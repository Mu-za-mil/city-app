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

    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.getCart(buyer.getId(), storeId)));
    }

    @PutMapping("/{storeId}/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId,
            @PathVariable Long productId,
            @RequestParam int quantity) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.updateQuantity(
                        buyer.getId(), storeId, productId, quantity)));
    }

    @DeleteMapping("/{storeId}/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.removeItem(buyer.getId(), storeId, productId)));
    }

    @DeleteMapping("/{storeId}")
    public ResponseEntity<ApiResponse<Void>> clearCart(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId) {
        cartService.clearCart(buyer.getId(), storeId);
        return ResponseEntity.ok(ApiResponse.ok("Cart cleared"));
    }

    // ── Multi-store cart overview ─────────────────────────────────────────────

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<CartResponse>>> getAllCarts(
            @AuthenticationPrincipal User buyer) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.getAllCarts(buyer.getId())));
    }

    // ── Checkout ──────────────────────────────────────────────────────────────

    @PostMapping("/{storeId}/checkout")
    public ResponseEntity<ApiResponse<?>> checkout(
            @AuthenticationPrincipal User buyer,
            @PathVariable Long storeId,
            @Valid @RequestBody CartCheckoutRequest req) {
        req.setStoreId(storeId);
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.checkout(buyer, req)));
    }
}