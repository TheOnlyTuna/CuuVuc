CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY);
INSERT INTO schema_version VALUES (1) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS profiles (
  player_id uuid PRIMARY KEY,
  player_name varchar(16) NOT NULL,
  cultivation_xp bigint NOT NULL DEFAULT 0 CHECK (cultivation_xp >= 0),
  balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS operations (
  request_id uuid PRIMARY KEY,
  fingerprint text NOT NULL,
  result text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS inventory (
  player_id uuid NOT NULL REFERENCES profiles,
  resource_id text NOT NULL,
  quantity bigint NOT NULL CHECK (quantity >= 0),
  PRIMARY KEY (player_id, resource_id)
);
CREATE TABLE IF NOT EXISTS ledger (
  entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id uuid NOT NULL REFERENCES operations,
  player_id uuid NOT NULL REFERENCES profiles,
  delta bigint NOT NULL CHECK (delta <> 0),
  balance_after bigint NOT NULL CHECK (balance_after >= 0),
  reason text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (request_id, player_id)
);
CREATE INDEX IF NOT EXISTS ledger_player_history ON ledger (player_id, entry_id DESC);
CREATE TABLE IF NOT EXISTS mine_nodes (
  node_id text PRIMARY KEY,
  available_at timestamptz NOT NULL
);
CREATE OR REPLACE FUNCTION reject_ledger_edit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Ledger is append-only' USING ERRCODE = '55000';
END;
$$;
CREATE OR REPLACE TRIGGER ledger_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger
FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
