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

    // Seller's store list
    Page<Store> findByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    // Admin: pending approvals
    Page<Store> findByStatusOrderByCreatedAtAsc(StoreStatus status, Pageable pageable);

    // ── Nearby Search (Haversine Formula) ─────────────────────────────────────

    /**
     * Find ACTIVE stores within a radius, sorted by distance.
     *
     * WHY NATIVE SQL (not JPQL):
     *   JPQL is object-oriented SQL — it knows about entities and relationships.
     *   It does NOT support database-specific functions like acos(), cos(), sin().
     *   These trigonometric functions are PostgreSQL-specific.
     *   JPQL would fail to translate them.
     *   nativeQuery = true: sends this SQL directly to PostgreSQL.
     *
     * THE HAVERSINE FORMULA:
     *   distance = 6371 * acos(
     *     cos(radians(userLat)) * cos(radians(storeLat))
     *     * cos(radians(storeLng) - radians(userLng))
     *     + sin(radians(userLat)) * sin(radians(storeLat))
     *   )
     *   6371 = Earth's radius in kilometers.
     *   Result: distance in kilometers between two GPS points.
     *
     * PERFORMANCE:
     *   This is O(N) — scans every row and computes the formula.
     *   For 100 stores: fast. For 10,000 stores: 500ms. For 100,000: 5 seconds.
     *   Phase 17 (PostGIS) replaces this with O(log N) spatial index query.
     *   We document this limitation NOW so nobody is surprised later.
     *
     * NOTE: HAVING clause not WHERE because distance is a computed column.
     *   WHERE runs before SELECT (distance not computed yet).
     *   HAVING runs after SELECT (distance is now available).
     */
    @Query(value = """
            SELECT * FROM (
            SELECT *,
                (6371 * acos(
                    cos(radians(:lat)) * cos(radians(latitude))
                    * cos(radians(longitude) - radians(:lng))
                    + sin(radians(:lat)) * sin(radians(latitude))
                )) AS distance_km
            FROM stores
            WHERE status = 'ACTIVE'
                AND open = true
                AND latitude IS NOT NULL
                AND longitude IS NOT NULL
        ) AS subquery
        WHERE distance_km <= :radiusKm
        ORDER BY distance_km ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Store> findNearby(@Param("lat")      double lat,
                           @Param("lng")      double lng,
                           @Param("radiusKm") double radiusKm,
                           @Param("limit")    int    limit);

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
