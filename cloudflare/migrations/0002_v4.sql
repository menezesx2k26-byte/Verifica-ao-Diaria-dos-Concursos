PRAGMA foreign_keys = ON;

ALTER TABLE contests ADD COLUMN relevance_status TEXT NOT NULL DEFAULT 'ACCEPTED';
ALTER TABLE contests ADD COLUMN relevance_confidence INTEGER NOT NULL DEFAULT 100;

CREATE INDEX IF NOT EXISTS idx_contests_relevance
  ON contests(relevance_status, active, priority DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS dashboard_configs (
  version INTEGER PRIMARY KEY,
  schema_version INTEGER NOT NULL,
  style_version INTEGER NOT NULL,
  min_app_version TEXT NOT NULL,
  published_at TEXT NOT NULL,
  sections_json TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('draft', 'published', 'superseded'))
);

CREATE INDEX IF NOT EXISTS idx_dashboard_configs_status
  ON dashboard_configs(status, version DESC);
