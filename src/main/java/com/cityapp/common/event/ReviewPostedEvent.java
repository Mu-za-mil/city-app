package com.cityapp.common.event;

import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewPostedEvent {

    private String  eventId;
    private Long    reviewId;
    private Long    reviewerId;
    private String  reviewerName;
    private String  targetType;  // "PRODUCT" or "STORE"
    private Long    targetId;
    private Integer rating;
    private Long    sellerId;
    private Instant timestamp;
}
