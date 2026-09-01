import unittest

import publish_api_snapshot as publisher


class PublishApiSnapshotTests(unittest.TestCase):
    def test_build_payload_includes_priority_contest_parents(self):
        contests = {"items": []}
        priority = {
            "documents": [
                {
                    "id": "doc-1",
                    "contest_id": "pg-004-2024-acs",
                    "source_id": "pg_diario",
                    "url": "https://example.gov.br/doc.pdf",
                    "sha256": "abc",
                }
            ],
            "events": [
                {
                    "id": "event-1",
                    "contest_id": "sv-02-2026-atg",
                    "source_id": "sv",
                    "type": "convocation",
                    "fingerprint": "fp",
                }
            ],
            "new_events": [],
            "source_health": [],
        }

        payload = publisher.build_payload(contests, priority)
        ids = {item["id"] for item in payload["contests"]}

        self.assertIn("pg-004-2024-acs", ids)
        self.assertIn("sv-02-2026-atg", ids)
        self.assertIn("pg-002-2025-prof-mat", ids)
        referenced = {
            item["contest_id"]
            for item in payload["documents"] + payload["events"]
            if item.get("contest_id")
        }
        self.assertTrue(referenced.issubset(ids))

    def test_existing_canonical_contest_wins_over_synthetic_parent(self):
        contests = {
            "items": [
                {
                    "id": "sv-02-2026-atg",
                    "title": "Título canônico enriquecido",
                    "organization": "Prefeitura de São Vicente",
                    "status": "open",
                }
            ]
        }
        payload = publisher.build_payload(contests, {"documents": [], "events": [], "new_events": [], "source_health": []})
        item = next(x for x in payload["contests"] if x["id"] == "sv-02-2026-atg")
        self.assertEqual(item["title"], "Título canônico enriquecido")


if __name__ == "__main__":
    unittest.main()
