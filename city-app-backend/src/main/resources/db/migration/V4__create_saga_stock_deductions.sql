CREATE TABLE saga_stock_deductions (
    id BIGSERIAL PRIMARY KEY,
    saga_id VARCHAR(100) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    items_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DEDUCTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    compensated_at TIMESTAMPTZ,
    CONSTRAINT chk_saga_stock_deduction_status
        CHECK (status IN ('DEDUCTED', 'RESTORED'))
);

CREATE INDEX idx_saga_stock_deductions_order_id
    ON saga_stock_deductions (order_id);

ALTER TABLE saga_stock_deductions
    ADD CONSTRAINT fk_saga_stock_deductions_order
    FOREIGN KEY (order_id) REFERENCES orders(id);