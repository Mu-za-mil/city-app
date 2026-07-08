package com.cityapp.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Input DTO for profile updates.
 * Only allows updating safe fields — not email, role, or password.
 * Password change is a separate endpoint with its own security flow.
 */
@Getter
@Setter
public class UpdateProfileRequest {

    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    private String profileImageUrl;
    // URL to profile image stored in S3/object storage.
    // The upload happens separately (client uploads to S3, gets URL, sends URL here).
}
