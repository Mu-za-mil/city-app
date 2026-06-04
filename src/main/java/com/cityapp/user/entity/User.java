package com.cityapp.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The User entity — the most important class in the application.
 * Every other entity has a foreign key reference to this one.
 *
 * WHY IMPLEMENTS UserDetails:
 *   UserDetails is Spring Security's interface for "the currently logged-in user".
 *   Without it: Spring Security returns a generic Object from @AuthenticationPrincipal.
 *   You'd have to cast: (User) principal — fragile and verbose.
 *
 *   With UserDetails: @AuthenticationPrincipal User user works directly in controllers.
 *   No casting. Type-safe. The entity IS the security principal.
 *
 * WHY @Getter NOT @Data:
 *   @Data generates equals() and hashCode() from ALL fields.
 *   On JPA entities: this causes infinite recursion on bidirectional relationships.
 *   Example: User has List<Order> orders. Order has User user.
 *   @Data hashCode: User.hashCode() calls Order.hashCode() calls User.hashCode() → StackOverflow
 *
 *   Fix: @Getter (generates getters only) + @EqualsAndHashCode(of="id") if needed.
 *   For entities: always use @Getter, never @Data.
 *
 * WHY @Builder + @NoArgsConstructor + @AllArgsConstructor together:
 *   @Builder generates a builder pattern: User.builder().name("Ravi").build()
 *   BUT @Builder only generates the all-args constructor.
 *   JPA requires a no-args constructor to instantiate entities when loading from DB.
 *   @NoArgsConstructor: adds the no-args constructor for JPA.
 *   @AllArgsConstructor: needed by @Builder (it calls the all-args constructor).
 *   All three together: Builder pattern + JPA compatibility.
 *   Missing @NoArgsConstructor: HibernateException: No default constructor.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // IDENTITY: maps to BIGSERIAL in PostgreSQL. DB generates the ID on INSERT.
    // WHY IDENTITY not SEQUENCE: SEQUENCE creates a separate sequence object.
    // IDENTITY ties the strategy to the column definition (BIGSERIAL).
    // Both work. IDENTITY is simpler for PostgreSQL.

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String email;
    // unique = true: Hibernate adds a UNIQUE constraint.
    // We already have this in V1 migration. Declaring it here too:
    // - Documents the constraint at the entity level (code is documentation)
    // - Hibernate validates on startup that the DB constraint exists

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    // Column name: "password_hash" in DB, "passwordHash" in Java.
    // WHY rename: Java convention is camelCase. DB convention is snake_case.
    // @Column(name = ...) bridges the naming difference.

    @Column(unique = true)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;
    // EnumType.STRING: stores "USER", "SELLER" etc. as VARCHAR in DB.
    // EnumType.ORDINAL (the default): stores 0, 1, 2... as integers.
    // WHY STRING not ORDINAL: ordinal is fragile.
    //   Add a new role at position 0: every existing role shifts by 1.
    //   "SELLER" (was ordinal 1) becomes "NEW_ROLE" (ordinal 1).
    //   Silent data corruption. Always use EnumType.STRING.
    //
    // @Builder.Default: sets default value when using Builder pattern.
    // Without it: User.builder().name("Ravi").build().getRole() returns NULL.
    // With it: returns Role.USER.

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "account_non_locked", nullable = false)
    @Builder.Default
    private boolean accountNonLocked = true;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    // @CreationTimestamp: Hibernate sets this on INSERT, never on UPDATE.
    // updatable = false: even if code tries to update it, Hibernate ignores it.
    // createdAt is immutable — once set, it never changes.

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
    // @UpdateTimestamp: Hibernate sets this on every UPDATE.

    // ── UserDetails Interface Implementation ──────────────────────────────────
    // These methods are called by Spring Security during authentication.

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Return the user's role as a Spring Security authority.
        // Spring Security convention: role names are prefixed with "ROLE_".
        // Role.SELLER → "ROLE_SELLER"
        // @PreAuthorize("hasRole('SELLER')") checks for "ROLE_SELLER" authority.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        // Spring Security calls this to get the stored password hash for comparison.
        // Returns the BCrypt hash, NOT the plaintext password.
        return passwordHash;
    }

    @Override
    public String getUsername() {
        // Spring Security uses this as the "username" for authentication.
        // We use email as the username (unique identifier for login).
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        // We don't expire accounts. Always return true.
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // We don't expire credentials. Refresh tokens handle session expiry.
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
