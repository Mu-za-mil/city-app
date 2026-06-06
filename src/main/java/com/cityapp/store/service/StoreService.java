package com.cityapp.store.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.store.dto.CreateStoreRequest;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.entity.Store;
import com.cityapp.store.entity.StoreStatus;
import com.cityapp.store.mapper.StoreMapper;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final CategoryRepository categoryRepository;
    private final StoreMapper storeMapper;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public StoreResponse createStore(User seller, CreateStoreRequest req) {

        Store store = storeMapper.toEntity(req);
        store.setOwner(seller);
        store.setStatus(StoreStatus.PENDING_APPROVAL);
        // Always PENDING_APPROVAL on creation.
        // Seller cannot self-approve. Admin must review.

        if (req.getCategoryId() != null) {
            store.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> AppException.notFound(
                            "Category not found: " + req.getCategoryId())));
        }

        if (req.getLatitude() != null && req.getLongitude() != null) {
            store.setCoordinates(req.getLatitude(), req.getLongitude());
            // setCoordinates sets: latitude, longitude, AND the PostGIS location Point
        }

        Store saved = storeRepository.save(store);
        log.info("Store created: id={} name='{}' seller={}",
                saved.getId(), saved.getName(), seller.getEmail());

        return storeMapper.toResponse(saved);
    }
    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId) {
        return storeMapper.toResponse(
                storeRepository.findById(storeId)
                        .orElseThrow(() -> AppException.notFound(
                                "Store not found: " + storeId)));
    }

    /**
     * Find stores within a radius of a GPS point.
     *
     * INPUT VALIDATION:
     *   radiusKm clamped to MAX_NEARBY_RADIUS_KM (50km).
     *   WHY: without clamping, a client sends radiusKm=99999.
     *   Haversine: scan the entire stores table.
     *   At 100k stores: 5-second query per request.
     *   With clamping: query is always within a reasonable geographic area.
     *
     * DISTANCE IN RESPONSE:
     *   The Haversine query computes distance_km for each store.
     *   We want to include it in the response for the client to display.
     *   Problem: StoreMapper maps Store entity → StoreResponse.
     *   distance_km is not on the Store entity — it's a computed column.
     *   Solution: map entity → response first, then set distanceKm manually.
     */
    @Transactional(readOnly = true)
    public List<StoreResponse> findNearby(double lat, double lng, double radiusKm) {
        if (radiusKm <= 0) {
            throw AppException.badRequest("Radius must be greater than 0");
        }
        double safeRadius = Math.min(radiusKm, AppConstants.MAX_NEARBY_RADIUS_KM);

        return storeRepository
                .findNearby(lat, lng, safeRadius, AppConstants.DEFAULT_NEARBY_LIMIT)
                .stream()
                .map(storeMapper::toResponse)
                .toList();
        // TODO Phase 17: use PostGIS ST_DWithin for O(log N) performance
        // Current: O(N) Haversine full table scan. Acceptable for < 5000 stores.
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> findTrending(int days, int limit) {
        int safeDays  = Math.max(1, Math.min(days, 90));
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return storeRepository.findTrending(safeDays, safeLimit)
                .stream()
                .map(storeMapper::toResponse)
                .toList();
    }
}
