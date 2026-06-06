package com.cityapp.store.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.common.response.PageResponse;
import com.cityapp.store.dto.CreateStoreRequest;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.service.StoreService;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    // ── Public endpoints (no auth needed) ────────────────────────────────────

    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<List<StoreResponse>>> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5.0") double radiusKm) {
        return ResponseEntity.ok(
                ApiResponse.ok(storeService.findNearby(lat, lng, radiusKm)));
    }

    @GetMapping("/trending")
    public ResponseEntity<ApiResponse<List<StoreResponse>>> trending(
            @RequestParam(defaultValue = "7")  int days,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(
                ApiResponse.ok(storeService.findTrending(days, limit)));
    }

    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> getStore(
            @PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok(storeService.getStore(storeId)));
    }

    // ── Seller endpoints ──────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ApiResponse<StoreResponse>> createStore(
            @AuthenticationPrincipal User seller,
            @Valid @RequestBody CreateStoreRequest req) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        storeService.createStore(seller, req),
                        "Store created and submitted for approval"));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ApiResponse<PageResponse<StoreResponse>>> myStores(
            @AuthenticationPrincipal User seller,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                storeService.getMyStores(seller,
                        PageRequest.of(page, size,
                                Sort.by("createdAt").descending()))));
    }

    @PatchMapping("/{storeId}")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ApiResponse<StoreResponse>> updateStore(
            @AuthenticationPrincipal User seller,
            @PathVariable Long storeId,
            @Valid @RequestBody CreateStoreRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                storeService.updateStore(storeId, seller.getId(), req)));
    }

    @PostMapping("/{storeId}/toggle-open")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ApiResponse<StoreResponse>> toggleOpen(
            @AuthenticationPrincipal User seller,
            @PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok(
                storeService.toggleOpenStatus(storeId, seller.getId()),
                "Store status toggled"));
    }

}
