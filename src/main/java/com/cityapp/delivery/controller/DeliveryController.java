package com.cityapp.delivery.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.delivery.dto.*;
import com.cityapp.delivery.service.DeliveryService;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    // ── Partner Registration ──────────────────────────────────────────────────

    @PostMapping("/register")
    @PreAuthorize("hasRole('DELIVERY_PARTNER')")
    public ResponseEntity<ApiResponse<DeliveryPartnerResponse>> register(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RegisterPartnerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        deliveryService.registerPartner(user, req)));
    }
}
