package com.cityapp.config;

import java.time.Duration;
import java.util.function.Supplier;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;

@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * Only open the Redis-backed rate-limit connection when rate limiting is enabled.
     * A test profile can therefore set cityapp.ratelimit.enabled=false without
     * requiring a running Redis instance during ApplicationContext startup.
     */
    @Bean
    @ConditionalOnProperty(
            name = "cityapp.ratelimit.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ProxyManager<byte[]> bucketProxyManager(
            @Value("${REDIS_HOST:localhost}") String redisHost,
            @Value("${REDIS_PORT:6379}") int redisPort) {

        String redisUrl = "redis://" + redisHost + ":" + redisPort;
        log.info("Creating Redis client for {}", redisUrl);

        RedisClient redisClient = RedisClient.create(redisUrl);

        log.info("Connecting to Redis at {}", redisUrl);
        redisClient.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build())
                .build());

        StatefulRedisConnection<byte[], byte[]> connection =
                redisClient.connect(ByteArrayCodec.INSTANCE);

        log.info("Redis connection established");

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
                .addLimit(Bandwidth.builder().capacity(capacity)
                        .refillGreedy(refillTokens, Duration.ofSeconds(refillSeconds)).build())
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> registerBucketConfig(
            @Value("${cityapp.ratelimit.register.capacity:3}") int capacity,
            @Value("${cityapp.ratelimit.register.refill-tokens:3}") int refillTokens,
            @Value("${cityapp.ratelimit.register.refill-seconds:3600}") int refillSeconds) {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity)
                        .refillGreedy(refillTokens, Duration.ofSeconds(refillSeconds)).build())
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> otpBucketConfig(
            @Value("${cityapp.ratelimit.otp.capacity:3}") int capacity,
            @Value("${cityapp.ratelimit.otp.refill-tokens:3}") int refillTokens,
            @Value("${cityapp.ratelimit.otp.refill-seconds:3600}") int refillSeconds) {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity)
                        .refillGreedy(refillTokens, Duration.ofSeconds(refillSeconds)).build())
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> generalApiBucketConfig(
            @Value("${cityapp.ratelimit.api.capacity:100}") int capacity,
            @Value("${cityapp.ratelimit.api.refill-tokens:100}") int refillTokens,
            @Value("${cityapp.ratelimit.api.refill-seconds:60}") int refillSeconds) {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity)
                        .refillGreedy(refillTokens, Duration.ofSeconds(refillSeconds)).build())
                .build();
    }
}
