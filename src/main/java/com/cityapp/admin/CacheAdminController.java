package com.cityapp.admin;

import com.cityapp.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin endpoints for cache inspection and management.
 * Useful for debugging stale cache issues in production.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/cache")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CacheAdminController {

    private final CacheManager          cacheManager;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Show all cache names and their approximate sizes.
     * Useful for monitoring memory usage per cache.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        cacheManager.getCacheNames().forEach(cacheName -> {
            // Count keys in Redis matching this cache
            long keyCount = 0;
            try {
                Set<String> keys = redisTemplate.keys(cacheName + ":*");
                keyCount = keys != null ? keys.size() : 0;
            } catch (Exception e) {
                keyCount = -1; // Redis unavailable
            }
            stats.put(cacheName, Map.of("keys", keyCount));
        });

        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    /**
     * Evict a specific cache entry by key.
     * Use when a seller reports seeing stale store data.
     * POST /api/v1/admin/cache/stores/evict?key=10
     */
    @PostMapping("/{cacheName}/evict")
    public ResponseEntity<ApiResponse<Void>> evictEntry(
            @PathVariable String cacheName,
            @RequestParam String key) {

        var cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("CACHE_NOT_FOUND",
                            "Cache '" + cacheName + "' does not exist"));
        }

        cache.evict(key);
        log.info("Cache evicted by admin: cache={} key={}", cacheName, key);
        return ResponseEntity.ok(ApiResponse.ok("Cache entry evicted"));
    }

    /**
     * Clear an entire cache.
     * Nuclear option: use only when you know stale data is widespread.
     * POST /api/v1/admin/cache/stores/clear
     */
    @PostMapping("/{cacheName}/clear")
    public ResponseEntity<ApiResponse<Void>> clearCache(
            @PathVariable String cacheName) {

        var cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("CACHE_NOT_FOUND",
                            "Cache '" + cacheName + "' does not exist"));
        }

        cache.clear();
        log.warn("Cache CLEARED by admin: cache={}", cacheName);
        return ResponseEntity.ok(ApiResponse.ok("Cache cleared"));
    }
}