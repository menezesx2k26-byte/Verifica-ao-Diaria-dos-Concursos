import tempfile
import unittest
from pathlib import Path

from build_d1_snapshot import Snapshot, build_snapshot, write_sql


class BuildD1SnapshotTest(unittest.TestCase):
    def test_snapshot_excludes_non_accepted_contests(self):
        contests = {
            "items": [
                {"id": "ok", "title": "Concurso Público", "organization": "Órgão", "relevance_status": "ACCEPTED"},
                {"id": "bad", "title": "Pregão Eletrônico", "organization": "Órgão", "relevance_status": "REJECTED_PROCUREMENT"},
                {"id": "uncertain", "title": "Aviso", "organization": "Órgão", "relevance_status": "QUARANTINED_LOW_CONFIDENCE"},
            ],
            "source_health": [],
        }

        snapshot = build_snapshot(contests, {"events": [], "documents": [], "source_health": []})

        self.assertEqual([item["id"] for item in snapshot.contests], ["ok"])

    def test_referenced_priority_contest_gets_a_stable_fk_parent(self):
        priority = {
            "documents": [{"id": "doc-1", "contest_id": "pg-004-2024-acs"}],
            "events": [{"id": "event-1", "contest_id": "pg-004-2024-acs"}],
            "source_health": [],
        }

        snapshot = build_snapshot({"items": [], "source_health": []}, priority)

        self.assertIn("pg-004-2024-acs", {item["id"] for item in snapshot.contests})
        parent = next(item for item in snapshot.contests if item["id"] == "pg-004-2024-acs")
        self.assertEqual(parent["relevance_status"], "ACCEPTED")
        self.assertTrue(parent["active"])

    def test_write_sql_is_transactional_and_escapes_source_text(self):
        snapshot = Snapshot(
            contests=({
                "id": "abc",
                "organization": "Prefeitura d'Oeste",
                "title": "Concurso d'Água",
                "source": "Diário",
                "url": "https://example.gov/contest",
                "first_seen": "2026-08-24T00:00:00Z",
                "last_seen": "2026-08-24T00:00:00Z",
                "updated_at": "2026-08-24T00:00:00Z",
                "relevance_status": "ACCEPTED",
            },),
            documents=(),
            events=(),
            alerts=(),
            source_health=(),
        )
        path = Path(tempfile.mkdtemp()) / "snapshot.sql"

        write_sql(snapshot, path)
        sql = path.read_text(encoding="utf-8")

        self.assertTrue(sql.startswith("BEGIN IMMEDIATE;"))
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertIn("Prefeitura d''Oeste", sql)
        self.assertIn("Concurso d''Água", sql)
        self.assertIn("ON CONFLICT(id) DO UPDATE", sql)

    def test_write_sql_persists_documents_events_health_and_alerts_after_contests(self):
        stamp = "2026-08-24T00:00:00Z"
        snapshot = Snapshot(
            contests=({
                "id": "pg-004-2024-acs", "organization": "PG", "title": "ACS",
                "first_seen": stamp, "last_seen": stamp, "updated_at": stamp,
            },),
            documents=({
                "id": "doc-1", "contest_id": "pg-004-2024-acs", "source_id": "source-1",
                "kind": "pdf", "title": "Edital", "url": "https://example.gov/doc.pdf",
                "sha256": "a" * 64, "fetched_at": stamp, "metadata": {"watch": "acs"},
            },),
            events=({
                "id": "event-1", "contest_id": "pg-004-2024-acs", "source_id": "source-1",
                "type": "convocation", "title": "Convocação", "body": "texto", "url": "https://example.gov/doc.pdf",
                "priority": 115, "happened_at": stamp, "created_at": stamp, "fingerprint": "fp-1",
            },),
            alerts=({
                "event_id": "event-1", "title": "Convocação", "body": "texto", "url": "https://example.gov/doc.pdf",
                "priority": 115, "created_at": stamp,
            },),
            source_health=({
                "id": "source-1", "label": "Fonte", "url": "https://example.gov",
                "http_ok": True, "parser_ok": True, "semantic_ok": True, "item_count": 1,
                "expected_min": 1, "checked_at": stamp, "last_success_at": stamp,
                "fingerprint": "hash", "scan_status": "NEW_EVENT", "error": "",
            },),
        )
        path = Path(tempfile.mkdtemp()) / "snapshot.sql"

        write_sql(snapshot, path)
        sql = path.read_text(encoding="utf-8")

        contest_pos = sql.index("INSERT INTO contests")
        document_pos = sql.index("INSERT INTO documents")
        event_pos = sql.index("INSERT INTO events")
        health_pos = sql.index("INSERT INTO source_health")
        alert_pos = sql.index("INSERT INTO alerts")
        self.assertLess(contest_pos, document_pos)
        self.assertLess(document_pos, event_pos)
        self.assertLess(event_pos, alert_pos)
        self.assertIn("metadata_json", sql)
        self.assertIn("WHERE NOT EXISTS", sql[alert_pos:])
        self.assertGreater(health_pos, event_pos)


if __name__ == "__main__":
    unittest.main()
