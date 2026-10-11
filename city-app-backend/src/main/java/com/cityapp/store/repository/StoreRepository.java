package com.cityapp.store.repository;

import com.cityapp.store.entity.Store;
import com.cityapp.store.entity.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    // ── Ownership Queries ─────────────────────────────────────────────────────

    /**
     * THE OWNERSHIP PATTERN — most important query in this module.
     *
     * WHY NOT: findById(storeId) then check if owner matches?
     *   findById: SELECT * FROM stores WHERE id = ?
     *   Then: if (!store.getOwner().getId().equals(ownerId)) throw forbidden
     *
     *   This leaks information: the store EXISTS (404 vs 403).
     *   Attacker who gets 403: "this store ID exists, just not mine."
     *   Can enumerate store IDs.
     *
     * WHY: findByIdAndOwnerId returns Optional.empty() for BOTH cases:
     *   "store doesn't exist" and "store exists but wrong owner"
     *   BOTH throw AppException.notFound → 404.
     *   Attacker sees: 404 for invalid AND 404 for not-owned.
     *   Cannot distinguish. Cannot enumerate.
     *
     * This is the Information Hiding principle applied to API security.
     */
    Optional<Store> findByIdAndOwnerIdAndStatusNot(Long id, Long ownerId, StoreStatus status);

    default Optional<Store> findByIdAndOwnerId(Long id, Long ownerId) {
        return findByIdAndOwnerIdAndStatusNot(id, ownerId, StoreStatus.CLOSED);
    }

    /**
     * Ownership-only lookup for historical seller data. Unlike findByIdAndOwnerId,
     * this deliberately includes CLOSED stores so sellers can still view past orders.
     */
    @Query("select s from Store s where s.id = :storeId and s.owner.id = :ownerId")
    Optional<Store> findOwnedStoreById(@Param("storeId") Long storeId,
                                       @Param("ownerId") Long ownerId);

    // Seller's store list
    Page<Store> findByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    // Admin: pending approvals
    Page<Store> findByStatusOrderByCreatedAtAsc(StoreStatus status, Pageable pageable);

    // ── Nearby Search (PostGIS) ───────────────────────────────────────────────

    /**
     * Find open ACTIVE stores within a radius, ordered by geodesic distance.
     *
     * ST_DWithin on the geography column applies the radius in metres and can
     * use idx_stores_location_gist to narrow the candidate set before sorting.
     * The partial GiST index predicate (status = 'ACTIVE') matches this query.
     * ST_Distance is used only for the nearby candidates to order nearest first.
     */
    @Query(value = """
            SELECT s.*
            FROM stores s
            WHERE s.status = 'ACTIVE'
              AND s.open = true
              AND s.location IS NOT NULL
              AND ST_DWithin(
                    s.location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    :radiusMeters
              )
            ORDER BY ST_Distance(
                s.location,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
            ) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Store> findNearby(@Param("lat") double lat,
                           @Param("lng") double lng,
                           @Param("radiusMeters") double radiusMeters,
                           @Param("limit") int limit);

    /**
     * Find stores within a bounding box (for map pan/zoom in mobile app).
     * Uses PostGIS && operator (bounding box overlap) — very fast with GIST index.
     */
    @Query(value = """
        SELECT * FROM stores
        WHERE status = 'ACTIVE'
          AND location IS NOT NULL
          AND location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
        ORDER BY name ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Store> findWithinBoundingBox(@Param("minLat") double minLat,
                                      @Param("minLng") double minLng,
                                      @Param("maxLat") double maxLat,
                                      @Param("maxLng") double maxLng,
                                      @Param("limit")  int    limit);

    // ── Analytics ─────────────────────────────────────────────────────────────

    /**
     * Trending stores: most orders in the last N days.
     * Used for the trending section on the home screen.
     */
    @Query(value = """
        SELECT s.*
        FROM stores s
        JOIN orders o ON o.store_id = s.id
        WHERE s.status = 'ACTIVE'
          AND o.created_at > NOW() - (:days || ' days')::INTERVAL
          AND o.status NOT IN ('CANCELLED')
        GROUP BY s.id
        ORDER BY COUNT(o.id) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Store> findTrending(@Param("days")  int days,
                             @Param("limit") int limit);
}
