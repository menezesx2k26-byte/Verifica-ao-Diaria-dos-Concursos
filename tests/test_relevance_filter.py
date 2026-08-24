import json
import unittest
from pathlib import Path

from relevance_filter import evaluate_candidate, matches_interest_profile


CASES_PATH = Path("tests/fixtures/relevance_cases.json")


class RelevanceFilterTests(unittest.TestCase):
    def test_rejects_procurement_even_when_edital_is_present(self):
        d = evaluate_candidate(
            "AVISO DE LICITAÇÃO – PREGÃO ELETRÔNICO nº 23/2025 – EDITAL nº 23/2025R",
            "aquisição de materiais por pregão eletrônico",
            "https://example.gov.br/transparencia/licitacoes/pregao/23-2025",
        )
        self.assertEqual(d.status, "REJECTED_PROCUREMENT")

    def test_rejects_navigation_link(self):
        d = evaluate_candidate(
            "Presidência",
            "Institucional Quem Somos",
            "https://example.jus.br/QuemSomos/Presidencia",
        )
        self.assertEqual(d.status, "REJECTED_NAVIGATION")

    def test_rejects_navigation_even_when_page_context_mentions_recruitment(self):
        d = evaluate_candidate(
            "Institucional",
            "TJSC concurso público edital inscrições abertas",
            "https://www.tjsc.jus.br/institucional",
        )
        self.assertEqual(d.status, "REJECTED_NAVIGATION")

    def test_edital_alone_is_not_recruitment(self):
        d = evaluate_candidate(
            "Edital nº 42/2026",
            "publicação do edital",
            "https://example.gov.br/edital/42",
        )
        self.assertEqual(d.status, "REJECTED_NO_RECRUITMENT_SIGNAL")

    def test_accepts_real_recruitment(self):
        d = evaluate_candidate(
            "Processo Seletivo para Professor Visitante – Edital 150/2026",
            "inscrições abertas para professor visitante; 2 vagas",
            "https://concursos.example.edu.br/150-2026",
        )
        self.assertEqual(d.status, "ACCEPTED")
        self.assertGreaterEqual(d.confidence, 80)

    def test_quarantines_ambiguous_personnel_notice(self):
        d = evaluate_candidate(
            "Aviso de seleção 12/2026",
            "seleção temporária",
            "https://example.gov.br/avisos/12-2026",
        )
        self.assertEqual(d.status, "QUARANTINED_LOW_CONFIDENCE")

    def test_regression_fixture(self):
        cases = json.loads(CASES_PATH.read_text(encoding="utf-8"))
        for case in cases["reject"]:
            with self.subTest(case=case):
                d = evaluate_candidate(case["title"], case["context"], case["url"])
                self.assertEqual(d.status, case["status"])
        for case in cases["accept"]:
            with self.subTest(case=case):
                d = evaluate_candidate(case["title"], case["context"], case["url"])
                self.assertEqual(d.status, "ACCEPTED")

    def test_interest_profile_accepts_sc_federal_middle_level(self):
        item = {
            "scope": "federal",
            "uf": "SC",
            "region": "SC",
            "education": "nível médio",
            "area": "Administrativo",
            "type": "concurso público",
            "title": "Concurso público para assistente administrativo",
        }
        profile = {
            "scope": ["federal"],
            "regions": ["SC"],
            "education": ["nível médio"],
            "areas": [],
            "types": [],
            "include_keywords": [],
            "exclude_keywords": [],
        }
        self.assertTrue(matches_interest_profile(item, profile))

    def test_interest_profile_rejects_excluded_keyword(self):
        item = {
            "scope": "federal",
            "uf": "SC",
            "region": "SC",
            "education": "nível superior",
            "area": "Docência",
            "type": "processo seletivo",
            "title": "Processo seletivo para professor de odontologia",
        }
        profile = {
            "scope": ["federal"],
            "regions": ["SC"],
            "education": [],
            "areas": [],
            "types": [],
            "include_keywords": [],
            "exclude_keywords": ["odontologia"],
        }
        self.assertFalse(matches_interest_profile(item, profile))


if __name__ == "__main__":
    unittest.main()
