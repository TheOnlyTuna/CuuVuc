CREATE TABLE resource_ledger (
    entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES operations,
    player_id uuid NOT NULL REFERENCES profiles,
    counterparty_id uuid NOT NULL REFERENCES profiles,
    resource_id text NOT NULL,
    delta bigint NOT NULL CHECK (delta BETWEEN -1000 AND 1000 AND delta <> 0),
    balance_after bigint NOT NULL CHECK (balance_after >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (player_id <> counterparty_id),
    UNIQUE (request_id,player_id,resource_id)
);
CREATE INDEX resource_ledger_player ON resource_ledger(player_id,entry_id);
CREATE TRIGGER resource_ledger_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON resource_ledger FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (5);
