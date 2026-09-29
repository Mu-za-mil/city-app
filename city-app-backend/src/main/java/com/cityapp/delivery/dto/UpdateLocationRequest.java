package com.cityapp.delivery.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter
public class UpdateLocationRequest {

    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
    private Double latitude;

    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
    private Double longitude;
}
