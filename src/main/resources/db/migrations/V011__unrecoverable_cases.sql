CREATE TABLE unrecoverable_cases (
    case_id uuid PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES building_contracts,
    review_id uuid NOT NULL UNIQUE REFERENCES recovery_reviews,
    proposer_id uuid NOT NULL REFERENCES profiles,
    plan text NOT NULL CHECK (plan IN ('KEEP_QUARANTINED','SETTLE_HELD_ESCROW')),
    compensation bigint NOT NULL CHECK (compensation BETWEEN 0 AND 1000),
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 8 AND 500),
    evidence text NOT NULL,
    world_observation text NOT NULL,
    findings text NOT NULL CHECK (char_length(findings)>0),
    assets text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (plan='SETTLE_HELD_ESCROW' OR compensation=0)
);
CREATE INDEX unrecoverable_cases_contract ON unrecoverable_cases(contract_id,created_at);
CREATE TRIGGER unrecoverable_cases_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON unrecoverable_cases FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
CREATE TABLE unrecoverable_decisions (
    case_id uuid PRIMARY KEY REFERENCES unrecoverable_cases,
    actor_id uuid NOT NULL REFERENCES profiles,
    outcome text NOT NULL CHECK (outcome IN ('APPROVED','REJECTED')),
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 8 AND 500),
    checked_world text NOT NULL,
    assets_after text NOT NULL,
    request_id uuid UNIQUE REFERENCES operations,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    authority_revision bigint NOT NULL CHECK (authority_revision>0),
    CHECK ((outcome='APPROVED' AND request_id IS NOT NULL) OR (outcome='REJECTED' AND request_id IS NULL))
);
CREATE TRIGGER unrecoverable_decisions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON unrecoverable_decisions FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
ALTER TABLE contract_escrow_ledger DROP CONSTRAINT contract_escrow_ledger_kind_check;
ALTER TABLE contract_escrow_ledger ADD CONSTRAINT contract_escrow_ledger_kind_check CHECK (kind IN ('DEPOSIT','RELEASE','REFUND','RECOVERY_SETTLEMENT'));
ALTER TABLE contract_escrow_ledger DROP CONSTRAINT contract_escrow_ledger_check;
ALTER TABLE contract_escrow_ledger ADD CONSTRAINT contract_escrow_ledger_check CHECK ((kind='DEPOSIT' AND delta>0) OR (kind IN ('RELEASE','REFUND','RECOVERY_SETTLEMENT') AND delta<0));
DROP INDEX escrow_settles_once;
CREATE UNIQUE INDEX escrow_settles_once ON contract_escrow_ledger(contract_id) WHERE kind IN ('RELEASE','REFUND','RECOVERY_SETTLEMENT');
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (11);
