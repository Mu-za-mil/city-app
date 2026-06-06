package com.cityapp.product.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.common.response.PageResponse;
import com.cityapp.product.dto.CreateProductRequest;
import com.cityapp.product.dto.ProductResponse;
import com.cityapp.product.service.ProductService;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/stores/{storeId}/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // ── Seller endpoints ──────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @PathVariable Long storeId,
            @AuthenticationPrincipal User seller,
            @Valid @RequestBody CreateProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        productService.createProduct(storeId, seller, req)));
    }
}
