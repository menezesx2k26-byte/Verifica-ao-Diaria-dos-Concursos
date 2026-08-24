import unittest

import canonicalize_contests as canonical


class CanonicalizeContestsTests(unittest.TestCase):
    def test_same_notice_keeps_same_identity_across_title_and_url_changes(self):
        a = {
            "source_id": "ufsc",
            "title": "Concurso Público Edital 012/2026",
            "url": "https://example.edu.br/concursos/12-2026?utm_source=x",
        }
        b = {
            "source_id": "ufsc",
            "title": "Retificação — Edital nº 12/2026 — Concurso Público",
            "url": "https://example.edu.br/novo/endereco/edital-12-2026",
        }
        self.assertEqual(canonical.identity_key(a), canonical.identity_key(b))

    def test_collapse_items_emits_one_row_per_id_and_keeps_richer_fields(self):
        items = [
            {
                "id": "same",
                "title": "Concurso Público 012/2026",
                "organization": "UFSC",
                "url": "https://example.edu.br/a",
                "status": "detected",
                "education": "",
                "area": "",
                "priority": 50,
                "first_seen": "2026-08-20T00:00:00Z",
                "last_seen": "2026-08-21T00:00:00Z",
            },
            {
                "id": "same",
                "title": "Concurso Público 012/2026 — Assistente Administrativo",
                "organization": "Universidade Federal de Santa Catarina",
                "url": "https://example.edu.br/b",
                "status": "open",
                "education": "nível médio",
                "area": "Administrativo",
                "priority": 80,
                "first_seen": "2026-08-19T00:00:00Z",
                "last_seen": "2026-08-22T00:00:00Z",
            },
        ]

        collapsed = canonical.collapse_items(items)

        self.assertEqual(len(collapsed), 1)
        item = collapsed[0]
        self.assertEqual(item["id"], "same")
        self.assertEqual(item["status"], "open")
        self.assertEqual(item["education"], "nível médio")
        self.assertEqual(item["area"], "Administrativo")
        self.assertEqual(item["priority"], 80)
        self.assertEqual(item["first_seen"], "2026-08-19T00:00:00Z")
        self.assertEqual(item["last_seen"], "2026-08-22T00:00:00Z")

    def test_recalculate_new_count_uses_unique_current_ids(self):
        items = [{"id": "a"}, {"id": "a"}, {"id": "b"}]
        self.assertEqual(canonical.recalculate_new_count(items, {"a"}), 1)


if __name__ == "__main__":
    unittest.main()
