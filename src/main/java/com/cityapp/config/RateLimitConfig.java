package com.cityapp.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Rate limiting configuration using Bucket4j with Redis backend.
 *
 * TOKEN BUCKET ALGORITHM:
 *   Imagine a bucket that holds tokens.
 *   Each request consumes 1 token.
 *   Tokens are refilled at a fixed rate.
 *   When the bucket is empty: request is rejected (429).
 *
 *   Example: login bucket { capacity: 5, refill: 5 per 15 minutes }
 *   Bucket starts full (5 tokens).
 *   5 login attempts: each takes 1 token. Bucket empty.
 *   6th attempt: bucket empty → 429 Too Many Requests.
 *   After 15 minutes: 5 tokens refilled. Can try again.
 *
 * WHY REDIS BACKEND (not in-memory):
 *   In-memory bucket: each server instance has its own bucket.
 *   2 server instances: attacker can make 5 attempts on instance A,
 *   then 5 more on instance B. Total: 10 attempts (double the limit).
 *
 *   Redis backend: one bucket per key across ALL instances.
 *   Attacker makes 5 attempts: redis bucket depleted.
 *   Instance A, B, or C: all see the same empty bucket.
 *   Rate limit enforced globally across all instances.
 *
 * WHY LETTUCE (not Jedis) FOR BUCKET4J:
 *   Lettuce: reactive, thread-safe, connection pooling.
 *   Bucket4j Redis backend requires atomic CAS (Compare-And-Swap) operations.
 *   Lettuce: natively supports atomic operations via Lua scripts.
 *   Spring Boot auto-configures Lettuce with spring-data-redis.
 *   We reuse the SAME Lettuce connection that Spring Data Redis uses.
 *
 * WHY REUSE LETTUCE CONNECTION:
 *   Spring Data Redis already manages a connection pool.
 *   Creating a second Lettuce client would double connection overhead.
 *   Reuse: one connection pool, two uses (caching + rate limiting).
 *   This avoids: "too many connections" errors under load.
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    @Value("${cityapp.ratelimit.enabled:true}")
    private boolean rateLimitEnabled;

    /**
     * Reuse Spring's existing Lettuce connection factory.
     * DO NOT create a new RedisClient — this would double connections.
     */
    @Bean
    public ProxyManager<byte[]> bucketProxyManager(
            LettuceConnectionFactory lettuceConnectionFactory) {

        if (!rateLimitEnabled) {
            log.info("Rate limiting disabled");
            return null;
        }

        // Extract the native Lettuce client from Spring's factory
        // This reuses the existing connection pool — no second client created
        io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> connection =
                ((io.lettuce.core.RedisClient) lettuceConnectionFactory
                        .getNativeClient())
                        .connect(ByteArrayCodec.INSTANCE);

        return LettuceBasedProxyManager.builderFor(connection)
                .build();
    }

    // ── Bucket Configuration Factories ────────────────────────────────────────

    /**
     * Login rate limit: 5 attempts per 15 minutes per IP+email combination.
     */
    @Bean
    public Supplier<BucketConfiguration> loginBucketConfig(
            @Value("${cityapp.ratelimit.login.capacity:5}") int capacity,
            @Value("${cityapp.ratelimit.login.refill-tokens:5}") int refillTokens,
            @Value("${cityapp.ratelimit.login.refill-seconds:900}") int refillSeconds) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens,
                                Duration.ofSeconds(refillSeconds))
                        // refillGreedy: refill tokens as fast as possible
                        // up to the capacity.
                        // Alternative: refillIntervally (all at once at interval end).
                        // Greedy is fairer for users.
                        .build())
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> registerBucketConfig(
            @Value("${cityapp.ratelimit.register.capacity:3}") int capacity,
            @Value("${cityapp.ratelimit.register.refill-tokens:3}") int refillTokens,
            @Value("${cityapp.ratelimit.register.refill-seconds:3600}") int refillSeconds) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens,
                                Duration.ofSeconds(refillSeconds))
                        .build())
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> otpBucketConfig(
            @Value("${cityapp.ratelimit.otp.capacity:3}") int capacity,
            @Value("${cityapp.ratelimit.otp.refill-tokens:3}") int refillTokens,
            @Value("${cityapp.ratelimit.otp.refill-seconds:3600}") int refillSeconds) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens,
                                Duration.ofSeconds(refillSeconds))
                        .build())
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> generalApiBucketConfig(
            @Value("${cityapp.ratelimit.api.capacity:100}") int capacity,
            @Value("${cityapp.ratelimit.api.refill-tokens:100}") int refillTokens,
            @Value("${cityapp.ratelimit.api.refill-seconds:60}") int refillSeconds) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens,
                                Duration.ofSeconds(refillSeconds))
                        .build())
                .build();
    }
}
