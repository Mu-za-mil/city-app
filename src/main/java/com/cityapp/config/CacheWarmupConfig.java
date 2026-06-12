package com.cityapp.config;

import com.cityapp.category.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pre-loads critical cache entries when the application starts.
 *
 * WHY CACHE WARMING:
 *   Without it: first request after deployment hits the DB (cold cache).
 *   If traffic spikes immediately after deployment: cold start.
 *   Hundreds of concurrent requests, all cache misses, all hit the DB.
 *   DB connection pool: exhausted. Slow startup under load.
 *
 *   With warming:
 *   Critical data cached before first request arrives.
 *   First user request: cache hit. Sub-millisecond response.
 *   No cold start. No thundering herd on deployment.
 *
 * WHICH DATA TO WARM:
 *   Only high-value, always-requested data:
 *   - Categories: queried on EVERY page load.
 *   - Trending stores: main page load.
 *   NOT individual stores: too many. The cache will warm naturally via demand.
 *
 * @EventListener(ApplicationReadyEvent):
 *   Fires AFTER all beans are initialised and the server is ready for traffic.
 *   Contrast with @PostConstruct: runs during bean initialisation.
 *   At @PostConstruct time: Redis may not be connected yet.
 *   At ApplicationReadyEvent: all infrastructure is ready.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmupConfig {

    private final CategoryService categoryService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCriticalCaches() {
        log.info("Warming critical caches...");

        try {
            // Pre-load categories (queried on every page)
            categoryService.getActiveCategories();
            log.info("✅ Categories cache warmed");
        } catch (Exception e) {
            // Cache warming failure: non-critical. App still works.
            // Log and continue. First requests will populate cache naturally.
            log.warn("Cache warming failed (non-critical): {}", e.getMessage());
        }
    }
}
