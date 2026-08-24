import unittest

from ensure_d1_v4 import schema_action


class EnsureD1V4Tests(unittest.TestCase):
    def test_fresh_database_uses_full_schema(self):
        self.assertEqual(schema_action([]), "schema")

    def test_legacy_database_runs_v4_migration(self):
        rows = [{"name": "id"}, {"name": "title"}, {"name": "active"}]
        self.assertEqual(schema_action(rows), "migration")

    def test_v4_database_only_reconciles_idempotent_schema(self):
        rows = [
            {"name": "id"},
            {"name": "relevance_status"},
            {"name": "relevance_confidence"},
        ]
        self.assertEqual(schema_action(rows), "reconcile")


if __name__ == "__main__":
    unittest.main()
