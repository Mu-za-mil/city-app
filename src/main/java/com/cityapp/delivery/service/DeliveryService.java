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
