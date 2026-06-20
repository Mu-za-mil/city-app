package com.cityapp.delivery.dto;

import com.cityapp.delivery.entity.DeliveryPartner.*;
import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter
public class RegisterPartnerRequest {
    @NotNull(message = "Vehicle type is required")
    private VehicleType vehicleType;

    @NotBlank(message = "Vehicle number is required")
    @Pattern(regexp = "^[A-Z]{2} \\d{2} [A-Z]{1,2} \\d{4}$",
            message = "Vehicle number must be in format: TN 01 AB 1234")
    private String vehicleNumber;

    @NotBlank(message = "License number is required")
    private String licenseNumber;
}
