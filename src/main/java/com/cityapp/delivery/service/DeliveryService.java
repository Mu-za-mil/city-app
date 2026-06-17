package com.cityapp.delivery.service;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.DeliveryAssignedEvent;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.exception.AppException;
import com.cityapp.config.RedisWebSocketRelay;
import com.cityapp.delivery.dto.*;
import com.cityapp.delivery.entity.DeliveryAssignment;
import com.cityapp.delivery.entity.DeliveryPartner;
import com.cityapp.delivery.repository.DeliveryAssignmentRepository;
import com.cityapp.delivery.repository.DeliveryPartnerRepository;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryPartnerRepository partnerRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;
    private final RedisWebSocketRelay webSocketRelay;
    private final StringRedisTemplate redisTemplate;

    private static final double ASSIGNMENT_RADIUS_KM = 10.0;
    private static final double METERS_PER_KM        = 1000.0;
    private static final int    LOCATION_HISTORY_TTL  = 3600;  // 1 hour
    private static final int    DB_WRITE_THROTTLE_SEC  = 30;   // write to DB at most every 30 seconds

    // ── Partner Management ────────────────────────────────────────────────────

    @Transactional
    public DeliveryPartnerResponse registerPartner(User user,
                                                   RegisterPartnerRequest req) {
        if (partnerRepository.findByUserId(user.getId()).isPresent()) {
            throw AppException.conflict(
                    "You already have a delivery partner profile");
        }

        DeliveryPartner partner = DeliveryPartner.builder()
                .user(user)
                .status(DeliveryPartner.PartnerStatus.PENDING)
                .vehicleType(req.getVehicleType())
                .vehicleNumber(req.getVehicleNumber())
                .licenseNumber(req.getLicenseNumber())
                .build();

        DeliveryPartner saved = partnerRepository.save(partner);
        log.info("Delivery partner registered: userId={} vehicle={}",
                user.getId(), req.getVehicleType());
        return toPartnerResponse(saved);
    }

    @Transactional
    public DeliveryPartnerResponse approvePartner(Long partnerId) {
        DeliveryPartner partner = findPartnerOrThrow(partnerId);
        partner.setStatus(DeliveryPartner.PartnerStatus.APPROVED);
        log.info("Delivery partner approved: id={}", partnerId);
        return toPartnerResponse(partnerRepository.save(partner));
    }

    @Transactional
    public DeliveryPartnerResponse goOnline(Long userId) {
        DeliveryPartner partner = partnerRepository.findByUserId(userId)
                .orElseThrow(() -> AppException.notFound(
                        "No delivery partner profile found"));

        if (partner.getStatus() == DeliveryPartner.PartnerStatus.SUSPENDED) {
            throw AppException.forbidden("Account suspended. Contact support.");
        }
        if (partner.getStatus() == DeliveryPartner.PartnerStatus.PENDING) {
            throw AppException.badRequest("Profile not yet approved by admin.");
        }

        partner.setStatus(DeliveryPartner.PartnerStatus.ACTIVE);
        log.info("Partner went online: userId={}", userId);
        return toPartnerResponse(partnerRepository.save(partner));
    }

    @Transactional
    public DeliveryPartnerResponse goOffline(Long userId) {
        DeliveryPartner partner = partnerRepository.findByUserId(userId)
                .orElseThrow(() -> AppException.notFound("Partner not found"));
        partner.setStatus(DeliveryPartner.PartnerStatus.INACTIVE);
        log.info("Partner went offline: userId={}", userId);
        return toPartnerResponse(partnerRepository.save(partner));
    }

    // ── Assignment ────────────────────────────────────────────────────────────

    /**
     * Assign the nearest available partner to an order.
     *
     * ASSIGNMENT STRATEGY:
     *   Find all ACTIVE partners within 10km of the store.
     *   Sort by distance (nearest first) using PostGIS <-> operator.
     *   Assign the nearest one.
     *
     *   WHY NOT AUTO-ASSIGN IN OrderService:
     *   OrderService creates the order. DeliveryService assigns partners.
     *   Single Responsibility Principle: each service has one job.
     *   Also: assignment can be triggered manually (admin) or automatically (Saga).
     *
     *   PRODUCTION CONSIDERATION:
     *   In practice: ask the nearest partner to accept (not force-assign).
     *   If partner doesn't accept within 30 seconds: try the next nearest.
     *   For Phase 11: direct assignment. "Accept/reject" flow in Phase 15+.
     */
    @Transactional
    public DeliveryAssignmentResponse assignPartner(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if (order.getStatus() != OrderStatus.CONFIRMED
                && order.getStatus() != OrderStatus.READY) {
            throw AppException.badRequest(
                    "Cannot assign delivery for order in status: " +
                            order.getStatus());
        }

        if (assignmentRepository.findByOrderId(orderId).isPresent()) {
            throw AppException.conflict("Order already has a delivery assignment");
        }

        Double storeLat = order.getStore().getLatitude();
        Double storeLng = order.getStore().getLongitude();

        if (storeLat == null || storeLng == null) {
            throw AppException.badRequest(
                    "Store has no GPS coordinates. Cannot find nearby partners.");
        }

        // Find nearest available partners
        double radiusMeters = ASSIGNMENT_RADIUS_KM * METERS_PER_KM;
        List<DeliveryPartner> candidates = partnerRepository
                .findAvailableNearStore(storeLat, storeLng, radiusMeters, 5);

        if (candidates.isEmpty()) {
            throw AppException.badRequest(
                    "No delivery partners available within " +
                            ASSIGNMENT_RADIUS_KM + "km of this store. " +
                            "Try again in a few minutes.");
        }

        // Assign the nearest available partner
        DeliveryPartner partner = candidates.get(0);

        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .order(order)
                .partner(partner)
                .status(DeliveryAssignment.AssignmentStatus.ASSIGNED)
                .build();

        DeliveryAssignment saved = assignmentRepository.save(assignment);

        // Update order status
        order.setStatus(OrderStatus.OUT_FOR_DELIVERY);
        orderRepository.save(order);

        // Publish event (notifies buyer + partner via Kafka → notification-service)
        eventPublisher.publishDeliveryAssigned(
                DeliveryAssignedEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .orderId(orderId)
                        .partnerId(partner.getId())
                        .partnerName(partner.getUser().getName())
                        .partnerPhone(partner.getUser().getPhone())
                        .storeId(order.getStore().getId())
                        .buyerId(order.getUser().getId())
                        .assignedAt(Instant.now())
                        .build());

        log.info("Delivery assigned: orderId={} partnerId={} partnerName={}",
                orderId, partner.getId(), partner.getUser().getName());

        return toAssignmentResponse(saved);
    }

    // ── Location Updates (THE CRITICAL PERFORMANCE PATH) ──────────────────────

    /**
     * Updates partner's GPS location.
     * Called by the partner's mobile app every 30 seconds.
     *
     * THREE-LAYER LOCATION STRATEGY:
     *
     * LAYER 1: Redis (always — every ping)
     *   SET "delivery:location:{orderId}" {lat, lng, timestamp}
     *   Sub-millisecond write. Always fresh.
     *   Used for: GET /delivery/{orderId}/location (instant read)
     *   Buyers polling (instead of WebSocket): fast Redis read, no DB.
     *
     * LAYER 2: WebSocket Push (always — every ping)
     *   Push to buyer's WebSocket: "your partner just moved to (13.04, 80.23)"
     *   Buyer's map marker updates in real-time.
     *   No polling needed. Instant.
     *
     * LAYER 3: PostgreSQL (throttled — at most once per 30 seconds)
     *   UPDATE delivery_partners SET current_location = ... WHERE id = ?
     *   Used for: "where was partner X at time T?" (analytics, dispute resolution)
     *
     * WHY THROTTLE THE DB WRITE:
     *   100 active deliveries × 2 pings/min = 200 DB writes/minute without throttle.
     *   With throttle (once per 30 seconds): 200 → 100 DB writes/minute.
     *   At 1000 active deliveries: 1000 → 500. Manageable.
     *   Without throttle at 10,000 deliveries: 20,000 DB writes/minute.
     *   Could overwhelm PostgreSQL connection pool.
     *
     * HOW THROTTLE WORKS (Redis SETNX):
     *   SET "delivery:dbwrite:{partnerId}" "1" IF NOT EXISTS EXPIRE 30
     *   First call: key doesn't exist → set it → DO the DB write.
     *   Subsequent calls within 30 seconds: key exists → SKIP the DB write.
     *   After 30 seconds: key expires → next call does the DB write again.
     *   Zero code overhead. Redis handles the timing.
     */
    @Transactional
    public void updateLocation(Long partnerId, Long orderId,
                               UpdateLocationRequest req) {

        // Verify assignment ownership
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndPartnerId(orderId, partnerId)
                .orElseThrow(() -> AppException.notFound(
                        "No active assignment for orderId=" + orderId +
                                " partnerId=" + partnerId));

        if (assignment.getStatus() == DeliveryAssignment.AssignmentStatus.DELIVERED) {
            throw AppException.badRequest("Delivery already completed");
        }

        // ── Layer 1: Redis (always) ───────────────────────────────────────────

        LocationDto locationDto = LocationDto.builder()
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .timestamp(Instant.now())
                .partnerId(partnerId)
                .partnerName(assignment.getPartner().getUser().getName())
                .build();

        String locationKey = AppConstants.REDIS_DELIVERY_LOCATION + orderId;
        try {
            redisTemplate.opsForValue().set(
                    locationKey,
                    req.getLatitude() + "," + req.getLongitude(),
                    LOCATION_HISTORY_TTL,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Failed to write location to Redis: {}", e.getMessage());
            // Non-critical: DB write still happens. Continue.
        }

        // ── Layer 2: WebSocket Push (always) ──────────────────────────────────

        Long buyerUserId = assignment.getOrder().getUser().getId();
        webSocketRelay.sendDeliveryLocation(buyerUserId, locationDto);

        // ── Layer 3: PostgreSQL (throttled) ───────────────────────────────────

        String dbWriteKey = AppConstants.REDIS_DELIVERY_DB_WRITE + partnerId;
        Boolean shouldWrite = redisTemplate.opsForValue()
                .setIfAbsent(dbWriteKey, "1",
                        DB_WRITE_THROTTLE_SEC, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(shouldWrite)) {
            // This is the first ping in the last 30 seconds — do the DB write
            DeliveryPartner partner = assignment.getPartner();
            partner.setCurrentCoordinates(req.getLatitude(), req.getLongitude());
            partnerRepository.save(partner);

            log.debug("Location DB write: partnerId={} lat={} lng={}",
                    partnerId, req.getLatitude(), req.getLongitude());
        } else {
            log.debug("Location DB write throttled for partnerId={}", partnerId);
        }
    }

    /**
     * Get current location of a delivery (from Redis — instant).
     * Buyers poll this as a fallback if WebSocket is not supported.
     */
    public LocationDto getCurrentLocation(Long orderId) {
        String key = AppConstants.REDIS_DELIVERY_LOCATION + orderId;
        String cached = redisTemplate.opsForValue().get(key);

        if (cached == null) {
            // Not in Redis: check DB (partner may have been offline for > 1 hour)
            return assignmentRepository.findByOrderId(orderId)
                    .map(a -> LocationDto.builder()
                            .latitude(a.getPartner().getCurrentLatitude())
                            .longitude(a.getPartner().getCurrentLongitude())
                            .partnerId(a.getPartner().getId())
                            .partnerName(a.getPartner().getUser().getName())
                            .build())
                    .orElseThrow(() -> AppException.notFound(
                            "No location data for order: " + orderId));
        }

        String[] parts = cached.split(",");
        return LocationDto.builder()
                .latitude(Double.parseDouble(parts[0]))
                .longitude(Double.parseDouble(parts[1]))
                .build();
    }


    // ── Private Helpers ───────────────────────────────────────────────────────

    private DeliveryPartner findPartnerOrThrow(Long partnerId) {
        return partnerRepository.findById(partnerId)
                .orElseThrow(() -> AppException.notFound(
                        "Delivery partner not found: " + partnerId));
    }

    private DeliveryPartnerResponse toPartnerResponse(DeliveryPartner p) {
        return DeliveryPartnerResponse.builder()
                .id(p.getId())
                .userId(p.getUser().getId())
                .name(p.getUser().getName())
                .phone(p.getUser().getPhone())
                .status(p.getStatus())
                .vehicleType(p.getVehicleType())
                .vehicleNumber(p.getVehicleNumber())
                .totalDeliveries(p.getTotalDeliveries())
                .avgRating(p.getAvgRating())
                .build();
    }

    private DeliveryAssignmentResponse toAssignmentResponse(DeliveryAssignment a) {
        return DeliveryAssignmentResponse.builder()
                .id(a.getId())
                .orderId(a.getOrder().getId())
                .partnerId(a.getPartner().getId())
                .partnerName(a.getPartner().getUser().getName())
                .partnerPhone(a.getPartner().getUser().getPhone())
                .status(a.getStatus())
                .assignedAt(a.getAssignedAt())
                .pickedUpAt(a.getPickedUpAt())
                .deliveredAt(a.getDeliveredAt())
                .build();
    }

}
