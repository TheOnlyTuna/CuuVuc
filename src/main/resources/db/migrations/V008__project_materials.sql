CREATE TABLE projects (
    project_id uuid PRIMARY KEY REFERENCES operations(request_id),
    sect_id uuid NOT NULL REFERENCES sects,
    owner_id uuid NOT NULL REFERENCES profiles,
    island_slot integer NOT NULL UNIQUE REFERENCES islands(slot),
    name text NOT NULL CHECK (char_length(name) BETWEEN 3 AND 32),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX projects_sect ON projects(sect_id,created_at);
CREATE TABLE project_stages (
    stage_id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects,
    worksite integer NOT NULL CHECK (worksite IN (1,2)),
    template_id text NOT NULL,
    status text NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','RESERVED','COMPLETED')),
    contract_id uuid UNIQUE REFERENCES building_contracts,
    UNIQUE(project_id,worksite),
    UNIQUE(stage_id,worksite,template_id),
    CHECK ((worksite=1 AND template_id='STONE_PAD_V1') OR (worksite=2 AND template_id='STONE_PATH_V1')),
    CHECK (status='READY' OR contract_id IS NOT NULL)
);
CREATE TABLE project_inventory (
    project_id uuid NOT NULL REFERENCES projects,
    resource_id text NOT NULL,
    quantity bigint NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY(project_id,resource_id)
);
CREATE TABLE project_stage_costs (
    stage_id uuid NOT NULL REFERENCES project_stages,
    resource_id text NOT NULL CHECK (resource_id IN ('linh_thach','huyen_thiet')),
    quantity bigint NOT NULL CHECK (quantity BETWEEN 1 AND 1000),
    PRIMARY KEY(stage_id,resource_id)
);
CREATE TRIGGER project_costs_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON project_stage_costs FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
CREATE TABLE stage_inventory (
    stage_id uuid NOT NULL REFERENCES project_stages,
    resource_id text NOT NULL,
    quantity bigint NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY(stage_id,resource_id)
);
CREATE TABLE project_history (
    entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects,
    stage_id uuid REFERENCES project_stages,
    request_id uuid NOT NULL UNIQUE REFERENCES operations,
    actor_id uuid NOT NULL REFERENCES profiles,
    event_type text NOT NULL CHECK (event_type IN ('CREATE','CONTRIBUTE','RESERVE','CONSUME','REFUND')),
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX project_history_project ON project_history(project_id,entry_id DESC);
CREATE TRIGGER project_history_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON project_history FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
ALTER TABLE building_contracts ADD COLUMN worksite integer NOT NULL DEFAULT 0 CHECK (worksite BETWEEN 0 AND 2);
ALTER TABLE building_contracts ADD COLUMN template_id text NOT NULL DEFAULT 'LEGACY_FLOOR_V1' CHECK (template_id IN ('LEGACY_FLOOR_V1','STONE_PAD_V1','STONE_PATH_V1'));
ALTER TABLE building_contracts ADD COLUMN project_stage_id uuid REFERENCES project_stages;
ALTER TABLE building_contracts ADD CONSTRAINT contract_worksite CHECK (
    (worksite=0 AND template_id='LEGACY_FLOOR_V1' AND project_stage_id IS NULL)
    OR (worksite=1 AND template_id='STONE_PAD_V1' AND project_stage_id IS NOT NULL AND funding_sect_id IS NOT NULL)
    OR (worksite=2 AND template_id='STONE_PATH_V1' AND project_stage_id IS NOT NULL AND funding_sect_id IS NOT NULL)
);
ALTER TABLE building_contracts ADD CONSTRAINT contract_stage_template FOREIGN KEY(project_stage_id,worksite,template_id) REFERENCES project_stages(stage_id,worksite,template_id);
ALTER TABLE building_contracts ADD CONSTRAINT contract_stage_pair UNIQUE(contract_id,project_stage_id);
ALTER TABLE project_stages ADD CONSTRAINT stage_contract_pair FOREIGN KEY(contract_id,stage_id) REFERENCES building_contracts(contract_id,project_stage_id) DEFERRABLE INITIALLY DEFERRED;
DROP INDEX one_unresolved_contract_per_island;
CREATE UNIQUE INDEX one_unresolved_contract_per_worksite ON building_contracts(island_slot,worksite) WHERE status IN ('OPEN','ACTIVE','SUBMITTED');
ALTER TABLE resource_ledger ALTER COLUMN player_id DROP NOT NULL;
ALTER TABLE resource_ledger ALTER COLUMN counterparty_id DROP NOT NULL;
ALTER TABLE resource_ledger ADD COLUMN project_id uuid REFERENCES projects;
ALTER TABLE resource_ledger ADD COLUMN stage_id uuid REFERENCES project_stages;
ALTER TABLE resource_ledger ADD COLUMN counterparty_project_id uuid REFERENCES projects;
ALTER TABLE resource_ledger ADD COLUMN counterparty_stage_id uuid REFERENCES project_stages;
ALTER TABLE resource_ledger ADD COLUMN actor_id uuid REFERENCES profiles;
ALTER TABLE resource_ledger ADD COLUMN kind text NOT NULL DEFAULT 'TRANSFER' CHECK (kind IN ('TRANSFER','CONSUME'));
ALTER TABLE resource_ledger ADD CONSTRAINT resource_account CHECK (num_nonnulls(player_id,project_id,stage_id)=1);
ALTER TABLE resource_ledger ADD CONSTRAINT resource_counterparty CHECK (
    (kind='TRANSFER' AND num_nonnulls(counterparty_id,counterparty_project_id,counterparty_stage_id)=1)
    OR (kind='CONSUME' AND delta<0 AND num_nonnulls(counterparty_id,counterparty_project_id,counterparty_stage_id)=0)
);
ALTER TABLE resource_ledger ADD CONSTRAINT resource_request_project UNIQUE(request_id,project_id,resource_id);
ALTER TABLE resource_ledger ADD CONSTRAINT resource_request_stage UNIQUE(request_id,stage_id,resource_id);
CREATE INDEX resource_ledger_project ON resource_ledger(project_id,entry_id DESC);
CREATE INDEX resource_ledger_stage ON resource_ledger(stage_id,entry_id DESC);
ALTER TABLE sect_history DROP CONSTRAINT sect_history_event_type_check;
ALTER TABLE sect_history ADD CONSTRAINT sect_history_event_type_check CHECK (event_type IN ('CREATE','INVITE','ACCEPT','ROLE','KICK','LEAVE','CANCEL_INVITE','SPEND_GRANT','SPEND_REVOKE','CONTRIBUTE','WITHDRAW','CONTRACT_DEPOSIT','CONTRACT_PAYMENT','CONTRACT_REFUND','PROJECT_CREATE','PROJECT_CONTRIBUTE'));
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (8);
