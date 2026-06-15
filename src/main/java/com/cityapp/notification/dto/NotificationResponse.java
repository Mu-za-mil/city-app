package com.cityapp.notification.dto;

import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationResponse {
    private Long    id;
    private String  title;
    private String  body;
    private String  type;
    private Long    referenceId;
    private String  referenceType;
    private boolean read;
    private Instant createdAt;
}