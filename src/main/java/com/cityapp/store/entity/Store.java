package com.cityapp.store.entity;

import com.cityapp.category.entity.Category;
import com.cityapp.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.time.LocalTime;
import java.math.BigDecimal;

@Entity
@Table(name = "stores")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Ownership ─────────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    // LAZY: when we load a Store, we don't automatically load the entire User.
    // We only load the User when we call store.getOwner().
    // If EAGER: every store query triggers an additional SELECT for the owner.
    // For a list of 20 stores: 21 queries (1 for stores + 20 for owners).
    // With LAZY + JOIN FETCH in custom query: 1 query total.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    // ── Basic Info ────────────────────────────────────────────────────────────
    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    private String address;
    private String city;
    private String state;
    private String pincode;

    // ── GPS Location ──────────────────────────────────────────────────────────
    private Double latitude;
    private Double longitude;

    @Column(columnDefinition = "geography(Point,4326)")
    private Point location;
    // PostGIS GEOGRAPHY column for fast spatial queries.
    // The V1 migration trigger auto-populates this when latitude/longitude are set.
    // We use setCoordinates() convenience method to update all three at once.

    // ── Operating Hours ───────────────────────────────────────────────────────
    @Column(name = "opening_time")
    private LocalTime openingTime;

    @Column(name = "closing_time")
    private LocalTime closingTime;
    // WHY LocalTime not Instant:
    // "Opens at 9 AM" is a daily recurring time, not a single moment in history.
    // LocalTime represents just the time part: 09:00:00.
    // We compare against IST LocalTime in StoreValidator.
    // The IST timezone is applied at comparison time, not storage time.

    @Column(nullable = false)
    @Builder.Default
    private boolean open = false;
    // Operational toggle: seller controls this daily.
    // Separate from status (admin-controlled).
    // BOTH must be true for checkout: status=ACTIVE AND open=true.

    // ── Status ────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StoreStatus status = StoreStatus.PENDING_APPROVAL;

    // ── Business Rules ────────────────────────────────────────────────────────
    @Column(name = "min_order_amount", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "avg_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal avgRating = BigDecimal.ZERO;

    @Column(name = "total_reviews")
    @Builder.Default
    private Integer totalReviews = 0;

    // ── Media ─────────────────────────────────────────────────────────────────
    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "banner_url")
    private String bannerUrl;

    // ── Timestamps ────────────────────────────────────────────────────────────
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Convenience Method ────────────────────────────────────────────────────

    /**
     * Sets GPS coordinates AND the PostGIS location column simultaneously.
     *
     * WHY A CONVENIENCE METHOD:
     *   PostGIS needs the location column to contain a Point geometry.
     *   The DB trigger also does this (from V1 migration).
     *   But JPA works with the Java object BEFORE the trigger fires.
     *   Setting all three here ensures Java object is consistent with DB.
     *
     * NOTE: longitude comes FIRST in Point (x-axis = longitude, y-axis = latitude)
     *   This is the WGS84 convention. Common mistake: swap lat/lng in Point.
     *   If swapped: stores appear in the ocean, nearby search returns nothing.
     */
    public void setCoordinates(double latitude, double longitude) {
        this.latitude  = latitude;
        this.longitude = longitude;
        org.locationtech.jts.geom.GeometryFactory gf =
                new org.locationtech.jts.geom.GeometryFactory(
                        new org.locationtech.jts.geom.PrecisionModel(), 4326);
        this.location = gf.createPoint(
                new org.locationtech.jts.geom.Coordinate(longitude, latitude));
    }
}
