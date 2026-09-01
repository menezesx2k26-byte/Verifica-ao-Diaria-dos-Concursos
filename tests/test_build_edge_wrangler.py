import unittest

from build_edge_wrangler import render_config


class BuildEdgeWranglerTests(unittest.TestCase):
    def test_replaces_all_database_placeholders_and_keeps_stable_worker_name(self):
        template = '''name = "concursos-watch-redundante"\ndatabase_id = "00000000-0000-4000-8000-000000000000"\n[env.staging]\nname = "concursos-watch-v4-staging"\ndatabase_id = "00000000-0000-4000-8000-000000000000"\n'''
        rendered = render_config(template, "12345678-1234-4234-9234-123456789abc")
        self.assertNotIn("00000000-0000-4000-8000-000000000000", rendered)
        self.assertEqual(rendered.count("12345678-1234-4234-9234-123456789abc"), 2)
        self.assertIn('name = "concursos-watch-redundante"', rendered)
        self.assertIn('name = "concursos-watch-v4-staging"', rendered)

    def test_rejects_invalid_database_id(self):
        with self.assertRaises(ValueError):
            render_config('database_id = "00000000-0000-4000-8000-000000000000"', "nope")


if __name__ == "__main__":
    unittest.main()
