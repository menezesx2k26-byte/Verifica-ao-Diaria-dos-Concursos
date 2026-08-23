import unittest
from datetime import date
from unittest.mock import patch

import new_contests as nc


class NewContestsParserTests(unittest.TestCase):
    def test_canonical_url_removes_tracking_and_fragment(self):
        value = nc.canonical_url("https://Example.com/a//b/?utm_source=x&foo=1#top")
        self.assertEqual(value, "https://example.com/a/b?foo=1")

    def test_stable_id_ignores_tracking(self):
        a = nc.stable_id("x", "https://a.gov.br/e?utm_source=x", "Edital 1")
        b = nc.stable_id("x", "https://a.gov.br/e", "Edital 1")
        self.assertEqual(a, b)

    def test_extracts_vacancies(self):
        self.assertEqual(nc.parse_vacancies("Concurso com 40 vagas e cadastro reserva"), "40")
        self.assertEqual(nc.parse_vacancies("Apenas cadastro reserva"), "CR")

    def test_classifies_profile(self):
        item = nc.classify("Inscrições abertas para professor de Matemática, nível superior", "")
        self.assertEqual(item["type"], "docência")
        self.assertEqual(item["area"], "Matemática")
        self.assertEqual(item["education"], "nível superior")
        self.assertEqual(item["status"], "open")

    def test_end_date_marks_closed_or_closing(self):
        yesterday = date.fromordinal(date.today().toordinal() - 1).isoformat()
        tomorrow = date.fromordinal(date.today().toordinal() + 1).isoformat()
        self.assertEqual(nc.classify("edital", yesterday)["status"], "closed")
        self.assertEqual(nc.classify("edital", tomorrow)["status"], "closing_soon")


if __name__ == "__main__":
    unittest.main()
