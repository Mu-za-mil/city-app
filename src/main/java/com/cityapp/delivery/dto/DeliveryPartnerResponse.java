package com.cityapp.delivery.dto;

import com.cityapp.delivery.entity.DeliveryPartner.*;
import lombok.*;
import java.math.BigDecimal;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryPartnerResponse {
    private Long        id;
    private Long        userId;
    private String      name;
    private String      phone;
    private PartnerStatus status;
    private VehicleType vehicleType;
    private String      vehicleNumber;
    private Integer     totalDeliveries;
    private BigDecimal  avgRating;
}
