package com.cityapp.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.metrics.cache.CacheMeterBinderProvider;
import org.springframework.boot.actuate.metrics.cache.CacheMetricsRegistrar;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;

/**
 * Exports cache metrics to Prometheus.
 * In Grafana: visualise cache hit rates per cache name.
 *
 * Metrics available after this config:
 *   cache_gets_total{cache="stores",result="hit"}
 *   cache_gets_total{cache="stores",result="miss"}
 *   cache_puts_total{cache="stores"}
 *   cache_evictions_total{cache="stores"}
 *
 * Hit rate formula:
 *   rate(cache_gets_total{result="hit"}[5m])
 *   ÷ rate(cache_gets_total[5m])
 *
 * HEALTHY TARGET: > 90% hit rate on "stores" and "categories" caches.
 * LOW HIT RATE: either TTL is too short or data changes too frequently.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CacheMetricsConfig {

    private final CacheManager  cacheManager;
    private final MeterRegistry meterRegistry;

    @Bean
    public CacheMetricsRegistrar cacheMetricsRegistrar() {
        return new CacheMetricsRegistrar(
                meterRegistry,
                (Collection<CacheMeterBinderProvider<?>>) cacheManager
        );
    }
}
