package com.cityapp.auth.dto;

import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class SessionResponse {
    private Long    id;
    private String  deviceInfo;
    private String  ipAddress;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
}
