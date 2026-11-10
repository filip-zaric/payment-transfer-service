CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    balance_minor BIGINT NOT NULL CHECK (balance_minor >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP'))
);
CREATE INDEX accounts_owner ON accounts(owner_id);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    principal_id VARCHAR(128) NOT NULL,
    idempotency_key UUID NOT NULL,
    source_id UUID NOT NULL REFERENCES accounts(id),
    destination_id UUID NOT NULL REFERENCES accounts(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP')),
    created_at TIMESTAMPTZ NOT NULL,
    source_balance_after BIGINT NOT NULL CHECK (source_balance_after >= 0),
    destination_balance_after BIGINT NOT NULL CHECK (destination_balance_after >= 0),
    UNIQUE (principal_id, idempotency_key),
    CHECK (source_id <> destination_id)
);
CREATE INDEX transfers_principal_time ON transfers(principal_id, created_at DESC, id);

CREATE TABLE ledger_entries (
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    signed_amount_minor BIGINT NOT NULL CHECK (signed_amount_minor <> 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP')),
    PRIMARY KEY (transfer_id, account_id)
);

CREATE FUNCTION prevent_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER transfers_append_only BEFORE UPDATE OR DELETE ON transfers
FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
CREATE TRIGGER ledger_append_only BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();

-- Deferred until COMMIT so the service can insert the transfer and both postings in any order.
CREATE FUNCTION verify_transfer_postings() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    transfer_uuid UUID;
    transfer_row transfers%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'transfers' THEN transfer_uuid := NEW.id;
    ELSE transfer_uuid := NEW.transfer_id;
    END IF;
    SELECT * INTO STRICT transfer_row FROM transfers WHERE id = transfer_uuid;
    IF (SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = transfer_uuid) <> 2
       OR EXISTS (
           SELECT 1 FROM ledger_entries WHERE transfer_id = transfer_uuid
           AND NOT (currency = transfer_row.currency AND (
               (account_id = transfer_row.source_id AND signed_amount_minor = -transfer_row.amount_minor)
               OR (account_id = transfer_row.destination_id AND signed_amount_minor = transfer_row.amount_minor)
           ))
       ) THEN
        RAISE EXCEPTION 'A transfer requires matching debit and credit postings' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER transfer_requires_postings AFTER INSERT ON transfers
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_transfer_postings();
CREATE CONSTRAINT TRIGGER posting_requires_balance AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_transfer_postings();
