package com.cityapp.config;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import lombok.extern.slf4j.Slf4j;

/**
 * Rate limiting configuration using Bucket4j with Redis backend.
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    @Bean
    public ProxyManager<byte[]> bucketProxyManager() {

        System.out.println("CREATING LETTUCE CLIENT");

        RedisClient redisClient =
                RedisClient.create("redis://127.0.0.1:6379");

        System.out.println("CONNECTING");

        StatefulRedisConnection<byte[], byte[]> connection =
                redisClient.connect(ByteArrayCodec.INSTANCE);

        System.out.println("CONNECTED");

        return LettuceBasedProxyManager
                .builderFor(connection)
                .build();
    }

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
