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
