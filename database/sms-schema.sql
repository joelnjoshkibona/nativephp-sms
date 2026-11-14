-- sms.sqlite, schema version 4. Written by SmsDbTest from SmsDb.MIGRATIONS; do not edit.

CREATE TABLE attachments (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    mime TEXT NOT NULL,
    size INTEGER NOT NULL DEFAULT 0,
    local_path TEXT,
    thumb_path TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE blocklist (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('number', 'prefix', 'wildcard', 'keyword')),
    action TEXT NOT NULL DEFAULT 'block' CHECK (action IN ('block', 'private')),
    notify TEXT CHECK (notify IN ('generic', 'silent')),
    pattern TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE change_log (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    entity TEXT NOT NULL CHECK (entity IN ('thread', 'message', 'attachment')),
    entity_id TEXT NOT NULL,
    op TEXT NOT NULL CHECK (op IN ('upsert', 'delete')),
    ts INTEGER NOT NULL
);

CREATE TABLE contact_cache (
    number_e164 TEXT PRIMARY KEY,
    name TEXT,
    color_override TEXT,
    avatar_ref TEXT,
    updated_at INTEGER NOT NULL
, number TEXT);

CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,
    at INTEGER NOT NULL,
    data TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    thread_id TEXT NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    system_id INTEGER UNIQUE,
    direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
    address TEXT NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    ts INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('received', 'queued', 'sent', 'delivered', 'failed')),
    sim_slot INTEGER,
    sub_id INTEGER,
    kind TEXT NOT NULL DEFAULT 'sms' CHECK (kind IN ('sms', 'mms')),
    parts_total INTEGER NOT NULL DEFAULT 1,
    parts_sent INTEGER NOT NULL DEFAULT 0,
    parts_failed INTEGER NOT NULL DEFAULT 0,
    parts_delivered INTEGER NOT NULL DEFAULT 0,
    client_ref TEXT UNIQUE,
    read INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
, sent_at INTEGER, delivered_at INTEGER, smsc_at INTEGER);

CREATE VIRTUAL TABLE messages_fts USING fts4(content="messages", body, tokenize=unicode61);

CREATE TABLE paired_devices (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    public_key TEXT NOT NULL,
    paired_at INTEGER NOT NULL,
    web_access_enabled INTEGER NOT NULL DEFAULT 1,
    last_seen INTEGER
);

CREATE TABLE scheduled (
    id TEXT PRIMARY KEY,
    thread_id TEXT NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    send_at INTEGER NOT NULL,
    sub_id INTEGER,
    status TEXT NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting', 'sending', 'sent', 'failed', 'cancelled')),
    message_id TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE spam_senders (
    address TEXT PRIMARY KEY,
    reported_at INTEGER NOT NULL
);

CREATE TABLE templates (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    shortcut TEXT UNIQUE,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE threads (
    id TEXT PRIMARY KEY,
    participants_key TEXT NOT NULL UNIQUE,
    addresses TEXT NOT NULL DEFAULT '[]',
    system_thread_id INTEGER,
    folder TEXT NOT NULL DEFAULT 'inbox' CHECK (folder IN ('inbox', 'spam', 'archive')),
    last_message_at INTEGER,
    last_message_id TEXT,
    unread INTEGER NOT NULL DEFAULT 0,
    pinned INTEGER NOT NULL DEFAULT 0,
    muted INTEGER NOT NULL DEFAULT 0,
    color TEXT,
    wallpaper TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX attachments_message ON attachments (message_id);

CREATE INDEX messages_thread ON messages (thread_id, ts);

CREATE INDEX scheduled_due ON scheduled (status, send_at);

CREATE INDEX threads_list ON threads (folder, pinned, last_message_at);

CREATE TRIGGER attachments_delete_log AFTER DELETE ON attachments BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('attachment', OLD.id, 'delete', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER attachments_insert_log AFTER INSERT ON attachments BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('attachment', NEW.id, 'upsert', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER attachments_update_log AFTER UPDATE ON attachments BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('attachment', NEW.id, 'upsert', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER messages_delete_log AFTER DELETE ON messages BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('message', OLD.id, 'delete', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER messages_insert_log AFTER INSERT ON messages BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('message', NEW.id, 'upsert', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER messages_update_log AFTER UPDATE ON messages BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('message', NEW.id, 'upsert', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER threads_delete_log AFTER DELETE ON threads BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('thread', OLD.id, 'delete', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER threads_insert_log AFTER INSERT ON threads BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('thread', NEW.id, 'upsert', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;

CREATE TRIGGER threads_update_log AFTER UPDATE ON threads BEGIN INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('thread', NEW.id, 'upsert', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)); END;
