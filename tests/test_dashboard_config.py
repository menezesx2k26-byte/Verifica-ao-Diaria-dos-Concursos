import json
import tempfile
import unittest
from pathlib import Path

from build_dashboard_sql import build_draft_sql, build_promotion_sql
from validate_dashboard_config import validate_dashboard_config


class DashboardConfigTests(unittest.TestCase):
    def valid_config(self):
        return {
            "schema_version": 1,
            "dashboard_version": 7,
            "style_version": 1,
            "min_app_version": "4.0.0",
            "sections": [
                {"type": "attention", "limit": 3},
                {"type": "priority_watch"},
                {"type": "open_contests", "limit": 8},
            ],
        }

    def test_unknown_section_type_is_rejected(self):
        data = self.valid_config()
        data["sections"] = [{"type": "raw_html", "html": "<script>x</script>"}]
        with self.assertRaises(ValueError):
            validate_dashboard_config(data)

    def test_unknown_section_key_is_rejected(self):
        data = self.valid_config()
        data["sections"][0]["html"] = "<b>nope</b>"
        with self.assertRaises(ValueError):
            validate_dashboard_config(data)

    def test_valid_config_generates_draft_and_atomic_promotion_sql(self):
        data = self.valid_config()
        validate_dashboard_config(data)
        draft = build_draft_sql(data)
        promote = build_promotion_sql(data["dashboard_version"])
        self.assertIn("sections_json, status", draft)
        self.assertIn("'draft'", draft)
        self.assertIn("ON CONFLICT(version) DO UPDATE", draft)
        self.assertTrue(draft.startswith("BEGIN IMMEDIATE;"))
        self.assertTrue(draft.rstrip().endswith("COMMIT;"))
        self.assertIn("SET status='published'", promote)
        self.assertIn("status='draft'", promote)
        self.assertIn("SET status='superseded'", promote)
        self.assertIn("version<>7", promote)
        self.assertTrue(promote.startswith("BEGIN IMMEDIATE;"))
        self.assertTrue(promote.rstrip().endswith("COMMIT;"))

    def test_cli_writes_deterministic_sql_files(self):
        from build_dashboard_sql import write_sql_files

        root = Path(tempfile.mkdtemp())
        config_path = root / "dashboard.json"
        draft_path = root / "draft.sql"
        promote_path = root / "promote.sql"
        config_path.write_text(json.dumps(self.valid_config()), encoding="utf-8")
        write_sql_files(config_path, draft_path, promote_path)
        self.assertTrue(draft_path.read_text(encoding="utf-8").startswith("BEGIN IMMEDIATE;"))
        self.assertIn("version=7", promote_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
