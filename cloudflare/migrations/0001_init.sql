-- ============================================================================
-- Guardian Cloudflare backend — initial relational schema
-- ============================================================================
-- Design notes:
--  * The legacy realtime database was a JSON tree (contact_status/*,
--    safety_incidents/*, sos_events/*). The Cloudflare schema is relational:
--    explicit primary keys, foreign keys, indexes and CHECK constraints.
--  * Sensitive data minimisation: no medical records, no raw IP addresses and
--    no plaintext credentials/refresh tokens are stored. Medical profile data
--    intentionally remains on-device (it was never synced to the cloud either).
--  * All timestamps are epoch milliseconds (INTEGER) in UTC.
--  * Ids are application generated UUID v4 (TEXT).
-- ============================================================================

CREATE TABLE users (
  id             TEXT    PRIMARY KEY NOT NULL,
  email          TEXT    NOT NULL,
  password_hash  TEXT    NOT NULL,
  display_name   TEXT    NOT NULL,
  email_verified INTEGER NOT NULL DEFAULT 0,
  phone          TEXT,
  locale         TEXT,
  disabled_at    INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  CHECK (email_verified IN (0, 1)),
  CHECK (length(email) BETWEEN 3 AND 320),
  CHECK (length(display_name) BETWEEN 1 AND 80)
) STRICT;

-- Case-insensitive uniqueness for login identity.
CREATE UNIQUE INDEX idx_users_email_lower ON users (lower(email));

CREATE TABLE profiles (
  user_id      TEXT    PRIMARY KEY NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  display_name TEXT    NOT NULL,
  phone        TEXT,
  locale       TEXT,
  updated_at   INTEGER NOT NULL
) STRICT;

-- One row per authenticated device session. Refresh tokens are only ever
-- stored as SHA-256 hashes; the access token is a short lived signed JWT.
CREATE TABLE sessions (
  id                     TEXT    PRIMARY KEY NOT NULL,
  user_id                TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  refresh_token_hash     TEXT    NOT NULL,
  device_id              TEXT,
  device_label           TEXT,
  created_at             INTEGER NOT NULL,
  last_used_at           INTEGER NOT NULL,
  access_expires_at      INTEGER NOT NULL,
  refresh_expires_at     INTEGER NOT NULL,
  rotated_at             INTEGER,
  revoked_at             INTEGER,
  revoked_reason         TEXT
) STRICT;

CREATE UNIQUE INDEX idx_sessions_refresh_hash ON sessions (refresh_token_hash);
CREATE INDEX idx_sessions_user ON sessions (user_id, revoked_at);
CREATE INDEX idx_sessions_refresh_expiry ON sessions (refresh_expires_at);

-- Rotation history: presentation of any previously rotated refresh token
-- indicates replay, and the whole session is revoked (token theft response).
CREATE TABLE refresh_token_history (
  token_hash TEXT    PRIMARY KEY NOT NULL,
  session_id TEXT    NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
  user_id    TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  rotated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_refresh_history_session ON refresh_token_history (session_id);

CREATE TABLE devices (
  id           TEXT    NOT NULL,
  user_id      TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  platform     TEXT    NOT NULL DEFAULT 'android',
  label        TEXT,
  app_version  TEXT,
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, id)
) STRICT;

CREATE TABLE emergency_contacts (
  id            TEXT    PRIMARY KEY NOT NULL,
  user_id       TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name          TEXT    NOT NULL,
  phone         TEXT    NOT NULL,
  relationship  TEXT    NOT NULL DEFAULT '',
  is_verified   INTEGER NOT NULL DEFAULT 0,
  priority      INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  CHECK (is_verified IN (0, 1)),
  CHECK (length(name) BETWEEN 1 AND 80),
  CHECK (length(phone) BETWEEN 3 AND 32)
) STRICT;

CREATE INDEX idx_emergency_contacts_user ON emergency_contacts (user_id, priority);

CREATE TABLE family_groups (
  id            TEXT    PRIMARY KEY NOT NULL,
  name          TEXT    NOT NULL,
  owner_user_id TEXT    NOT NULL REFERENCES users (id),
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  archived_at   INTEGER,
  CHECK (length(name) BETWEEN 1 AND 60)
) STRICT;

CREATE TABLE family_members (
  id        TEXT    PRIMARY KEY NOT NULL,
  group_id  TEXT    NOT NULL REFERENCES family_groups (id) ON DELETE CASCADE,
  user_id   TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  role      TEXT    NOT NULL,
  joined_at INTEGER NOT NULL,
  removed_at INTEGER,
  CHECK (role IN ('OWNER', 'PARENT', 'GUARDIAN', 'CHILD', 'MEMBER')),
  UNIQUE (group_id, user_id)
) STRICT;

CREATE INDEX idx_family_members_user ON family_members (user_id, removed_at);
CREATE INDEX idx_family_members_group ON family_members (group_id, removed_at);

-- Invite codes are stored hashed and are single use.
CREATE TABLE family_invites (
  id          TEXT    PRIMARY KEY NOT NULL,
  group_id    TEXT    NOT NULL REFERENCES family_groups (id) ON DELETE CASCADE,
  code_hash   TEXT    NOT NULL,
  role        TEXT    NOT NULL,
  created_by  TEXT    NOT NULL REFERENCES users (id),
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  consumed_at INTEGER,
  consumed_by TEXT REFERENCES users (id),
  CHECK (role IN ('PARENT', 'GUARDIAN', 'CHILD', 'MEMBER'))
) STRICT;

CREATE UNIQUE INDEX idx_family_invites_code ON family_invites (code_hash);
CREATE INDEX idx_family_invites_group ON family_invites (group_id, consumed_at);

-- Presence replaces the legacy connection listener + disconnect hooks.
CREATE TABLE presence (
  user_id      TEXT    PRIMARY KEY NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  status       TEXT    NOT NULL,
  last_seen_at INTEGER NOT NULL,
  battery_level INTEGER,
  source       TEXT    NOT NULL DEFAULT 'APP',
  CHECK (status IN ('ONLINE', 'OFFLINE')),
  CHECK (battery_level IS NULL OR battery_level BETWEEN 0 AND 100)
) STRICT;

CREATE INDEX idx_presence_last_seen ON presence (last_seen_at);

-- Community safety reports: replaces the legacy `safety_incidents` tree.
CREATE TABLE safety_events (
  id               TEXT    PRIMARY KEY NOT NULL,
  kind             TEXT    NOT NULL,
  reporter_user_id TEXT    REFERENCES users (id) ON DELETE SET NULL,
  title            TEXT    NOT NULL,
  category         TEXT    NOT NULL,
  severity         TEXT    NOT NULL,
  description      TEXT    NOT NULL DEFAULT '',
  latitude         REAL    NOT NULL,
  longitude        REAL    NOT NULL,
  geohash          TEXT    NOT NULL,
  occurred_at      INTEGER NOT NULL,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER,
  status           TEXT    NOT NULL DEFAULT 'ACTIVE',
  confirmations    INTEGER NOT NULL DEFAULT 0,
  CHECK (kind IN ('HAZARD', 'INCIDENT')),
  CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
  CHECK (status IN ('ACTIVE', 'RESOLVED', 'ARCHIVED')),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180),
  CHECK (length(title) BETWEEN 1 AND 120),
  CHECK (length(description) <= 2000),
  CHECK (confirmations >= 0)
) STRICT;

CREATE INDEX idx_safety_events_bbox ON safety_events (status, latitude, longitude);
CREATE INDEX idx_safety_events_reporter ON safety_events (reporter_user_id, created_at);
CREATE INDEX idx_safety_events_expiry ON safety_events (expires_at);

CREATE TABLE safety_event_votes (
  event_id   TEXT    NOT NULL REFERENCES safety_events (id) ON DELETE CASCADE,
  user_id    TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (event_id, user_id)
) STRICT;

-- Replaces the legacy `sos_events` tree. (user_id, client_event_id) is unique so
-- an offline device can safely replay an SOS without creating duplicates.
CREATE TABLE sos_events (
  id               TEXT    PRIMARY KEY NOT NULL,
  user_id          TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  client_event_id  TEXT    NOT NULL,
  trigger_source   TEXT    NOT NULL,
  status           TEXT    NOT NULL DEFAULT 'ACTIVE',
  latitude         REAL,
  longitude        REAL,
  accuracy_m       REAL,
  battery_level    INTEGER,
  network_status   TEXT,
  device_info      TEXT,
  occurred_at      INTEGER NOT NULL,
  received_at      INTEGER NOT NULL,
  acknowledged_at  INTEGER,
  resolved_at      INTEGER,
  resolution_note  TEXT,
  CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED', 'CANCELLED')),
  CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  CHECK (battery_level IS NULL OR battery_level BETWEEN 0 AND 100),
  CHECK (length(trigger_source) BETWEEN 1 AND 64)
) STRICT;

CREATE UNIQUE INDEX idx_sos_events_client_key ON sos_events (user_id, client_event_id);
CREATE INDEX idx_sos_events_user ON sos_events (user_id, occurred_at);
CREATE INDEX idx_sos_events_status ON sos_events (status, occurred_at);

-- Opt-in, time-boxed location sharing (never continuous background upload).
CREATE TABLE location_shares (
  id            TEXT    PRIMARY KEY NOT NULL,
  user_id       TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  group_id      TEXT    REFERENCES family_groups (id) ON DELETE CASCADE,
  latitude      REAL    NOT NULL,
  longitude     REAL    NOT NULL,
  accuracy_m    REAL,
  battery_level INTEGER,
  source        TEXT    NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  CHECK (source IN ('SOS', 'CHECKIN', 'SAFE_ZONE', 'MANUAL')),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180)
) STRICT;

CREATE INDEX idx_location_shares_group ON location_shares (group_id, created_at);
CREATE INDEX idx_location_shares_user ON location_shares (user_id, created_at);
CREATE INDEX idx_location_shares_expiry ON location_shares (expires_at);

CREATE TABLE checkins (
  id               TEXT    PRIMARY KEY NOT NULL,
  user_id          TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  group_id         TEXT    REFERENCES family_groups (id) ON DELETE SET NULL,
  duration_minutes INTEGER NOT NULL,
  status           TEXT    NOT NULL,
  note             TEXT    NOT NULL DEFAULT '',
  started_at       INTEGER NOT NULL,
  due_at           INTEGER NOT NULL,
  completed_at     INTEGER,
  CHECK (status IN ('ACTIVE', 'COMPLETED', 'ALERTED')),
  CHECK (duration_minutes BETWEEN 1 AND 1440),
  CHECK (length(note) <= 500)
) STRICT;

CREATE INDEX idx_checkins_user ON checkins (user_id, started_at);
CREATE INDEX idx_checkins_due ON checkins (status, due_at);

CREATE TABLE family_messages (
  id              TEXT    PRIMARY KEY NOT NULL,
  group_id        TEXT    NOT NULL REFERENCES family_groups (id) ON DELETE CASCADE,
  sender_user_id  TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  body            TEXT    NOT NULL,
  is_emergency    INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL,
  CHECK (is_emergency IN (0, 1)),
  CHECK (length(body) BETWEEN 1 AND 2000)
) STRICT;

CREATE INDEX idx_family_messages_group ON family_messages (group_id, created_at);

-- Security/abuse audit trail. IP addresses are stored only as keyed hashes.
CREATE TABLE audit_logs (
  id         TEXT    PRIMARY KEY NOT NULL,
  user_id    TEXT    REFERENCES users (id) ON DELETE SET NULL,
  action     TEXT    NOT NULL,
  outcome    TEXT    NOT NULL,
  ip_hash    TEXT,
  user_agent TEXT,
  details    TEXT,
  created_at INTEGER NOT NULL,
  CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
) STRICT;

CREATE INDEX idx_audit_logs_user ON audit_logs (user_id, created_at);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, created_at);

CREATE TABLE evidence_files (
  id            TEXT    PRIMARY KEY NOT NULL,
  user_id       TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  sos_event_id  TEXT    REFERENCES sos_events (id) ON DELETE SET NULL,
  object_key    TEXT    NOT NULL,
  content_type  TEXT    NOT NULL,
  size_bytes    INTEGER NOT NULL,
  sha256        TEXT    NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  deleted_at    INTEGER,
  CHECK (size_bytes >= 0),
  CHECK (size_bytes <= 5242880)
) STRICT;

CREATE UNIQUE INDEX idx_evidence_object_key ON evidence_files (object_key);
CREATE INDEX idx_evidence_user ON evidence_files (user_id, created_at);
CREATE INDEX idx_evidence_expiry ON evidence_files (expires_at);

-- Fixed window counters backing the API rate limiter.
CREATE TABLE rate_limit_counters (
  bucket       TEXT    NOT NULL,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket, window_start)
) STRICT;
