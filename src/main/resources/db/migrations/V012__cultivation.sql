CREATE TABLE cultivation_progress (
    player_id uuid PRIMARY KEY REFERENCES profiles,
    rank integer NOT NULL CHECK (rank BETWEEN 0 AND 10),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    legacy_title text
);
INSERT INTO cultivation_progress(player_id,rank,legacy_title)
SELECT player_id, CASE WHEN cultivation_xp >= 500 THEN 10 WHEN cultivation_xp >= 100 THEN 1 ELSE 0 END,
       CASE WHEN cultivation_xp >= 10000 THEN 'Nguyên Anh' WHEN cultivation_xp >= 2000 THEN 'Kim Đan'
            WHEN cultivation_xp >= 500 THEN 'Trúc Cơ' WHEN cultivation_xp >= 100 THEN 'Luyện Khí' ELSE 'Phàm nhân' END
FROM profiles;
CREATE TABLE cultivation_policy (
    singleton boolean PRIMARY KEY CHECK (singleton),
    rules_sha256 text NOT NULL
);
CREATE TABLE cultivation_trials (
    trial_id uuid PRIMARY KEY REFERENCES operations(request_id),
    player_id uuid NOT NULL REFERENCES cultivation_progress,
    sequence text NOT NULL CHECK (sequence ~ '^[0-4](,[0-4]){4}$'),
    step integer NOT NULL DEFAULT 0 CHECK (step BETWEEN 0 AND 5),
    status text NOT NULL CHECK (status IN ('ACTIVE','SUCCESS','FAILED','INTERRUPTED')),
    preview_until timestamptz NOT NULL,
    deadline timestamptz NOT NULL CHECK (deadline > preview_until),
    finished_at timestamptz,
    retry_at timestamptz,
    reason text,
    CHECK ((status='ACTIVE' AND finished_at IS NULL AND retry_at IS NULL AND reason IS NULL)
        OR (status<>'ACTIVE' AND finished_at IS NOT NULL AND retry_at IS NOT NULL AND reason IS NOT NULL)),
    CHECK (status <> 'SUCCESS' OR step=5)
);
CREATE UNIQUE INDEX cultivation_one_trial ON cultivation_trials(player_id) WHERE status='ACTIVE';
CREATE INDEX cultivation_trial_history ON cultivation_trials(player_id,deadline DESC);
CREATE TABLE cultivation_history (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id uuid NOT NULL REFERENCES cultivation_progress,
    request_id uuid REFERENCES operations,
    trial_id uuid REFERENCES cultivation_trials,
    kind text NOT NULL CHECK (kind IN ('MIGRATED','TRAIN','START','SUCCESS','FAILED','INTERRUPTED')),
    rank integer NOT NULL CHECK (rank BETWEEN 0 AND 10),
    xp bigint NOT NULL CHECK (xp >= 0),
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(request_id)
);
CREATE INDEX cultivation_history_player ON cultivation_history(player_id,event_id DESC);
CREATE INDEX cultivation_history_trial ON cultivation_history(trial_id);
CREATE TRIGGER cultivation_history_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON cultivation_history
FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_edit();
INSERT INTO cultivation_history(player_id,kind,rank,xp,detail)
SELECT p.player_id,'MIGRATED',c.rank,p.cultivation_xp,'Kế thừa danh hiệu XP trước V012: ' || c.legacy_title
FROM profiles p JOIN cultivation_progress c USING(player_id);
DELETE FROM schema_version;
INSERT INTO schema_version VALUES (12);
