CREATE TABLE IF NOT EXISTS play_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    start_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    end_at INTEGER
);
UPDATE play_sessions SET end_at = last_seen_at WHERE end_at IS NULL;
CREATE UNIQUE INDEX play_sessions_one_open ON play_sessions ((1)) WHERE end_at IS NULL;
CREATE INDEX play_sessions_start ON play_sessions (start_at);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE resource_types (name TEXT PRIMARY KEY COLLATE NOCASE, unit TEXT NOT NULL);
INSERT INTO resource_types VALUES ('Primogems', 'Primogems'), ('Intertwined Fates', 'Fates'),
    ('Quests', 'quests'), ('Materials', 'items'), ('Playtime', 'hours');
CREATE TABLE objectives (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL CHECK(length(trim(title)) > 0),
    description TEXT NOT NULL,
    resource TEXT NOT NULL REFERENCES resource_types(name),
    target INTEGER NOT NULL CHECK(target > 0),
    created_at INTEGER NOT NULL,
    deadline TEXT,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','COMPLETED','ARCHIVED','DELETED')),
    source TEXT NOT NULL CHECK(source IN ('MANUAL','AUTOMATIC','MIXED')),
    generation INTEGER NOT NULL DEFAULT 0,
    eligible_since INTEGER NOT NULL
);
CREATE INDEX objectives_eligibility ON objectives(resource,status,source);
CREATE TABLE daily_income (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL,
    account TEXT NOT NULL,
    income_day TEXT NOT NULL UNIQUE,
    total INTEGER NOT NULL CHECK(total >= 0),
    category TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(provider,account,income_day)
);
CREATE TABLE resource_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_key TEXT NOT NULL UNIQUE,
    resource TEXT NOT NULL REFERENCES resource_types(name),
    event_day TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    amount INTEGER NOT NULL,
    category TEXT NOT NULL,
    note TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('MANUAL','IMPORT','REVERSAL','PLAYTIME','ADJUSTMENT','BASELINE')),
    objective_id INTEGER REFERENCES objectives(id),
    daily_id INTEGER REFERENCES daily_income(id),
    reverses_id INTEGER UNIQUE REFERENCES resource_events(id)
);
CREATE INDEX resource_events_day ON resource_events(resource,event_day);
CREATE INDEX resource_events_daily ON resource_events(daily_id,id);
CREATE TABLE objective_events (
    event_id INTEGER NOT NULL REFERENCES resource_events(id),
    objective_id INTEGER NOT NULL REFERENCES objectives(id),
    generation INTEGER NOT NULL,
    amount INTEGER NOT NULL,
    PRIMARY KEY(event_id,objective_id,generation)
);
CREATE INDEX objective_events_progress ON objective_events(objective_id,generation);
CREATE TABLE tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL CHECK(length(trim(title)) > 0),
    category TEXT NOT NULL,
    priority TEXT NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH')),
    due TEXT,
    completed INTEGER NOT NULL CHECK(completed IN (0,1))
);
CREATE TABLE income_lots (
    sequence INTEGER PRIMARY KEY,
    event_id INTEGER NOT NULL UNIQUE REFERENCES resource_events(id),
    daily_id INTEGER NOT NULL REFERENCES daily_income(id),
    remaining INTEGER NOT NULL CHECK(remaining >= 0)
);
CREATE INDEX income_lots_daily ON income_lots(daily_id,sequence);
