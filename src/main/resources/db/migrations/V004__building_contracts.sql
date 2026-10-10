CREATE TABLE contract_state (
    singleton boolean PRIMARY KEY CHECK (singleton),
    revision bigint NOT NULL CHECK (revision >= 0)
);
INSERT INTO contract_state VALUES (true,0);
CREATE TABLE building_contracts (
    contract_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES profiles,
    builder_id uuid REFERENCES profiles,
    island_slot integer NOT NULL REFERENCES islands(slot),
    reward bigint NOT NULL CHECK (reward BETWEEN 1 AND 1000),
    status text NOT NULL CHECK (status IN ('OPEN','ACTIVE','SUBMITTED','COMPLETED','CANCELLED','EXPIRED')),
    expires_at timestamptz NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (builder_id IS NULL OR builder_id <> owner_id),
    CHECK (status NOT IN ('ACTIVE','SUBMITTED','COMPLETED') OR builder_id IS NOT NULL)
);
CREATE UNIQUE INDEX one_unresolved_contract_per_island ON building_contracts(island_slot) WHERE status IN ('OPEN','ACTIVE','SUBMITTED');
CREATE INDEX contracts_expiry ON building_contracts(expires_at) WHERE status IN ('OPEN','ACTIVE','SUBMITTED');
CREATE TABLE contract_escrow_accounts (
    contract_id uuid PRIMARY KEY REFERENCES building_contracts,
    currency_id text NOT NULL CHECK (currency_id = 'linh_te'),
    balance bigint NOT NULL CHECK (balance BETWEEN 0 AND 1000)
);
CREATE TABLE contract_escrow_ledger (
    entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES contract_escrow_accounts,
    request_id uuid NOT NULL REFERENCES operations,
    kind text NOT NULL CHECK (kind IN ('DEPOSIT','RELEASE','REFUND')),
    delta bigint NOT NULL CHECK (delta <> 0),
    balance_after bigint NOT NULL CHECK (balance_after >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(contract_id,kind),
    CHECK ((kind='DEPOSIT' AND delta>0) OR (kind IN ('RELEASE','REFUND') AND delta<0))
);
CREATE UNIQUE INDEX escrow_settles_once ON contract_escrow_ledger(contract_id) WHERE kind IN ('RELEASE','REFUND');
CREATE TRIGGER escrow_ledger_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON contract_escrow_ledger FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
CREATE TABLE building_history (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES building_contracts,
    actor_id uuid NOT NULL REFERENCES profiles,
    event_type text NOT NULL CHECK (event_type IN ('CREATE','ACCEPT','SUBMIT','COMPLETE','CANCEL','EXPIRE','PLACE','BREAK')),
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX building_history_contract ON building_history(contract_id,event_id);
CREATE TRIGGER building_history_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON building_history FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (4);
