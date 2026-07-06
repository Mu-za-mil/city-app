package com.cityapp.delivery.entity;

import com.cityapp.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "delivery_partners")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryPartner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
    // ONE-TO-ONE: each user is at most one delivery partner.
    // @OneToOne not @ManyToOne: a user cannot be TWO delivery partners.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PartnerStatus status = PartnerStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type")
    private VehicleType vehicleType;

    @Column(name = "vehicle_number")
    private String vehicleNumber;   // e.g., "TN 01 AB 1234"

    @Column(name = "license_number")
    private String licenseNumber;

    // ── Current GPS Location ──────────────────────────────────────────────────

    @Column(name = "current_latitude")
    private Double currentLatitude;

    @Column(name = "current_longitude")
    private Double currentLongitude;

    @Column(name = "current_location",
            columnDefinition = "geography(Point,4326)")
    private Point currentLocation;
    // PostGIS GEOGRAPHY column. Used by the "find nearest partner" query.
    // The V1 migration trigger auto-populates this from currentLatitude/Longitude.
    // Also set explicitly via setCurrentCoordinates().

    // ── Statistics ────────────────────────────────────────────────────────────

    @Column(name = "total_deliveries", nullable = false)
    @Builder.Default
    private Integer totalDeliveries = 0;

    @Column(name = "avg_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal avgRating = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Convenience method ────────────────────────────────────────────────────

    /**
     * Sets all three location fields simultaneously:
     * currentLatitude, currentLongitude, AND the PostGIS Point.
     *
     * WHY A METHOD NOT JUST SETTERS:
     *   If you call setCurrentLatitude(13.04) then setCurrentLongitude(80.23)
     *   separately, the Point column may be inconsistent between the two calls.
     *   This method updates all three atomically in the Java object.
     *   The DB trigger also does this, but Java object consistency matters too.
     */
    public void setCurrentCoordinates(double latitude, double longitude) {
        this.currentLatitude  = latitude;
        this.currentLongitude = longitude;

        // Create PostGIS Point (longitude first — x-axis convention)
        org.locationtech.jts.geom.GeometryFactory gf =
                new org.locationtech.jts.geom.GeometryFactory(
                        new org.locationtech.jts.geom.PrecisionModel(), 4326);
        this.currentLocation = gf.createPoint(
                new org.locationtech.jts.geom.Coordinate(longitude, latitude));
    }

    public enum PartnerStatus {
        PENDING,    // registered, awaiting approval
        APPROVED,   // approved, not yet active for today
        ACTIVE,     // online, available for deliveries
        INACTIVE,   // offline (went off-duty)
        SUSPENDED   // admin action
    }

    public enum VehicleType {
        BICYCLE, MOTORCYCLE, CAR, VAN
    }
}
