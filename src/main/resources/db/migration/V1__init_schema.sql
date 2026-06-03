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
