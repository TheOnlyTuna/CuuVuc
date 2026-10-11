CREATE TABLE sect_accounts (
    sect_id uuid PRIMARY KEY REFERENCES sects,
    account_type text NOT NULL DEFAULT 'SECT' CHECK (account_type = 'SECT'),
    currency text NOT NULL DEFAULT 'linh_te' CHECK (currency = 'linh_te'),
    balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0)
);
INSERT INTO sect_accounts(sect_id) SELECT sect_id FROM sects;
ALTER TABLE sect_members ADD COLUMN treasury_spend boolean NOT NULL DEFAULT false;
ALTER TABLE sect_members ADD CONSTRAINT sect_spend_role CHECK (NOT treasury_spend OR role = 'ELDER');
ALTER TABLE ledger ALTER COLUMN player_id DROP NOT NULL;
ALTER TABLE ledger ADD COLUMN sect_id uuid REFERENCES sect_accounts;
ALTER TABLE ledger ADD CONSTRAINT ledger_account CHECK (num_nonnulls(player_id,sect_id) = 1);
ALTER TABLE ledger ADD CONSTRAINT ledger_request_sect UNIQUE (request_id,sect_id);
CREATE INDEX ledger_sect_history ON ledger(sect_id,entry_id DESC);
ALTER TABLE building_contracts ADD COLUMN funding_sect_id uuid REFERENCES sect_accounts;
ALTER TABLE sect_history DROP CONSTRAINT sect_history_event_type_check;
ALTER TABLE sect_history ADD CONSTRAINT sect_history_event_type_check CHECK (event_type IN ('CREATE','INVITE','ACCEPT','ROLE','KICK','LEAVE','CANCEL_INVITE','SPEND_GRANT','SPEND_REVOKE','CONTRIBUTE','WITHDRAW','CONTRACT_DEPOSIT','CONTRACT_PAYMENT','CONTRACT_REFUND'));
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (7);
