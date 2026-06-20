package com.cityapp.delivery.dto;

import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class LocationDto {
    private Double  latitude;
    private Double  longitude;
    private Instant timestamp;
    private Long    partnerId;
    private String  partnerName;
}