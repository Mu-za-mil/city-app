package com.cityapp.auth.dto;

import com.cityapp.common.enums.Role;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Input DTO for user registration.
 *
 * WHY A SEPARATE DTO NOT DIRECT ENTITY:
 *   The User entity has: id, passwordHash, enabled, createdAt, updatedAt
 *   The registration request has: name, email, password (plaintext), role
 *
 *   If we accepted the User entity directly:
 *   - Client could set id (take over any user ID)
 *   - Client could set enabled=false (disable their own account)
 *   - Client could set role=SUPER_ADMIN (privilege escalation)
 *   - We'd receive passwordHash (we don't want plaintext hash)
 *
 *   The DTO is an explicit contract:
 *   "The client sends exactly these fields, nothing else."
 *   Any field not in the DTO is simply not accepted.
 *
 * WHY @Getter @Setter NOT @Data:
 *   @Data is fine for DTOs (they don't have JPA relationships).
 *   @Getter @Setter is used here for explicitness.
 *   Some teams use @Data for DTOs, @Getter for entities.
 *   Be consistent within a project. Pick one. Never mix.
 *
 * VALIDATION ANNOTATIONS:
 *   @NotBlank: not null AND not empty string AND not whitespace-only
 *   @Email: must match email regex (has @ and domain)
 *   @Size: min/max character length
 *   @Pattern: custom regex
 *   These run when @Valid is added to the controller parameter.
 */
@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
            message = "Password must contain at least one uppercase letter, " +
                    "one lowercase letter, one number, and one special character"
    )
    private String password;

    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone must be a valid 10-digit Indian number")
    private String phone;
    // Optional field. Pattern: Indian mobile numbers start with 6-9.

    // Role is optional — defaults to USER.
    // Clients can register as USER or SELLER.
    // SUPER_ADMIN is never self-assigned (created via seed data or migration only).
    private Role role = Role.USER;
}
