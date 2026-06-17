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

    @PostMapping("/online")
    @PreAuthorize("hasRole('DELIVERY_PARTNER')")
    public ResponseEntity<ApiResponse<DeliveryPartnerResponse>> goOnline(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
                deliveryService.goOnline(user.getId())));
    }

    @PostMapping("/offline")
    @PreAuthorize("hasRole('DELIVERY_PARTNER')")
    public ResponseEntity<ApiResponse<DeliveryPartnerResponse>> goOffline(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
                deliveryService.goOffline(user.getId())));
    }

    // ── Location Updates (from partner's app) ─────────────────────────────────

    @PostMapping("/{orderId}/location")
    @PreAuthorize("hasRole('DELIVERY_PARTNER')")
    public ResponseEntity<ApiResponse<Void>> updateLocation(
            @AuthenticationPrincipal User partner,
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateLocationRequest req) {

        deliveryService.updateLocation(partner.getId(), orderId, req);
        return ResponseEntity.ok(ApiResponse.ok("Location updated"));
    }

    // ── Buyer: get current location ───────────────────────────────────────────

    @GetMapping("/{orderId}/location")
    public ResponseEntity<ApiResponse<LocationDto>> getLocation(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(
                deliveryService.getCurrentLocation(orderId)));
    }

    // ── Admin: assign delivery partner ────────────────────────────────────────

    @PostMapping("/{orderId}/assign")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('SELLER')")
    public ResponseEntity<ApiResponse<DeliveryAssignmentResponse>> assign(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(
                deliveryService.assignPartner(orderId)));
    }

    // ── Partner: mark delivered ───────────────────────────────────────────────

    @PostMapping("/{orderId}/delivered")
    @PreAuthorize("hasRole('DELIVERY_PARTNER')")
    public ResponseEntity<ApiResponse<DeliveryAssignmentResponse>> markDelivered(
            @AuthenticationPrincipal User partner,
            @PathVariable Long orderId,
            @RequestParam(required = false) String proofUrl) {
        return ResponseEntity.ok(ApiResponse.ok(
                deliveryService.markDelivered(partner.getId(), orderId, proofUrl)));
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    @PostMapping("/{partnerId}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DeliveryPartnerResponse>> approve(
            @PathVariable Long partnerId) {
        return ResponseEntity.ok(ApiResponse.ok(
                deliveryService.approvePartner(partnerId)));
    }
}
