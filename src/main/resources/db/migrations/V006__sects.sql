CREATE TABLE sects (
    sect_id uuid PRIMARY KEY,
    name text NOT NULL CHECK (char_length(name) >= 3) CHECK (char_length(name) <= 32),
    name_key text NOT NULL UNIQUE,
    leader_id uuid NOT NULL REFERENCES profiles,
    leader_role text NOT NULL DEFAULT 'LEADER' CHECK (leader_role = 'LEADER'),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE sect_members (
    player_id uuid PRIMARY KEY REFERENCES profiles,
    sect_id uuid NOT NULL REFERENCES sects,
    role text NOT NULL CHECK (role IN ('LEADER','ELDER','MEMBER')),
    joined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (sect_id,player_id,role)
);
CREATE UNIQUE INDEX sect_one_leader ON sect_members(sect_id) WHERE role = 'LEADER';
CREATE INDEX sect_members_sect ON sect_members(sect_id,player_id);
ALTER TABLE sects ADD CONSTRAINT sect_leader_membership FOREIGN KEY (sect_id,leader_id,leader_role)
    REFERENCES sect_members(sect_id,player_id,role) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE sect_invitations (
    invitation_id uuid PRIMARY KEY REFERENCES operations(request_id),
    sect_id uuid NOT NULL REFERENCES sects,
    inviter_id uuid NOT NULL REFERENCES profiles,
    recipient_id uuid NOT NULL REFERENCES profiles,
    status text NOT NULL CHECK (status IN ('PENDING','ACCEPTED','CANCELLED')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX sect_invitations_recipient ON sect_invitations(recipient_id,created_at);
CREATE INDEX sect_invitations_sect ON sect_invitations(sect_id,created_at);
CREATE TABLE sect_history (
    entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sect_id uuid NOT NULL REFERENCES sects,
    request_id uuid NOT NULL UNIQUE REFERENCES operations,
    actor_id uuid NOT NULL REFERENCES profiles,
    target_id uuid NOT NULL REFERENCES profiles,
    event_type text NOT NULL CHECK (event_type IN ('CREATE','INVITE','ACCEPT','ROLE','KICK','LEAVE','CANCEL_INVITE')),
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX sect_history_sect ON sect_history(sect_id,entry_id);
CREATE TRIGGER sect_history_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON sect_history FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (6);
