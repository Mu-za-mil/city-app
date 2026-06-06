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

    // ── Public endpoints ──────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> search(
            @RequestParam(required = false) Long       storeId,
            @RequestParam(required = false) String     q,
            @RequestParam(required = false) Long       categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0")    int  page,
            @RequestParam(defaultValue = "20")   int  size,
            @RequestParam(defaultValue = "name") String sort) {

        // Build sort from query param
        Sort sortObj = switch (sort) {
            case "price_asc"    -> Sort.by("price").ascending();
            case "price_desc"   -> Sort.by("price").descending();
            case "rating"       -> Sort.by("avgRating").descending();
            case "newest"       -> Sort.by("createdAt").descending();
            default             -> Sort.by("name").ascending();
        };
        Pageable pageable = PageRequest.of(page, size, sortObj);

        return ResponseEntity.ok(ApiResponse.ok(
                productService.searchProducts(
                        storeId, q, categoryId, minPrice, maxPrice, pageable)));
    }

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
