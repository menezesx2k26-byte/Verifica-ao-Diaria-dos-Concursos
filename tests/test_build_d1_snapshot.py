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


if __name__ == "__main__":
    unittest.main()
