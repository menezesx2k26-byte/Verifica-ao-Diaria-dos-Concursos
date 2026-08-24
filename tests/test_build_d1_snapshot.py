import unittest

from build_d1_snapshot import build_snapshot


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


if __name__ == "__main__":
    unittest.main()
