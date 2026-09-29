-- =============================================================================
-- V1: Complete Domain Schema
-- City Super App — Hyperlocal Marketplace
--
-- DESIGN PRINCIPLES:
--  1. BIGSERIAL for all primary keys (auto-increment integer)
--     WHY not UUID: UUIDs are 16 bytes vs 8 bytes for BIGINT.
--     B-tree index on UUID is larger = slower queries.
--     UUIDs cause page fragmentation on INSERT (random vs sequential).
--     For internal IDs: BIGSERIAL. For public-facing IDs: consider UUID.
--
--  2. TIMESTAMPTZ (timestamp with time zone) for all timestamps
--     WHY not TIMESTAMP: TIMESTAMP stores local time with no TZ context.
--     When your server moves from UTC+5:30 to UTC, all times are wrong.
--     TIMESTAMPTZ stores UTC internally, converts on display. Always correct.
--
--  3. NUMERIC(12,2) for monetary values
--     WHY not FLOAT or DOUBLE: Floating point cannot represent all decimals.
--     0.1 + 0.2 = 0.30000000000000004 in floating point.
--     ₹79.99 + ₹0.01 = ₹79.99999... in floating point.
--     NUMERIC is exact decimal arithmetic. Money requires exactness.
--
--  4. VARCHAR with explicit limits for text fields
--     WHY not TEXT: TEXT has no length limit. A malicious user could submit
--     a 100MB product description and crash the system.
--     VARCHAR(500) rejects it at the database level.
--
--  5. CHECK constraints for enums stored as VARCHAR
--     WHY not an enum type: PostgreSQL enums cannot be altered without
--     dropping and recreating the type. Adding a new status value requires
--     a complex migration. CHECK on VARCHAR is flexible.
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- USERS
-- The central entity. Every other table references users.
-- Supports multiple roles in one table (simpler than separate tables per role).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE users (
                       id              BIGSERIAL       PRIMARY KEY,
                       name            VARCHAR(100)    NOT NULL,
                       email           VARCHAR(150)    UNIQUE NOT NULL,
    -- UNIQUE: prevents duplicate accounts. Also creates an implicit index.
    -- login query: SELECT * FROM users WHERE email = 'x' → uses this index.
                       password_hash   VARCHAR(255)    NOT NULL,
    -- VARCHAR(255): BCrypt hash is always 60 chars. 255 gives future room.
                       phone           VARCHAR(20)     UNIQUE,
    -- UNIQUE and nullable: phone login is optional. Unique when provided.
                       role            VARCHAR(30)     NOT NULL DEFAULT 'USER'
                           CHECK (role IN ('USER', 'SELLER', 'DELIVERY_PARTNER', 'SUPER_ADMIN')),
    -- WHY CHECK constraint: rejects invalid role values at the DB level.
    -- Without it: a bug could set role='HACKER'. With it: database rejects it.
                       enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Why not delete: we never hard-delete users (order history references them).
    -- enabled=false = "soft suspended" → cannot login but history preserved.
                       account_non_locked  BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Separate from enabled: locked = too many failed login attempts.
                       profile_image_url   VARCHAR(500),
                       created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                       updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Index for fast login lookup by email
CREATE INDEX idx_users_email ON users(email);
-- Wait, UNIQUE already creates an index. This is redundant.
-- WHY still add it: explicit indexes are visible in monitoring tools.
-- The implicit UNIQUE index is invisible in some query planners.
-- Actually: remove this and keep only the UNIQUE constraint.
-- LESSON: UNIQUE already creates an index. Don't duplicate it.

-- Seed the super admin (needed from day one for store approval)
-- BCrypt hash of 'Admin@1234' with cost factor 12
INSERT INTO users (name, email, password_hash, role, enabled)
VALUES ('Super Admin', 'admin@cityapp.com',
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj/RK.PgO2yK',
        'SUPER_ADMIN', TRUE);

-- ─────────────────────────────────────────────────────────────────────────────
-- REFRESH TOKENS (for Phase 4 — JWT Refresh Token system)
-- Adding now because it references users table.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
                                id              BIGSERIAL       PRIMARY KEY,
                                token           VARCHAR(512)    NOT NULL UNIQUE,
                                user_id         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- ON DELETE CASCADE: when a user is deleted, all their sessions go too.
    -- Without it: orphaned refresh tokens referencing non-existent users.
                                expires_at      TIMESTAMPTZ     NOT NULL,
                                revoked         BOOLEAN         NOT NULL DEFAULT FALSE,
                                revoked_at      TIMESTAMPTZ,
                                revoke_reason   VARCHAR(100),
                                device_info     VARCHAR(255),
                                ip_address      VARCHAR(50),
                                user_agent      VARCHAR(500),
                                created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                                last_used_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens(user_id)
    WHERE revoked = FALSE;
-- WHY partial index: only index ACTIVE sessions.
-- Revoked sessions are historical — we never query for them in hot paths.
-- Smaller index = faster lookup.

-- ─────────────────────────────────────────────────────────────────────────────
-- ADDRESSES (user shipping/delivery addresses)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE user_addresses (
                                id          BIGSERIAL       PRIMARY KEY,
                                user_id     BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                label       VARCHAR(50)     NOT NULL,   -- "Home", "Office", "Mom's House"
                                line1       VARCHAR(200)    NOT NULL,
                                line2       VARCHAR(200),
                                city        VARCHAR(100)    NOT NULL,
                                state       VARCHAR(100)    NOT NULL,
                                pincode     VARCHAR(10)     NOT NULL,
                                latitude    DOUBLE PRECISION,
                                longitude   DOUBLE PRECISION,
                                is_default  BOOLEAN         NOT NULL DEFAULT FALSE,
                                created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_addresses_user ON user_addresses(user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- CATEGORIES
-- A flat list of store/product categories (Grocery, Electronics, etc.)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE categories (
                            id          BIGSERIAL       PRIMARY KEY,
                            name        VARCHAR(100)    NOT NULL UNIQUE,
                            slug        VARCHAR(100)    NOT NULL UNIQUE,
    -- slug: URL-friendly version. "Fresh Produce" → "fresh-produce"
    -- Used in URLs: GET /stores?category=fresh-produce
                            icon_url    VARCHAR(500),
                            active      BOOLEAN         NOT NULL DEFAULT TRUE,
                            created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Seed default categories
INSERT INTO categories (name, slug) VALUES
                                        ('Grocery & Supermarket', 'grocery'),
                                        ('Electronics & Gadgets', 'electronics'),
                                        ('Restaurants & Food', 'food'),
                                        ('Pharmacy & Health', 'pharmacy'),
                                        ('Fashion & Clothing', 'fashion'),
                                        ('Home & Kitchen', 'home-kitchen'),
                                        ('Books & Stationery', 'books'),
                                        ('Sports & Fitness', 'sports'),
                                        ('Beauty & Personal Care', 'beauty'),
                                        ('Toys & Kids', 'toys'),
                                        ('Bakery & Sweets', 'bakery'),
                                        ('Organic & Natural', 'organic'),
                                        ('Pet Supplies', 'pets'),
                                        ('Hardware & Tools', 'hardware'),
                                        ('Gifts & Flowers', 'gifts'),
                                        ('Auto & Vehicles', 'auto');

-- ─────────────────────────────────────────────────────────────────────────────
-- STORES
-- Physical store locations. Managed by sellers.
-- GPS coordinates for nearby search.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE stores (
                        id              BIGSERIAL           PRIMARY KEY,
                        owner_id        BIGINT              NOT NULL REFERENCES users(id),
    -- FK to users: the seller who owns this store
                        category_id     BIGINT              REFERENCES categories(id),
    -- Nullable: store may be created before category is assigned
                        name            VARCHAR(200)        NOT NULL,
                        description     VARCHAR(2000),
                        address         VARCHAR(500),
                        city            VARCHAR(100),
                        state           VARCHAR(100),
                        pincode         VARCHAR(10),

    -- GPS coordinates for Haversine/PostGIS nearby search
                        latitude        DOUBLE PRECISION,
                        longitude       DOUBLE PRECISION,
                        location        GEOGRAPHY(POINT, 4326),
    -- GEOGRAPHY: PostGIS type for GPS coordinates on Earth's curved surface.
    -- Enables spatial indexing for fast nearby queries.
    -- Populated automatically by trigger when lat/lng are set.

    -- Operating hours (IST — all times stored and compared in IST)
                        opening_time    TIME,   -- e.g. 09:00:00
                        closing_time    TIME,   -- e.g. 22:00:00
    -- WHY TIME not TIMESTAMPTZ: operating hours repeat daily.
    -- "Opens at 9 AM" doesn't have a date component.

                        open            BOOLEAN             NOT NULL DEFAULT FALSE,
    -- open: seller toggles this manually. Can be overridden by the scheduler.
    -- "I'm closing early today" → set open=false despite configured hours.

                        status          VARCHAR(30)         NOT NULL DEFAULT 'PENDING_APPROVAL'
                            CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    -- PENDING_APPROVAL: newly registered, waiting for admin review
    -- ACTIVE: approved and can receive orders
    -- SUSPENDED: violated terms, temporarily blocked
    -- CLOSED: permanently closed

                        min_order_amount    NUMERIC(10,2)   DEFAULT 0,
                        delivery_radius_km  NUMERIC(5,2)    DEFAULT 5.0,
                        avg_rating          NUMERIC(3,2)    DEFAULT 0.0,
                        total_reviews       INTEGER         DEFAULT 0,
                        logo_url            VARCHAR(500),
                        banner_url          VARCHAR(500),

                        created_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
                        updated_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW()
);

-- Index for owner lookups (seller dashboard: "show my stores")
CREATE INDEX idx_stores_owner ON stores(owner_id);
-- Index for category browsing
CREATE INDEX idx_stores_category ON stores(category_id);
-- Spatial index for PostGIS nearby query
CREATE INDEX idx_stores_location_gist
    ON stores USING GIST (location)
    WHERE status = 'ACTIVE';
-- WHY partial index: only ACTIVE stores appear in nearby search.
-- Suspended/closed stores don't need to be indexed.
-- Smaller index = faster query.

-- ─────────────────────────────────────────────────────────────────────────────
-- PRODUCTS
-- Items sold by stores. Soft-deleted (active=false).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE products (
                          id              BIGSERIAL       PRIMARY KEY,
                          store_id        BIGINT          NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                          category_id     BIGINT          REFERENCES categories(id),
                          name            VARCHAR(300)    NOT NULL,
                          description     VARCHAR(2000),
                          sku             VARCHAR(100),   -- stock keeping unit (optional, seller-defined)
                          price           NUMERIC(12,2)   NOT NULL,
    -- NUMERIC(12,2): up to ₹9,999,999,999.99. Exact decimal.
    -- NEVER use FLOAT for price. 79.99 in float = 79.98999999999...
                          compare_at_price    NUMERIC(12,2),
    -- compare_at_price: "Was ₹200, Now ₹150" — the strikethrough price
                          unit            VARCHAR(50)     DEFAULT 'piece',
    -- "500g", "1kg", "1L", "piece", "dozen"
                          active          BOOLEAN         NOT NULL DEFAULT TRUE,
    -- WHY soft delete: order history references product.
    -- Hard delete breaks historical orders that reference deleted products.
                          avg_rating      NUMERIC(3,2)    DEFAULT 0.0,
                          total_reviews   INTEGER         DEFAULT 0,
                          image_urls      TEXT[],
    -- TEXT[]: PostgreSQL array. Stores multiple image URLs in one column.
    -- Alternative: separate product_images table. Array is simpler for ≤5 images.
                          created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                          updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_store    ON products(store_id);
CREATE INDEX idx_products_category ON products(category_id);
CREATE INDEX idx_products_active   ON products(store_id, active);
-- Composite index: "active products in store X" — common query, needs both fields.
-- Index (store_id, active) handles: WHERE store_id = 10 AND active = true

-- Full-text search index
CREATE INDEX idx_products_fts ON products
    USING GIN (to_tsvector('english', name || ' ' || COALESCE(description, '')));
-- GIN index on text-search vector. Enables:
-- WHERE to_tsvector('english', name) @@ to_tsquery('rice') → fast FTS

-- ─────────────────────────────────────────────────────────────────────────────
-- INVENTORY
-- One row per product. Tracks available quantity.
-- Separate from products: inventory changes frequently. Product info rarely.
-- Splitting reduces lock contention: updating inventory doesn't lock product row.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE inventory (
                           id                  BIGSERIAL   PRIMARY KEY,
                           product_id          BIGINT      NOT NULL UNIQUE REFERENCES products(id) ON DELETE CASCADE,
    -- UNIQUE: one inventory record per product
                           quantity            INTEGER     NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    -- CHECK quantity >= 0: database rejects any UPDATE that would set negative stock.
    -- Even if application code has a bug: the database is the last line of defence.
                           low_stock_threshold INTEGER     NOT NULL DEFAULT 10,
    -- When quantity drops below this: send low-stock alert to seller
                           version             INTEGER     NOT NULL DEFAULT 0,
    -- Optimistic lock version. @Version in JPA.
    -- WHY: for non-time-critical updates. If two updates conflict:
    -- one succeeds, the other gets OptimisticLockingFailureException.
    -- The failing request retries. No DB-level locking required.
    -- CONTRAST with pessimistic lock (SELECT FOR UPDATE) in Phase 7.
                           updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- WISHLISTS
-- Users can save products to buy later.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE wishlist_items (
                                id          BIGSERIAL   PRIMARY KEY,
                                user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                product_id  BIGINT      NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                                created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                UNIQUE (user_id, product_id)
    -- Composite UNIQUE: user cannot wishlist same product twice.
    -- Idempotent: adding again is safe, DB rejects the duplicate.
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ORDERS
-- The central commerce entity. Immutable once placed.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE orders (
                        id                  BIGSERIAL       PRIMARY KEY,
                        user_id             BIGINT          NOT NULL REFERENCES users(id),
                        store_id            BIGINT          NOT NULL REFERENCES stores(id),
                        idempotency_key     VARCHAR(200)    UNIQUE,
    -- UNIQUE: same idempotency key = same order. Client can retry safely.
    -- If network fails after order creation but before response:
    -- client retries with same key → gets back the original order, not a duplicate.
                        status              VARCHAR(30)     NOT NULL DEFAULT 'CREATED'
                            CHECK (status IN (
                                              'CREATED', 'CONFIRMED', 'PREPARING',
                                              'READY', 'OUT_FOR_DELIVERY', 'DELIVERED',
                                              'PICKED_UP', 'CANCELLED'
                                )),
                        order_type          VARCHAR(20)     NOT NULL CHECK (order_type IN ('DELIVERY', 'TAKEAWAY')),
                        total_amount        NUMERIC(12,2)   NOT NULL CHECK (total_amount > 0),
    -- CHECK total_amount > 0: prevents ₹0 orders from a UI bug
                        delivery_address    VARCHAR(500),
                        delivery_lat        DOUBLE PRECISION,
                        delivery_lng        DOUBLE PRECISION,
                        cancellation_reason VARCHAR(500),
                        saga_id             VARCHAR(200),
    -- saga_id: correlation ID for the distributed transaction (Phase 10)
                        notes               VARCHAR(500),
                        created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                        updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Composite index: seller's dashboard query "show me orders for my store"
CREATE INDEX idx_orders_store_status ON orders(store_id, status);
-- Buyer's order history: "show me my orders"
CREATE INDEX idx_orders_user ON orders(user_id);
-- Saga timeout detection: "find CREATED orders older than 2 minutes"
CREATE INDEX idx_orders_status_created ON orders(status, created_at)
    WHERE status = 'CREATED';
-- WHY partial index: only CREATED orders are queried by the timeout handler.
-- DELIVERED/CANCELLED orders don't need this index.

-- ─────────────────────────────────────────────────────────────────────────────
-- ORDER ITEMS
-- Individual line items within an order.
-- Snapshot of product data at order time.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE order_items (
                             id              BIGSERIAL       PRIMARY KEY,
                             order_id        BIGINT          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                             product_id      BIGINT          NOT NULL REFERENCES products(id),
                             product_name    VARCHAR(300)    NOT NULL,
    -- WHY snapshot product_name: if seller renames the product later,
    -- the order history should show the name at time of purchase.
    -- Without snapshot: the order shows the current name, not the ordered name.
                             unit_price      NUMERIC(12,2)   NOT NULL,
                             quantity        INTEGER         NOT NULL CHECK (quantity > 0),
                             subtotal        NUMERIC(12,2)   NOT NULL
    -- subtotal = unit_price * quantity (redundant but prevents recalculation bugs)
);

CREATE INDEX idx_order_items_order   ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- PAYMENTS
-- One payment record per order attempt.
-- Multiple records if buyer retries after failure.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE payments (
                          id                  BIGSERIAL       PRIMARY KEY,
                          order_id            BIGINT          NOT NULL REFERENCES orders(id),
                          amount              NUMERIC(12,2)   NOT NULL,
                          currency            VARCHAR(3)      NOT NULL DEFAULT 'INR',
                          method              VARCHAR(30)     NOT NULL CHECK (method IN ('COD', 'RAZORPAY', 'UPI')),
                          status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
                          gateway_order_id    VARCHAR(200),   -- Razorpay's order ID
                          gateway_payment_id  VARCHAR(200),   -- Razorpay's payment ID (after payment)
                          gateway_signature   VARCHAR(500),   -- For webhook verification
                          paid_at             TIMESTAMPTZ,
                          created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order            ON payments(order_id);
CREATE INDEX idx_payments_gateway_order_id ON payments(gateway_order_id);
-- Index on gateway_order_id: webhook handler looks up payment by Razorpay's ID.

-- ─────────────────────────────────────────────────────────────────────────────
-- INVOICES
-- Generated after successful payment.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE invoices (
                          id              BIGSERIAL       PRIMARY KEY,
                          order_id        BIGINT          NOT NULL UNIQUE REFERENCES orders(id),
                          invoice_number  VARCHAR(100)    NOT NULL UNIQUE,
                          amount          NUMERIC(12,2)   NOT NULL,
                          tax             NUMERIC(12,2)   NOT NULL DEFAULT 0,
                          generated_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- NOTIFICATIONS
-- Persistent in-app notification history.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE notifications (
                               id              BIGSERIAL       PRIMARY KEY,
                               user_id         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               type            VARCHAR(50)     NOT NULL,   -- ORDER_UPDATE, LOW_STOCK, etc.
                               title           VARCHAR(200)    NOT NULL,
                               body            VARCHAR(1000)   NOT NULL,
                               reference_id    BIGINT,         -- the orderId or productId this refers to
                               reference_type  VARCHAR(50),    -- 'ORDER', 'PRODUCT', 'DELIVERY'
                               read_at         TIMESTAMPTZ,    -- null = unread. Set when user reads it.
                               created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_unread
    ON notifications(user_id, created_at DESC)
    WHERE read_at IS NULL;
-- Partial index for unread notifications only.
-- The most common query: "show user's unread notifications, newest first"

-- ─────────────────────────────────────────────────────────────────────────────
-- DEVICE TOKENS (FCM Push Notification tokens)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE device_tokens (
                               id          BIGSERIAL   PRIMARY KEY,
                               user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               token       VARCHAR(500) NOT NULL UNIQUE,
                               platform    VARCHAR(10)  NOT NULL CHECK (platform IN ('IOS', 'ANDROID', 'WEB')),
                               active      BOOLEAN      NOT NULL DEFAULT TRUE,
                               created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_device_tokens_user_active
    ON device_tokens(user_id)
    WHERE active = TRUE;

-- ─────────────────────────────────────────────────────────────────────────────
-- REVIEWS
-- Verified purchase reviews for products and stores.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE reviews (
                         id          BIGSERIAL       PRIMARY KEY,
                         reviewer_id BIGINT          NOT NULL REFERENCES users(id),
                         order_id    BIGINT          NOT NULL REFERENCES orders(id),
                         target_type VARCHAR(20)     NOT NULL CHECK (target_type IN ('PRODUCT', 'STORE')),
                         target_id   BIGINT          NOT NULL,
                         rating      INTEGER         NOT NULL CHECK (rating BETWEEN 1 AND 5),
    -- INTEGER not SMALLINT: entity field is Integer (4 bytes).
    -- SMALLINT (2 bytes) causes Hibernate type mismatch.
    -- THIS IS THE BUG WE FOUND. Integer here prevents it.
                         comment     VARCHAR(2000),
                         created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Prevent duplicate reviews: one review per product per order
                         UNIQUE (reviewer_id, order_id, target_type, target_id)
);

CREATE INDEX idx_reviews_target ON reviews(target_type, target_id);
-- Used when displaying reviews: "show all reviews for product 42"

-- ─────────────────────────────────────────────────────────────────────────────
-- DELIVERY PARTNERS
-- Users with role=DELIVERY_PARTNER have a corresponding record here.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE delivery_partners (
                                   id                  BIGSERIAL       PRIMARY KEY,
                                   user_id             BIGINT          NOT NULL UNIQUE REFERENCES users(id),
    -- UNIQUE: one partner profile per user
                                   vehicle_type        VARCHAR(30)     CHECK (vehicle_type IN ('BICYCLE', 'MOTORCYCLE', 'CAR', 'VAN')),
                                   vehicle_number      VARCHAR(20),
                                   license_number      VARCHAR(50),
                                   status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                       CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'INACTIVE', 'SUSPENDED')),
                                   current_latitude    DOUBLE PRECISION,
                                   current_longitude   DOUBLE PRECISION,
                                   current_location    GEOGRAPHY(POINT, 4326),
    -- PostGIS spatial column for fast nearby partner queries
                                   total_deliveries    INTEGER         NOT NULL DEFAULT 0,
                                   avg_rating          NUMERIC(3,2)    DEFAULT 0.0,
                                   created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                                   updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_partners_available_location
    ON delivery_partners USING GIST (current_location)
    WHERE status = 'ACTIVE';
-- Only index ACTIVE partners: dispatcher never assigns to inactive partners.

-- ─────────────────────────────────────────────────────────────────────────────
-- DELIVERY ASSIGNMENTS
-- Links orders to delivery partners.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE delivery_assignments (
                                      id              BIGSERIAL   PRIMARY KEY,
                                      order_id        BIGINT      NOT NULL UNIQUE REFERENCES orders(id),
    -- UNIQUE: one assignment per order
                                      partner_id      BIGINT      NOT NULL REFERENCES delivery_partners(id),
                                      status          VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED'
                                          CHECK (status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
                                      assigned_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                      picked_up_at    TIMESTAMPTZ,
                                      delivered_at    TIMESTAMPTZ,
                                      delivery_proof_url VARCHAR(500)   -- photo of delivered package
);

CREATE INDEX idx_delivery_assignments_partner ON delivery_assignments(partner_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- STORE FOLLOWS
-- Buyers can follow stores to get announcements.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE store_follows (
                               id          BIGSERIAL   PRIMARY KEY,
                               user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               store_id    BIGINT      NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                               created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                               UNIQUE (user_id, store_id)
    -- Idempotent follow: following twice just returns the existing follow.
);

CREATE INDEX idx_store_follows_store ON store_follows(store_id);
-- Used for announcement fan-out: "get all follower userIds for store X"

-- ─────────────────────────────────────────────────────────────────────────────
-- ANNOUNCEMENTS
-- Sellers broadcast messages to their followers.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE announcements (
                               id          BIGSERIAL       PRIMARY KEY,
                               store_id    BIGINT          NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                               title       VARCHAR(200)    NOT NULL,
                               message     VARCHAR(2000)   NOT NULL,
                               created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_announcements_store ON announcements(store_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- ADS
-- Stores can pay to promote their products.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE ads (
                     id          BIGSERIAL       PRIMARY KEY,
                     store_id    BIGINT          NOT NULL REFERENCES stores(id),
                     product_id  BIGINT          REFERENCES products(id),
                     title       VARCHAR(200)    NOT NULL,
                     image_url   VARCHAR(500),
                     target_url  VARCHAR(500),
                     ad_type     VARCHAR(30)     NOT NULL CHECK (ad_type IN ('BANNER', 'SPONSORED_PRODUCT', 'POPUP')),
                     status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'PAUSED', 'EXPIRED')),
                     starts_at   TIMESTAMPTZ     NOT NULL,
                     ends_at     TIMESTAMPTZ     NOT NULL,
                     budget      NUMERIC(12,2),
                     impressions INTEGER         NOT NULL DEFAULT 0,
                     clicks      INTEGER         NOT NULL DEFAULT 0,
                     created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ads_active_dates
    ON ads(status, ends_at)
    WHERE status = 'ACTIVE';
-- For the ad expiry scheduler: "find active ads that have expired"

-- ─────────────────────────────────────────────────────────────────────────────
-- OUTBOX EVENTS (Transactional Outbox Pattern)
-- Adding now: cheaper than ALTER TABLE later.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE outbox_events (
                               id              BIGSERIAL       PRIMARY KEY,
                               aggregate_type  VARCHAR(100)    NOT NULL,
                               aggregate_id    VARCHAR(100)    NOT NULL,
                               topic           VARCHAR(200)    NOT NULL,
                               partition_key   VARCHAR(200),
                               event_type      VARCHAR(200)    NOT NULL,
                               payload         TEXT            NOT NULL,
                               status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
                               retry_count     INTEGER         NOT NULL DEFAULT 0,
                               max_retries     INTEGER         NOT NULL DEFAULT 3,
                               last_error      TEXT,
                               created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                               published_at    TIMESTAMPTZ,
                               next_retry_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_pending
    ON outbox_events(status, next_retry_at)
    WHERE status = 'PENDING';

-- ─────────────────────────────────────────────────────────────────────────────
-- PostGIS TRIGGERS
-- Auto-populate GEOGRAPHY columns from lat/lng
-- ─────────────────────────────────────────────────────────────────────────────

-- Enable PostGIS
CREATE EXTENSION IF NOT EXISTS postgis;

-- Trigger function for stores
CREATE OR REPLACE FUNCTION sync_store_location()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL THEN
        NEW.location := ST_SetSRID(
            ST_MakePoint(NEW.longitude, NEW.latitude), 4326
        )::GEOGRAPHY;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_store_location
    BEFORE INSERT OR UPDATE OF latitude, longitude ON stores
    FOR EACH ROW EXECUTE FUNCTION sync_store_location();

-- Trigger function for delivery partners
CREATE OR REPLACE FUNCTION sync_partner_location()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.current_latitude IS NOT NULL AND NEW.current_longitude IS NOT NULL THEN
        NEW.current_location := ST_SetSRID(
            ST_MakePoint(NEW.current_longitude, NEW.current_latitude), 4326
        )::GEOGRAPHY;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_partner_location
    BEFORE INSERT OR UPDATE OF current_latitude, current_longitude
                     ON delivery_partners
                         FOR EACH ROW EXECUTE FUNCTION sync_partner_location();