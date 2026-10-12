CREATE TABLE recovery_authorities (
    player_id uuid PRIMARY KEY REFERENCES profiles,
    enabled boolean NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0)
);
CREATE TABLE recovery_authority_history (
    entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id uuid NOT NULL REFERENCES profiles,
    enabled boolean NOT NULL,
    revision bigint NOT NULL,
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 8 AND 500),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(player_id,revision)
);
CREATE TRIGGER recovery_authority_history_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_authority_history FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
CREATE TABLE recovery_reviews (
    review_id uuid PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES building_contracts,
    actor_id uuid NOT NULL REFERENCES profiles,
    evidence text NOT NULL,
    world_observation text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX recovery_reviews_contract ON recovery_reviews(contract_id,created_at);
CREATE TRIGGER recovery_reviews_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_reviews FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
CREATE TABLE recovery_decisions (
    review_id uuid PRIMARY KEY REFERENCES recovery_reviews,
    actor_id uuid NOT NULL REFERENCES profiles,
    decision text NOT NULL CHECK (decision IN ('KEEP_LOCKED','RESOLVE_INTENTS_AND_REOPEN')),
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 8 AND 500),
    checked_world text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER recovery_decisions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_decisions FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
ALTER TABLE contract_block_journal DROP CONSTRAINT contract_block_journal_state_check;
ALTER TABLE contract_block_journal ADD CONSTRAINT contract_block_journal_state_check CHECK (state IN ('INTENT','APPLIED','ABORTED','REVIEWED_UNCONFIRMED'));
ALTER TABLE contract_block_journal ADD COLUMN resolution_review_id uuid REFERENCES recovery_decisions(review_id);
ALTER TABLE contract_block_journal ADD CONSTRAINT journal_review_resolution CHECK ((state='REVIEWED_UNCONFIRMED' AND resolution_review_id IS NOT NULL) OR (state<>'REVIEWED_UNCONFIRMED' AND resolution_review_id IS NULL));
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (10);
