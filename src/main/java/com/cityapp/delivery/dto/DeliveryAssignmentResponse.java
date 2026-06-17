package com.cityapp.delivery.dto;

import com.cityapp.delivery.entity.DeliveryAssignment.*;
import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryAssignmentResponse {
    private Long             id;
    private Long             orderId;
    private Long             partnerId;
    private String           partnerName;
    private String           partnerPhone;
    private AssignmentStatus status;
    private Instant          assignedAt;
    private Instant          pickedUpAt;
    private Instant          deliveredAt;
}
