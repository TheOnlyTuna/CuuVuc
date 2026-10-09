ALTER TABLE operations ADD COLUMN actor_id uuid;
UPDATE operations SET actor_id = split_part(fingerprint, ':', 2)::uuid;
ALTER TABLE operations ALTER COLUMN actor_id SET NOT NULL;
ALTER TABLE ledger ADD COLUMN business_day date;
ALTER TABLE ledger DISABLE TRIGGER ledger_immutable;
UPDATE ledger SET business_day = (created_at AT TIME ZONE 'UTC')::date;
ALTER TABLE ledger ENABLE TRIGGER ledger_immutable;
ALTER TABLE ledger ALTER COLUMN business_day SET NOT NULL;
ALTER TABLE ledger ALTER COLUMN business_day SET DEFAULT (transaction_timestamp() AT TIME ZONE 'UTC')::date;
CREATE TABLE economy_policy (
    singleton boolean PRIMARY KEY CHECK (singleton),
    currency_id text NOT NULL CHECK (currency_id = 'linh_te'),
    server_daily_mint bigint NOT NULL CHECK (server_daily_mint > 0),
    player_daily_mint bigint NOT NULL CHECK (player_daily_mint > 0 AND player_daily_mint <= server_daily_mint),
    catalog_sha256 text NOT NULL
);
CREATE TABLE economy_daily (
    business_day date PRIMARY KEY,
    minted bigint NOT NULL CHECK (minted >= 0),
    burned bigint NOT NULL CHECK (burned >= 0)
);
CREATE TABLE player_mint_daily (
    business_day date NOT NULL,
    player_id uuid NOT NULL REFERENCES profiles,
    minted bigint NOT NULL CHECK (minted >= 0),
    PRIMARY KEY (business_day, player_id)
);
INSERT INTO economy_daily
SELECT business_day,
       coalesce(sum(delta) FILTER (WHERE reason LIKE 'NPC_SELL:%'), 0),
       coalesce(-sum(delta) FILTER (WHERE reason LIKE 'NPC_BUY:%'), 0)
FROM ledger GROUP BY business_day;
INSERT INTO player_mint_daily
SELECT business_day, player_id, sum(delta)
FROM ledger WHERE reason LIKE 'NPC_SELL:%' GROUP BY business_day, player_id;
CREATE INDEX ledger_business_day ON ledger (business_day, reason);
UPDATE schema_version SET version = 2 WHERE version = 1;
