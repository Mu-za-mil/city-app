-- V7: Accelerate case-insensitive substring product searches.
-- pg_trgm supports LIKE patterns such as '%rice%' with GIN indexes.
-- Partial indexes keep inactive (soft-deleted) products out of the search indexes.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_products_name_trgm_active
    ON products USING GIN (LOWER(name) gin_trgm_ops)
    WHERE active = TRUE;

CREATE INDEX idx_products_description_trgm_active
    ON products USING GIN (LOWER(description) gin_trgm_ops)
    WHERE active = TRUE;
