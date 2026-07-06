package com.cityapp.delivery.repository;

import com.cityapp.delivery.entity.DeliveryPartner;
import com.cityapp.delivery.entity.DeliveryPartner.PartnerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryPartnerRepository
        extends JpaRepository<DeliveryPartner, Long> {

    Optional<DeliveryPartner> findByUserId(Long userId);

    /**
     * Find ACTIVE delivery partners near a store, sorted by distance.
     * Uses PostGIS spatial index (GIST on current_location).
     *
     * WHEN ASSIGNING A DELIVERY:
     *   Find the nearest ACTIVE partner within 10km of the store.
     *   Sort by distance: nearest partner assigned first.
     *   Limit 5: consider top 5 candidates (first available accepts).
     *
     * WHY <-> OPERATOR (KNN — K-Nearest-Neighbour):
     *   Uses the spatial index directly for sorted nearest results.
     *   Faster than computing all distances then sorting.
     *   index_scan → sort by distance in one operation.
     *
     * FALLBACK TO HAVERSINE IF POSTGIS UNAVAILABLE:
     *   The PostGIS query uses the spatial index.
     *   If spatial index not ready (first boot before migration):
     *   Falls back to computing distance in memory from the results.
     *   Phase 17 ensures PostGIS is always available.
     */
    @Query(value = """
        -- Include partners that have either the PostGIS geography column set
        -- OR have latitude/longitude populated (fallback when DB write is throttled).
        SELECT dp.*
        FROM delivery_partners dp
        WHERE dp.status = 'ACTIVE'
          AND (
                (dp.current_location IS NOT NULL
                 AND ST_DWithin(
                     dp.current_location,
                     ST_SetSRID(ST_MakePoint(:storeLng, :storeLat), 4326)::geography,
                     :radiusMeters
                 ))
               OR
                (dp.current_location IS NULL
                 AND dp.current_latitude IS NOT NULL
                 AND dp.current_longitude IS NOT NULL
                 AND ST_DWithin(
                     ST_SetSRID(ST_MakePoint(dp.current_longitude, dp.current_latitude), 4326)::geography,
                     ST_SetSRID(ST_MakePoint(:storeLng, :storeLat), 4326)::geography,
                     :radiusMeters
                 ))
              )
        ORDER BY ST_Distance(
                  COALESCE(
                    dp.current_location,
                    ST_SetSRID(ST_MakePoint(dp.current_longitude, dp.current_latitude), 4326)::geography
                  ),
                  ST_SetSRID(ST_MakePoint(:storeLng, :storeLat), 4326)::geography
              )
        LIMIT :limit
        """, nativeQuery = true)
    List<DeliveryPartner> findAvailableNearStore(
            @Param("storeLat")     double storeLat,
            @Param("storeLng")     double storeLng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit")        int    limit);

    // Admin: list partners by status
    List<DeliveryPartner> findByStatus(PartnerStatus status);
}
