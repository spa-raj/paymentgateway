CREATE TABLE payments (
    id                   BINARY(16)     NOT NULL,
    created_at           DATETIME       NULL,
    last_modified_at     DATETIME       NULL,
    is_deleted           BIT(1)         NOT NULL DEFAULT 0,
    created_by           BINARY(16)     NULL,
    last_modified_by     BINARY(16)     NULL,
    version              INT            NULL,
    order_id             BINARY(16)     NOT NULL,
    user_id              VARCHAR(255)   NOT NULL,
    amount               DECIMAL(19,2)  NOT NULL,
    currency             VARCHAR(10)    NOT NULL DEFAULT 'INR',
    status               VARCHAR(20)    NOT NULL,
    gateway              VARCHAR(30)    NOT NULL,
    gateway_payment_id   VARCHAR(255)   NULL,
    gateway_payment_link VARCHAR(1024)  NULL,
    failure_reason       VARCHAR(500)   NULL,
    order_event_id       VARCHAR(255)   NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_payment_order ON payments(order_id);
CREATE UNIQUE INDEX idx_payment_order_event ON payments(order_event_id);
CREATE INDEX idx_payment_user ON payments(user_id, created_at DESC);
CREATE INDEX idx_payment_gateway_id ON payments(gateway_payment_id);
