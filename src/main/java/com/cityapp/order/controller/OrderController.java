package com.cityapp.order.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.common.response.PageResponse;
import com.cityapp.order.dto.*;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.service.OrderService;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // ── Buyer endpoints ───────────────────────────────────────────────────────

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.getOrder(orderId, buyer.getId())));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> myOrders(
            @AuthenticationPrincipal User buyer,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.getMyOrders(
                        buyer.getId(),
                        PageRequest.of(page, size,
                                Sort.by("createdAt").descending()))));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long orderId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Cancelled by buyer";
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.cancelOrder(orderId, buyer.getId(), reason)));
    }

    // ── Seller endpoints ──────────────────────────────────────────────────────


    @GetMapping("/store/{storeId}")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> storeOrders(
            @PathVariable Long storeId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.getStoreOrders(
                        storeId, status,
                        PageRequest.of(page, size,
                                Sort.by("createdAt").descending()))));
    }

    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasRole('SELLER') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> updateStatus(
            @AuthenticationPrincipal User seller,
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateStatusRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.updateStatus(orderId, seller.getId(), req)));
    }
}
