package com.cityapp.auth.dto;

import com.cityapp.common.enums.Role;
import lombok.*;

import java.time.Instant;

/**
 * Output DTO for user data.
 *
 * WHAT'S INCLUDED:
 *   id, name, email, phone, role, enabled, profileImageUrl, createdAt
 *
 * WHAT'S INTENTIONALLY EXCLUDED:
 *   passwordHash — NEVER send password hashes to clients. Ever.
 *   accountNonLocked — internal security state, not for clients
 *   updatedAt — internal tracking, not needed by clients
 *
 * WHY @NoArgsConstructor AND @AllArgsConstructor with @Builder:
 *   @Builder alone generates ONLY the all-args constructor.
 *   Jackson (JSON serialisation) needs the no-args constructor to create
 *   instances when deserialising from cache (Redis) or test fixtures.
 *   @NoArgsConstructor: adds the no-args constructor.
 *   @AllArgsConstructor: @Builder uses this.
 *   All three: builder pattern + Jackson + manual construction all work.
 *
 * THIS IS THE BUG WE HIT:
 *   UserResponse was @Builder @Data.
 *   @Data generates equals/hashCode/toString but NOT all-args constructor.
 *   @Builder needs all-args constructor to exist.
 *   @Data + @Builder causes: "No suitable constructor found".
 *   Fix: always use @Builder @NoArgsConstructor @AllArgsConstructor together.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long    id;
    private String  name;
    private String  email;
    private String  phone;
    private Role    role;
    private boolean enabled;
    private String  profileImageUrl;
    private Instant createdAt;
}
