ALTER TABLE profiles ADD COLUMN inventory_revision bigint NOT NULL DEFAULT 0 CHECK (inventory_revision >= 0);
CREATE TABLE item_tokens (
    token_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id uuid NOT NULL REFERENCES profiles,
    resource_id text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 36),
    UNIQUE (player_id, resource_id, ordinal)
);
CREATE TABLE islands (
    player_id uuid PRIMARY KEY REFERENCES profiles,
    slot integer GENERATED ALWAYS AS IDENTITY UNIQUE CHECK (slot BETWEEN 1 AND 10000),
    ready boolean NOT NULL DEFAULT false
);
CREATE TABLE island_builders (
    owner_id uuid NOT NULL REFERENCES islands(player_id),
    builder_id uuid NOT NULL REFERENCES profiles(player_id),
    PRIMARY KEY (owner_id, builder_id),
    CHECK (owner_id <> builder_id)
);
CREATE TABLE world_state (
    singleton boolean PRIMARY KEY CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
INSERT INTO world_state(singleton) VALUES (true);
UPDATE schema_version SET version = 3 WHERE version = 2;
