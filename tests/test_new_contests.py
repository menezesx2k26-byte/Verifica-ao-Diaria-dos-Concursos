import unittest
from datetime import date
from types import SimpleNamespace
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

    def test_historical_archive_entry_is_stale(self):
        self.assertTrue(nc.is_stale_candidate("Edital 141/07", "Edital antigo", "https://tjsc.jus.br/edital-141-2007", "detected", ""))

    def test_current_open_entry_is_not_stale(self):
        year = date.today().year
        self.assertFalse(nc.is_stale_candidate(f"Edital 10/{str(year)[-2:]}", "inscrições abertas", f"https://org.gov.br/edital-{year}", "open", ""))

    def test_candidate_pipeline_drops_procurement(self):
        html = """<main>
          <a href="/transparencia/licitacoes/pregao/55">AVISO PUBLICAÇÃO DE EDITAL - PREGÃO ELETRÔNICO nº 55/2026</a>
          <a href="/concursos/42">Concurso público para técnico administrativo - Edital 42/2026</a>
        </main>"""
        source = {
            "id": "example",
            "label": "Example — Concursos",
            "url": "https://example.gov.br/concursos",
            "city": "Teste",
            "region": "SC",
            "scope": "federal",
            "priority": 50,
        }
        fake = SimpleNamespace(text=html, url=source["url"])
        with patch.object(nc, "fetch", return_value=fake):
            items, decisions, _ = nc.candidate_links(source, ["edital", "concurso publico"], [])
        self.assertEqual(
            [x["title"] for x in items],
            ["Concurso público para técnico administrativo - Edital 42/2026"],
        )
        self.assertTrue(any(d["status"] == "REJECTED_PROCUREMENT" for d in decisions))


if __name__ == "__main__":
    unittest.main()
