package com.cityapp.common.constants;

/**
 * Application-wide constants.
 *
 * WHY A CONSTANTS CLASS:
 *   Without it: topic names are strings scattered across 15 service classes.
 *   One typo: "order.created" vs "order_created" → event never consumed.
 *   Bug is silent: producer sends, consumer never receives, no error anywhere.
 *
 *   With constants:
 *   kafkaTemplate.send(AppConstants.TOPIC_ORDER_CREATED, ...)
 *   consumer subscribes with AppConstants.TOPIC_ORDER_CREATED
 *   → Impossible for producer and consumer to use different strings.
 *   → Refactoring: rename constant → compiler finds every usage.
 *
 * WHY interface NOT class:
 *   interface: constants are implicitly public static final.
 *   No instantiation possible (you can't do: new AppConstants()).
 *   Less boilerplate than a class with private constructor.
 *   Alternative: final class with private constructor is also valid.
 *   Both work. Interface is slightly more concise.
 */
public interface AppConstants {

    // ── Kafka Topics ──────────────────────────────────────────────────────────
    // Naming convention: domain.event-verb (present tense action)
    // Why dot-notation: Kafka topics are case-sensitive.
    //   "order.created" and "Order.Created" are different topics.
    //   lowercase.dot.separated is the industry standard.

    String TOPIC_USER_REGISTERED      = "user.registered";
    String TOPIC_ORDER_CREATED        = "order.created";
    String TOPIC_ORDER_STATUS_CHANGED = "order.status.changed";
    String TOPIC_DEDUCT_STOCK         = "deduct.stock";
    String TOPIC_STOCK_DEDUCTED       = "stock.deducted";
    String TOPIC_INVENTORY_LOW        = "inventory.low";
    String TOPIC_DELIVERY_ASSIGNED    = "delivery.assigned";
    String TOPIC_STORE_ANNOUNCEMENT   = "store.announcement";
    String TOPIC_REVIEW_POSTED        = "review.posted";

    // ── Redis Key Prefixes ────────────────────────────────────────────────────
    // Naming convention: domain:entity:identifier
    // Colon-separated hierarchy: tools like RedisInsight group by prefix.
    // Easy to see: "all cart keys" = search "cart:*"

    String REDIS_CART_PREFIX          = "cart:";
    // Full key: cart:{userId}:{storeId} → cart:42:10

    String REDIS_OTP_PREFIX           = "otp:";
    // Full key: otp:{phone} → otp:9876543210

    String REDIS_OTP_THROTTLE_PREFIX  = "otp:throttle:";
    // Full key: otp:throttle:{phone} → otp:throttle:9876543210

    String REDIS_JWT_BLACKLIST_PREFIX = "jwt:blacklist:";
    // Full key: jwt:blacklist:{token}

    String REDIS_DELIVERY_LOCATION    = "delivery:location:";
    // Full key: delivery:location:{orderId} → delivery:location:1001

    String REDIS_DELIVERY_DB_WRITE    = "delivery:dbwrite:";
    // Full key: delivery:dbwrite:{partnerId} → throttle DB writes

    String REDIS_LOW_STOCK_DEDUP      = "lowstock:";
    // Full key: lowstock:{productId} → prevent duplicate alerts

    String REDIS_SAGA_PROCESSED       = "saga:processed:";
    // Full key: saga:processed:{orderId} → idempotent saga handler

    String REDIS_NOTIF_DEDUP_PREFIX   = "notif:dedup:";
    // Full key: notif:dedup:{orderId}:{status}

    String REDIS_WS_RELAY_PREFIX      = "ws:notifications:";
    // Full key: ws:notifications:{userId}

    // ── Cache Names ───────────────────────────────────────────────────────────
    // WHY CONSTANTS NOT STRING LITERALS:
    //   @Cacheable("stores") ← string literal: typo "storez" = wrong cache used
    //   @Cacheable(AppConstants.CACHE_STORES) ← compile error on typo
    //   Constants: refactor-safe, IDE autocomplete, consistent naming.

    String CACHE_STORES       = "stores";
    String CACHE_STORE_STATUS = "storeStatus";
    String CACHE_PRODUCTS     = "products";
    String CACHE_CATEGORIES   = "categories";
    String CACHE_TRENDING     = "trending";

    // ── Security ──────────────────────────────────────────────────────────────
    int    OTP_LENGTH                 = 6;
    int    OTP_EXPIRY_SECONDS         = 300;      // 5 minutes
    int    OTP_THROTTLE_SECONDS       = 3600;     // 1 hour
    int    OTP_MAX_PER_HOUR           = 5;

    int    MAX_SESSIONS_PER_USER      = 5;
    int    LOW_STOCK_DEDUP_HOURS      = 1;

    // ── Business Rules ────────────────────────────────────────────────────────
    double MAX_NEARBY_RADIUS_KM       = 50.0;
    int    DEFAULT_NEARBY_LIMIT       = 20;
    int    SAGA_TIMEOUT_MINUTES       = 2;
    int    DELIVERY_DB_WRITE_SECONDS  = 30;
}
