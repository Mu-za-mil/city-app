package com.cityapp.store.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.PageResponse;
import com.cityapp.store.dto.CreateStoreRequest;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.entity.Store;
import com.cityapp.store.entity.StoreStatus;
import com.cityapp.store.mapper.StoreMapper;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
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

    /**
     * @Cacheable: on first call, queries DB and stores result in Redis.
     * On subsequent calls: returns Redis value. DB not queried.
     *
     * key = "#storeId":
     *   SpEL expression. Evaluates to the method parameter value.
     *   Redis key: "stores::10" (cacheName + "::" + key)
     *   Different storeId → different cache entry.
     *   store 10: "stores::10", store 11: "stores::11" — independent entries.
     *
     * unless = "#result == null":
     *   Don't cache null results.
     *   If store not found: AppException.notFound() is thrown (never reaches cache).
     *   Unless without this: null is cached → next request returns null from cache →
     *   no DB query → 200 OK with null data instead of 404.
     *   With this: null never cached → next request queries DB → correct 404.
     */
    @Cacheable(value = AppConstants.CACHE_STORES, key = "#storeId",
            unless = "#result == null")
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

    /**
     * Trending stores: read from the cache, populated by the scheduler.
     * Users ALWAYS get cached data. Never waits for the DB query.
     *
     * CACHE KEY: "trending::{days}:{limit}"
     * Different time windows and limits = different cache entries.
     * "trending::7:10" = top 10 stores in last 7 days.
     */
    @Cacheable(value = AppConstants.CACHE_TRENDING,
            key = "#days + ':' + #limit")
    @Transactional(readOnly = true)
    public List<StoreResponse> findTrending(int days, int limit) {
        int safeDays  = Math.max(1, Math.min(days, 90));
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return storeRepository.findTrending(safeDays, safeLimit)
                .stream()
                .map(storeMapper::toResponse)
                .toList();
    }

    /**
     * Proactively refreshes the trending stores cache every 5 minutes.
     * Runs the expensive query in the background so users never wait.
     *
     * @CacheEvict first: clears old cached value.
     * Then findTrending() call: executes query, populates cache.
     * Next user request: cache hit. Zero wait.
     *
     * WHY @Scheduled NOT @CachePut HERE:
     *   @CachePut always executes the method AND updates the cache.
     *   @Scheduled + @CacheEvict + method call: same effect but more explicit.
     *   Shows the intent: "periodically refresh this data."
     *
     * IMPORTANT: @CacheEvict and the method call must be in a @Transactional
     * context to work correctly with Spring AOP proxies.
     * @Scheduled runs outside the Spring transaction context.
     * Call a @Transactional method (findTrending) from the scheduled method.
     * Spring creates a proxy → @Cacheable runs correctly.
     */
    @Scheduled(fixedDelay = 300_000)  // Every 5 minutes
    @Transactional(readOnly = true)
    @CacheEvict(value = AppConstants.CACHE_TRENDING, allEntries = true)
    public void refreshTrendingCache() {
        log.debug("Refreshing trending stores cache...");
        // Pre-populate the most common requests:
        findTrending(7, 10);   // default: top 10 in last 7 days
        findTrending(30, 20);  // monthly: top 20 in last 30 days
        log.debug("Trending cache refreshed");
    }


    @Transactional(readOnly = true)
    public PageResponse<StoreResponse> getMyStores(User seller, Pageable pageable) {
        return PageResponse.from(
                storeRepository.findByOwnerIdOrderByCreatedAtDesc(
                                seller.getId(), pageable)
                        .map(storeMapper::toResponse));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * @CacheEvict: when a store is updated, remove it from cache.
     * Next read: cache miss → DB query → fresh data cached.
     *
     * WHY NOT @CachePut (update cache on write):
     *   @CachePut: after save(), put the new value in cache.
     *   Sounds good. Problem: the mapper needs the full entity with all JOIN data.
     *   If the save operation only has partial data (just the fields being updated):
     *   the cached response would have null for owner.name, category.name etc.
     *   Safer: evict on write, reload on next read.
     *   The one extra DB query on next read is cheap compared to serving bad data.
     *
     * allEntries = false (default): only evict the specific key.
     * We know exactly which store changed: evict only "stores::10", not all stores.
     * allEntries = true: evict ALL entries in the cache.
     * Never use unless you must (evicts unrelated stores unnecessarily).
     */
    @CacheEvict(value = {AppConstants.CACHE_STORES, AppConstants.CACHE_STORE_STATUS},
            key = "#storeId")
    @Transactional
    public StoreResponse updateStore(Long storeId, Long sellerId, CreateStoreRequest req) {
        // OWNERSHIP CHECK: returns 404 for both "not found" and "wrong owner"
        // This is the Information Hiding security pattern
        Store store = storeRepository.findByIdAndOwnerId(storeId, sellerId)
                .orElseThrow(() -> AppException.notFound("Store not found: " + storeId));

        if (req.getName() != null)        store.setName(req.getName());
        if (req.getDescription() != null) store.setDescription(req.getDescription());
        if (req.getAddress() != null)     store.setAddress(req.getAddress());
        if (req.getCity() != null)        store.setCity(req.getCity());
        if (req.getLogoUrl() != null)     store.setLogoUrl(req.getLogoUrl());
        if (req.getBannerUrl() != null)   store.setBannerUrl(req.getBannerUrl());
        if (req.getOpeningTime() != null) store.setOpeningTime(req.getOpeningTime());
        if (req.getClosingTime() != null) store.setClosingTime(req.getClosingTime());
        if (req.getMinOrderAmount() != null) store.setMinOrderAmount(req.getMinOrderAmount());

        if (req.getLatitude() != null && req.getLongitude() != null) {
            store.setCoordinates(req.getLatitude(), req.getLongitude());
        }

        if (req.getCategoryId() != null) {
            store.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> AppException.notFound("Category not found")));
        }

        return storeMapper.toResponse(storeRepository.save(store));
    }

    @CacheEvict(value = {AppConstants.CACHE_STORES, AppConstants.CACHE_STORE_STATUS},
            key = "#storeId")
    @Transactional
    public StoreResponse toggleOpenStatus(Long storeId, Long sellerId) {
        Store store = storeRepository.findByIdAndOwnerId(storeId, sellerId)
                .orElseThrow(() -> AppException.notFound("Store not found: " + storeId));

        if (store.getStatus() != StoreStatus.ACTIVE) {
            throw AppException.badRequest(
                    "Only ACTIVE stores can be toggled open/closed. " +
                            "Current status: " + store.getStatus());
        }

        boolean requestedOpen = !store.isOpen();
        store.setOpen(requestedOpen);
        store.setManualOpenOverride(requestedOpen);
        Store saved = storeRepository.save(store);

        log.info("Store {} toggled: open={} by seller={}",
                storeId, saved.isOpen(), sellerId);
        return storeMapper.toResponse(saved);
    }


    // ── Admin Operations ──────────────────────────────────────────────────────
    @CacheEvict(value = {AppConstants.CACHE_STORES, AppConstants.CACHE_STORE_STATUS},
            key = "#storeId")
    @Transactional
    public StoreResponse approveStore(Long storeId) {
        Store store = findStoreOrThrow(storeId);

        if (store.getStatus() == StoreStatus.ACTIVE) {
            throw AppException.conflict("Store is already approved");
        }

        store.setStatus(StoreStatus.ACTIVE);
        log.info("Store approved: id={} name='{}'", storeId, store.getName());
        return storeMapper.toResponse(storeRepository.save(store));
    }

    @CacheEvict(value = {AppConstants.CACHE_STORES, AppConstants.CACHE_STORE_STATUS},
            key = "#storeId")
    @Transactional
    public StoreResponse suspendStore(Long storeId) {
        Store store = findStoreOrThrow(storeId);
        store.setStatus(StoreStatus.SUSPENDED);
        store.setOpen(false);   // close it too — suspended stores don't accept orders
        log.info("Store suspended: id={}", storeId);
        return storeMapper.toResponse(storeRepository.save(store));
    }

    @Transactional(readOnly = true)
    public PageResponse<StoreResponse> getPendingApprovals(Pageable pageable) {
        return PageResponse.from(
                storeRepository.findByStatusOrderByCreatedAtAsc(
                                StoreStatus.PENDING_APPROVAL, pageable)
                        .map(storeMapper::toResponse));
    }

    // ── Scheduled: Auto Open/Close Based on Operating Hours ──────────────────

    /**
     * Runs every 5 minutes. Automatically opens/closes stores based on configured hours.
     *
     * WHY @Scheduled INSTEAD OF ON-DEMAND CHECK:
     *   Option A: Check operating hours on every API request.
     *     Pro: always accurate to the second.
     *     Con: every store fetch computes LocalTime.now(IST).
     *          1000 concurrent requests = 1000 time comparisons.
     *          Complexity in every query.
     *
     *   Option B: Scheduled job updates open flag periodically.
     *     Pro: store.open is a simple boolean. Any query reads it.
     *     Pro: sellers can manually override (close early, open for special event).
     *     Con: up to 5-minute delay between scheduled hours and actual open/close.
     *     Con: consumes scheduler thread every 5 minutes.
     *
     *   OUR CHOICE: Option B.
     *   The 5-minute delay is acceptable for operating hours.
     *   Sellers can manually toggle via toggleOpenStatus() for immediate effect.
     *   Manual override persists until the next scheduled run.
     */
    @Scheduled(fixedDelay = 300_000) // Every 5 minutes
    @Transactional
    public void autoToggleStoreHours() {
        LocalTime nowIST = LocalTime.now(IST);

        // Fetch only ACTIVE stores with configured hours
        // (PENDING/SUSPENDED stores should not be auto-opened)
        storeRepository.findByStatusOrderByCreatedAtAsc(
                        StoreStatus.ACTIVE, Pageable.unpaged())
                .forEach(store -> {
                    if (store.getOpeningTime() == null) return;
                    if (reconcileOpenState(store, nowIST)) {
                        storeRepository.save(store);
                        log.debug("Auto-reconciled store {}: open={}, manualOverride={}",
                                store.getId(), store.isOpen(), store.getManualOpenOverride());
                    }
                });
    }

    /**
     * Reconciles one store against its schedule.
     *
     * A seller's explicit open/close choice wins while it conflicts with the
     * configured schedule. Once the schedule naturally reaches that chosen
     * state (for example, a manual early close reaches closing time), the
     * override is cleared and normal scheduling resumes.
     *
     * Package-private to make the schedule/override state machine testable
     * with a fixed time, without waiting for the real scheduler.
     *
     * @return true when the entity changed and should be persisted
     */
    boolean reconcileOpenState(Store store, LocalTime now) {
        boolean shouldBeOpen = isWithinHours(store, now);
        Boolean manualOverride = store.getManualOpenOverride();

        if (manualOverride != null) {
            if (manualOverride != shouldBeOpen) {
                return false; // Respect seller's choice while schedule disagrees.
            }
            store.setManualOpenOverride(null); // Schedule caught up; resume automation.
            if (store.isOpen() != shouldBeOpen) {
                store.setOpen(shouldBeOpen);
            }
            return true;
        }

        if (store.isOpen() == shouldBeOpen) {
            return false;
        }

        store.setOpen(shouldBeOpen);
        return true;
    }

    boolean isWithinHours(Store store, LocalTime now) {
        LocalTime open = store.getOpeningTime();
        LocalTime close = store.getClosingTime();
        if (open == null || close == null || open.equals(close)) {
            return false;
        }

        // Opening time is inclusive; closing time is exclusive.
        // For example, 09:00–18:00 is open from 09:00 up to (but not including) 18:00.
        if (open.isBefore(close)) {
            return !now.isBefore(open) && now.isBefore(close);
        }

        // Overnight hours, e.g. 22:00–02:00: open from 22:00 until 02:00.
        return !now.isBefore(open) || now.isBefore(close);
    }

    /**
     * Cache the store's operational status separately from the full store data.
     * WHY SEPARATE CACHE:
     *   storeStatus TTL = 30 seconds (must be fresh for checkout validation).
     *   stores TTL = 5 minutes (full store profile changes rarely).
     *   If we cached open/closed in "stores" cache:
     *   Seller closes store → 5 minutes until buyers see it as closed.
     *   Buyers can checkout a "closed" store for 5 minutes. Wrong.
     *   Separate storeStatus cache with 30s TTL: buyers see status within 30 seconds.
     */
    @Cacheable(value = AppConstants.CACHE_STORE_STATUS, key = "#storeId")
    @Transactional(readOnly = true)
    public boolean isStoreOpen(Long storeId) {
        return storeRepository.findById(storeId)
                .map(Store::isOpen)
                .orElse(false);
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> AppException.notFound("Store not found: " + storeId));
    }
}
