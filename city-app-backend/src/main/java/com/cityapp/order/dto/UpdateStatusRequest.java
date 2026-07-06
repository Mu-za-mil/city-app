package com.cityapp.order.dto;

import com.cityapp.order.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class UpdateStatusRequest {

    @NotNull(message = "Status is required")
    private OrderStatus status;

    private String cancellationReason;
    // Required when status = CANCELLED. Optional otherwise.
}
