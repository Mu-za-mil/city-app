package com.cityapp.category.controller;

import com.cityapp.category.entity.Category;
import com.cityapp.category.service.CategoryService;
import com.cityapp.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Category>>> getCategories() {
        return ResponseEntity.ok(
                ApiResponse.ok(categoryService.getActiveCategories()));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Category>> createCategory(
            @RequestParam String name,
            @RequestParam String slug,
            @RequestParam(required = false) String iconUrl) {
        return ResponseEntity.ok(ApiResponse.ok(
                categoryService.createCategory(name, slug, iconUrl)));
    }
}