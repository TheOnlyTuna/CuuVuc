CREATE TABLE contract_block_journal (
    event_id uuid PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES building_contracts(contract_id),
    actor_id uuid NOT NULL REFERENCES profiles(player_id),
    event_type text NOT NULL CHECK (event_type IN ('PLACE','BREAK')),
    position text NOT NULL CHECK (position ~ '^-?[0-9]+:-?[0-9]+:-?[0-9]+$'),
    before_material text NOT NULL,
    after_material text NOT NULL,
    state text NOT NULL CHECK (state IN ('INTENT','APPLIED','ABORTED')),
    prepared_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    audit_event_id bigint UNIQUE REFERENCES building_history(event_id),
    CHECK (before_material <> after_material),
    CHECK ((state='INTENT' AND resolved_at IS NULL) OR (state<>'INTENT' AND resolved_at IS NOT NULL)),
    CHECK ((state='APPLIED' AND audit_event_id IS NOT NULL) OR (state<>'APPLIED' AND audit_event_id IS NULL))
);
CREATE UNIQUE INDEX contract_block_pending_position ON contract_block_journal(contract_id,position) WHERE state='INTENT';
CREATE INDEX contract_block_journal_contract ON contract_block_journal(contract_id,prepared_at,event_id);
CREATE TABLE contract_recovery_fences (
    contract_id uuid PRIMARY KEY REFERENCES building_contracts(contract_id),
    classification text NOT NULL CHECK (classification IN ('INCOMPLETE','MISMATCH','UNRECOVERABLE')),
    reason text NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (9);
