CREATE TABLE accounts (
    account_id        UUID PRIMARY KEY,
    currency          CHAR(3)       NOT NULL,
    available_balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    reserved_balance  NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (reserved_balance >= 0)
);
CREATE TABLE orders (
    order_id    UUID PRIMARY KEY,
    account_id  UUID          NOT NULL REFERENCES accounts(account_id),
    symbol      VARCHAR(16)   NOT NULL,
    side        VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity    NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    limit_price NUMERIC(19,4) NOT NULL CHECK (limit_price > 0),
    status      VARCHAR(12)   NOT NULL CHECK (status IN ('NEW', 'FILLED', 'CANCELLED')),
    executed_at TIMESTAMP(3) WITH TIME ZONE
);
CREATE TABLE ledger (
    entry_id    BIGSERIAL PRIMARY KEY,
    account_id  UUID          NOT NULL REFERENCES accounts(account_id),
    symbol      VARCHAR(16)   NOT NULL,
    side        VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity    NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    executed_at TIMESTAMP(3) WITH TIME ZONE NOT NULL
);
CREATE INDEX ledger_account_time ON ledger (account_id, executed_at);
CREATE TABLE idempotency_keys (
    idem_key   VARCHAR(128) PRIMARY KEY,
    order_id   UUID NOT NULL REFERENCES orders(order_id),
    expires_at TIMESTAMP(3) WITH TIME ZONE NOT NULL
);
