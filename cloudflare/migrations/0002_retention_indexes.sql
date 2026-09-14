-- ============================================================================
-- Migration 0002 — retention / lookup indexes
-- ============================================================================
-- Safe, additive migration: only indexes are created, no table rewrite and no
-- data mutation. `IF NOT EXISTS` keeps the migration idempotent so it can be
-- re-applied on a database that already received the index.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_safety_events_kind_created
  ON safety_events (kind, status, created_at);

CREATE INDEX IF NOT EXISTS idx_family_invites_expiry
  ON family_invites (expires_at);

CREATE INDEX IF NOT EXISTS idx_evidence_sos
  ON evidence_files (sos_event_id);

CREATE INDEX IF NOT EXISTS idx_rate_limit_window
  ON rate_limit_counters (window_start);
