package com.cityapp.cart.controller;

import com.cityapp.cart.dto.*;
import com.cityapp.cart.service.CartService;
import com.cityapp.common.response.ApiResponse;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    // ── Per-store cart operations ─────────────────────────────────────────────

    @PostMapping("/{storeId}/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId,
            @Valid @RequestBody AddToCartRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.addItem(buyer, storeId, req)));
    }

    @DeleteMapping("/{storeId}")
    public ResponseEntity<ApiResponse<Void>> clearCart(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId) {
        cartService.clearCart(buyer.getId(), storeId);
        return ResponseEntity.ok(ApiResponse.ok("Cart cleared"));
    }

}