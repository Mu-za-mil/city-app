package com.cityapp.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Configuration — the most bug-prone configuration in the application.
 *
 * THE CLASSCASTEXCEPTION BUG (encountered in production):
 *
 * SYMPTOM:
 *   First request to GET /cart → works (cart is added to Redis)
 *   Second request to GET /cart → ClassCastException:
 *   "LinkedHashMap cannot be cast to Cart"
 *
 * ROOT CAUSE:
 *   Without activateDefaultTyping:
 *   Jackson serialises Cart to JSON WITHOUT class type information:
 *   { "userId": 42, "storeId": 10, "items": [...] }
 *
 *   When deserialising this JSON:
 *   Jackson has no idea what Java class to create.
 *   It defaults to LinkedHashMap (the generic JSON object representation).
 *
 *   Code then does: Cart cart = (Cart) redisTemplate.opsForValue().get(key)
 *   Actual type: LinkedHashMap
 *   Cast target: Cart
 *   → ClassCastException
 *
 * THE FIX:
 *   activateDefaultTyping stores the class name IN the JSON:
 *   { "@class": "com.cityapp.cart.model.Cart",
 *     "userId": 42, "storeId": 10, "items": [...] }
 *
 *   On deserialisation: Jackson reads @class, creates the correct Cart instance.
 *   No ClassCastException. Ever.
 *
 * WHY NOT USE JdkSerializationRedisSerializer:
 *   Java's built-in serialisation stores binary data (not JSON).
 *   You cannot read/inspect the stored data with redis-cli.
 *   Debugging: redis-cli GET "cart:42:10" → garbled binary text. Useless.
 *   Maintenance: Java serialisation is brittle — any field rename breaks it.
 *   Performance: JSON is faster and smaller than Java serialisation for most data.
 *   ALWAYS use JSON serialisation for Redis. Never Java binary serialisation.
 *
 * THE CACHE STAMPEDE FIX (TTL Jitter):
 *   Without jitter: 1000 users cache the same store with TTL=5min.
 *   All entries expire simultaneously.
 *   All 1000 next requests: cache miss → 1000 concurrent DB queries.
 *   Connection pool exhausted. DB overloaded.
 *
 *   With jitter: TTL = base + random(0, 20% of base)
 *   Store cache: TTL = 300 + random(0, 60) seconds
 *   Expiries are spread over 60 seconds.
 *   At most 17 cache misses per second → easily absorbed.
 */
@Configuration
public class RedisConfig {

    /**
     * The ObjectMapper configured for Redis serialisation.
     * MUST have activateDefaultTyping to prevent ClassCastException.
     * MUST have JavaTimeModule to serialise Instant (used in Cart timestamps).
     *
     * WHY SEPARATE OBJECTMAPPER FROM SPRING'S DEFAULT:
     *   Spring Boot auto-configures an ObjectMapper for HTTP responses.
     *   That mapper: does NOT include @class in JSON (correct for API responses).
     *   This Redis mapper: DOES include @class (required for Redis deserialisation).
     *   Using the same mapper for both: @class would appear in API responses.
     *   Ugly, exposes internal class names, security concern.
     *   Separate mappers: each configured correctly for its purpose.
     */
    @Bean(name = "redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Support for Java 8 date/time types (Instant, LocalTime, etc.)
        mapper.registerModule(new JavaTimeModule());
        // Don't serialise Instant as an array [seconds, nanos] — use ISO string
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // THE CRITICAL FIX: embed @class in every serialised object
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType(Object.class)
                        // Allow all subtypes of Object — our own classes + JDK classes.
                        // In production, consider restricting to com.cityapp.* for security.
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                // NON_FINAL: add @class only for non-final classes.
                // Final classes (String, Integer): not polymorphic, no @class needed.
                JsonTypeInfo.As.PROPERTY
                // AS.PROPERTY: embed @class as a JSON property name ("@class").
                // Alternative: AS.WRAPPER_ARRAY embeds as ["com.cityapp.Cart", {...}].
                // AS.PROPERTY is more readable and standard.
        );

        return mapper;
    }

    /**
     * Primary RedisTemplate for complex objects (Cart, etc.).
     * Keys: String serialisation (human-readable Redis keys).
     * Values: JSON with type info (@class embedded).
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serialiser: String (so keys are readable in redis-cli)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serialiser: JSON with @class type info
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * RedisCacheManager for @Cacheable/@CacheEvict annotations.
     *
     * Per-cache TTL configuration:
     *   stores:      5 minutes  — store profiles change rarely
     *   storeStatus: 30 seconds — open/closed status must be fresh
     *   categories:  1 hour     — category list almost never changes
     *
     * WHY DIFFERENT TTLs PER CACHE:
     *   One TTL for everything: too long → stale data.
     *   Too short → cache is useless (misses too often).
     *   Per-cache TTL: each cache tuned to the data's change frequency.
     *   storeStatus changes when seller toggles open/close: needs short TTL.
     *   Category list: 16 items, admin changes maybe monthly: needs long TTL.
     *
     * WHY TTL JITTER:
     *   All entries in a cache with the same TTL expire simultaneously.
     *   Without jitter: thundering herd problem (1000 cache misses at once).
     *   With jitter: add random(0, 20%) to TTL. Expires spread over time.
     *   Max 17 misses/second instead of 1000 simultaneously.
     */
    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        // Default config: 5 minutes + jitter
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(withJitter(Duration.ofMinutes(5)))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jsonSerializer))
                .disableCachingNullValues();
        // disableCachingNullValues: don't cache null results.
        // If a store is not found (null): don't cache null.
        // Next request: re-queries DB → may find the store if it was just created.

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        cacheConfigs.put("stores",
                defaultConfig.entryTtl(withJitter(Duration.ofMinutes(5))));

        cacheConfigs.put("storeStatus",
                defaultConfig.entryTtl(withJitter(Duration.ofSeconds(30))));
        // 30 seconds: seller closes store → buyers see it closed within 30s.
        // 5 minutes would mean buyers could checkout for 5 minutes after close.

        cacheConfigs.put("categories",
                defaultConfig.entryTtl(withJitter(Duration.ofHours(1))));
        // 1 hour: categories are admin-managed, rarely change.

        cacheConfigs.put("products",
                defaultConfig.entryTtl(withJitter(Duration.ofMinutes(10))));

        return RedisCacheManager.builder(
                        RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory))
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    /**
     * Adds random jitter to a TTL to prevent cache stampede.
     *
     * Example with 5-minute base:
     *   Duration.ofMinutes(5) + random(0 to 60 seconds)
     *   = 300 to 360 seconds
     *
     * All entries that were populated simultaneously will expire
     * at different times within a 60-second window.
     * Maximum 1 cache miss per second instead of 1000 simultaneously.
     */
    private Duration withJitter(Duration base) {
        long jitterMs = (long) (base.toMillis() * 0.2 * Math.random());
        return base.plusMillis(jitterMs);
    }
}
