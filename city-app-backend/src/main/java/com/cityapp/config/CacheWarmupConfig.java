package com.cityapp.config;

import com.cityapp.category.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.cache.warmup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CacheWarmupConfig {

    private final CategoryService categoryService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCriticalCaches() {
        log.info("Warming critical caches...");

        try {
            categoryService.getActiveCategories();
            log.info("Categories cache warmed");
        } catch (Exception e) {
            log.warn("Cache warming failed (non-critical): {}", e.getMessage());
        }
    }
}
