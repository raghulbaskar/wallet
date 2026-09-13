CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(60)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Opaque identity string, not a FK to users(id): bearer-token callers on the
-- core API never create a users row, only the login layer's signup/login does.
CREATE TABLE IF NOT EXISTS wallets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(64) NOT NULL UNIQUE,
    balance_paise   BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance_paise >= 0)
);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wallets_updated_at
    BEFORE UPDATE ON wallets
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE IF NOT EXISTS transfers (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key   VARCHAR(128) NOT NULL UNIQUE,
    from_wallet_id    UUID NOT NULL REFERENCES wallets(id),
    to_wallet_id      UUID NOT NULL REFERENCES wallets(id),
    amount_paise      BIGINT NOT NULL,
    status            VARCHAR(32) NOT NULL,
    request_hash      VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_positive_amount   CHECK (amount_paise > 0),
    CONSTRAINT chk_distinct_wallets  CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX IF NOT EXISTS idx_transfers_from_wallet_history
    ON transfers (from_wallet_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_transfers_to_wallet_history
    ON transfers (to_wallet_id, created_at DESC, id DESC);
