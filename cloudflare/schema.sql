PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS contests (
  id TEXT PRIMARY KEY,
  organization TEXT NOT NULL,
  notice_number TEXT NOT NULL DEFAULT '',
  year INTEGER,
  title TEXT NOT NULL,
  city TEXT NOT NULL DEFAULT '',
  uf TEXT NOT NULL DEFAULT '',
  region TEXT NOT NULL DEFAULT '',
  scope TEXT NOT NULL DEFAULT '',
  board TEXT NOT NULL DEFAULT '',
  type TEXT NOT NULL DEFAULT '',
  education TEXT NOT NULL DEFAULT '',
  area TEXT NOT NULL DEFAULT '',
  remuneration TEXT NOT NULL DEFAULT '',
  vacancies TEXT NOT NULL DEFAULT '',
  fee TEXT NOT NULL DEFAULT '',
  registration_start TEXT NOT NULL DEFAULT '',
  registration_end TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'detected',
  source TEXT NOT NULL DEFAULT '',
  source_url TEXT NOT NULL DEFAULT '',
  edital_url TEXT NOT NULL DEFAULT '',
  priority INTEGER NOT NULL DEFAULT 50,
  active INTEGER NOT NULL DEFAULT 1,
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_contests_status ON contests(status, active);
CREATE INDEX IF NOT EXISTS idx_contests_region ON contests(uf, region, scope);
CREATE INDEX IF NOT EXISTS idx_contests_priority ON contests(priority DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS documents (
  id TEXT PRIMARY KEY,
  contest_id TEXT,
  source_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  url TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  published_at TEXT NOT NULL DEFAULT '',
  fetched_at TEXT NOT NULL,
  text_excerpt TEXT NOT NULL DEFAULT '',
  metadata_json TEXT NOT NULL DEFAULT '{}',
  FOREIGN KEY(contest_id) REFERENCES contests(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_source_hash ON documents(source_id, sha256);
CREATE INDEX IF NOT EXISTS idx_documents_contest ON documents(contest_id, fetched_at DESC);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  contest_id TEXT,
  source_id TEXT NOT NULL,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  url TEXT NOT NULL DEFAULT '',
  priority INTEGER NOT NULL DEFAULT 0,
  happened_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  FOREIGN KEY(contest_id) REFERENCES contests(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_events_fingerprint ON events(fingerprint);
CREATE INDEX IF NOT EXISTS idx_events_priority ON events(priority DESC, created_at DESC);

CREATE TABLE IF NOT EXISTS source_health (
  id TEXT PRIMARY KEY,
  label TEXT NOT NULL,
  url TEXT NOT NULL,
  http_ok INTEGER NOT NULL DEFAULT 0,
  parser_ok INTEGER NOT NULL DEFAULT 0,
  semantic_ok INTEGER NOT NULL DEFAULT 0,
  item_count INTEGER NOT NULL DEFAULT 0,
  expected_min INTEGER NOT NULL DEFAULT 0,
  checked_at TEXT NOT NULL,
  last_success_at TEXT NOT NULL DEFAULT '',
  fingerprint TEXT NOT NULL DEFAULT '',
  scan_status TEXT NOT NULL DEFAULT 'UNKNOWN',
  error TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS alerts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT,
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  url TEXT NOT NULL DEFAULT '',
  priority INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  FOREIGN KEY(event_id) REFERENCES events(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_alerts_created ON alerts(created_at DESC);

CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
