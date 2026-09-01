# Concursos Watch v4 Filtering Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** impedir que licitações, pregões, navegação institucional e outros itens sem sinal real de recrutamento cheguem ao feed, adicionando quarentena e filtros específicos de interesse.

**Architecture:** a coleta continua ampla, mas todo candidato passa por um classificador puro e testável antes de virar `Contest`. A decisão é fail-closed: somente `ACCEPTED` entra em `state/new_contests.json`; rejeitados e itens de baixa confiança são gravados em diagnóstico separado. O perfil de interesse é aplicado depois da validação de domínio, sem interferir na watchlist prioritária.

**Tech Stack:** Python 3.12+, pytest/unittest atual do repo, BeautifulSoup, JSON versionado.

**Spec:** `docs/superpowers/specs/2026-08-24-concursos-watch-v4-final-design.md`

## Global Constraints

- `edital` isolado nunca é sinal positivo de concurso.
- `REJECTED_PROCUREMENT`, `REJECTED_NAVIGATION`, `REJECTED_NO_RECRUITMENT_SIGNAL`, `QUARANTINED_LOW_CONFIDENCE`, `ACCEPTED` são os estados canônicos.
- Somente `ACCEPTED` entra no feed operacional.
- Watchlist exata não depende dos filtros genéricos de interesse.
- Em dúvida, quarentena; nunca promover por heurística fraca.
- O filtro deve ser determinístico e não depender de rede.

---

### Task 1: Extrair classificador de relevância puro

**Files:**
- Create: `relevance_filter.py`
- Test: `tests/test_relevance_filter.py`

**Interfaces:**
- Produces: `RelevanceDecision(status: str, reason: str, confidence: int, positive_signals: tuple[str, ...], negative_signals: tuple[str, ...])`
- Produces: `evaluate_candidate(title: str, context: str, url: str) -> RelevanceDecision`
- Produces: `fold_relevance(text: str) -> str`

- [ ] **Step 1: Write failing tests for procurement and navigation**

```python
from relevance_filter import evaluate_candidate


def test_rejects_procurement_even_when_edital_is_present():
    d = evaluate_candidate(
        "AVISO DE LICITAÇÃO – PREGÃO ELETRÔNICO nº 23/2025 – EDITAL nº 23/2025R",
        "aquisição de materiais por pregão eletrônico",
        "https://example.gov.br/transparencia/licitacoes/pregao/23-2025",
    )
    assert d.status == "REJECTED_PROCUREMENT"


def test_rejects_navigation_link():
    d = evaluate_candidate("Presidência", "Institucional Quem Somos", "https://example.jus.br/QuemSomos/Presidencia")
    assert d.status == "REJECTED_NAVIGATION"


def test_edital_alone_is_not_recruitment():
    d = evaluate_candidate("Edital nº 42/2026", "publicação do edital", "https://example.gov.br/edital/42")
    assert d.status == "REJECTED_NO_RECRUITMENT_SIGNAL"
```

- [ ] **Step 2: Run tests and verify failure**

Run: `python -m pytest tests/test_relevance_filter.py -q`

Expected: FAIL because `relevance_filter` does not exist.

- [ ] **Step 3: Implement minimal decision model and hard-deny rules**

```python
from dataclasses import dataclass
from urllib.parse import urlparse
import re
import unicodedata

STATUSES = {
    "ACCEPTED",
    "QUARANTINED_LOW_CONFIDENCE",
    "REJECTED_PROCUREMENT",
    "REJECTED_NAVIGATION",
    "REJECTED_NO_RECRUITMENT_SIGNAL",
}

@dataclass(frozen=True)
class RelevanceDecision:
    status: str
    reason: str
    confidence: int
    positive_signals: tuple[str, ...] = ()
    negative_signals: tuple[str, ...] = ()

PROCUREMENT_TERMS = (
    "licitacao", "pregao", "registro de precos", "inexigibilidade",
    "dispensa de licitacao", "fornecedor", "aquisicao", "fornecimento",
    "contratacao de empresa", "leilao",
)
PROCUREMENT_PATHS = ("/licitacoes/", "/pregao/", "/compras/", "/fornecedores/")
NAVIGATION_TERMS = (
    "presidencia", "vice-presidencia", "corregedoria", "quem somos",
    "secao de direito", "decanato", "institucional",
)
RECRUITMENT_TERMS = (
    "concurso publico", "processo seletivo", "processo seletivo simplificado",
    "tecnico-administrativo", "tecnico administrativo", "professor substituto",
    "professor temporario", "docente", "estagio", "estagiario", "residencia",
    "emprego publico", "contratacao temporaria de pessoal",
)
```

Implement `evaluate_candidate()` with precedence: procurement -> navigation -> positive recruitment -> quarantine/no-signal.

- [ ] **Step 4: Add positive and quarantine tests**

```python
def test_accepts_real_recruitment():
    d = evaluate_candidate(
        "Processo Seletivo para Professor Visitante – Edital 150/2026",
        "inscrições abertas para professor visitante; 2 vagas",
        "https://concursos.example.edu.br/150-2026",
    )
    assert d.status == "ACCEPTED"
    assert d.confidence >= 80


def test_quarantines_ambiguous_personnel_notice():
    d = evaluate_candidate(
        "Aviso de seleção 12/2026",
        "seleção temporária",
        "https://example.gov.br/avisos/12-2026",
    )
    assert d.status == "QUARANTINED_LOW_CONFIDENCE"
```

- [ ] **Step 5: Run focused tests**

Run: `python -m pytest tests/test_relevance_filter.py -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add relevance_filter.py tests/test_relevance_filter.py
git commit -m "feat(filter): add fail-closed recruitment classifier"
```

### Task 2: Transformar os falsos positivos atuais em fixtures permanentes

**Files:**
- Create: `tests/fixtures/relevance_cases.json`
- Modify: `tests/test_relevance_filter.py`

**Interfaces:**
- Consumes: `evaluate_candidate()` from Task 1.
- Produces: regression corpus used by CI.

- [ ] **Step 1: Create regression fixture**

```json
{
  "reject": [
    {
      "title": "AVISO DE LICITAÇÃO – PREGÃO ELETRÔNICO nº. 23/2025 – EDITAL nº. 23/2025R",
      "context": "aquisição e fornecimento por pregão eletrônico",
      "url": "https://www.saovicente.sp.gov.br/transparencia/licitacoes/pregao/pregao-eletronico-no-23-2025",
      "status": "REJECTED_PROCUREMENT"
    },
    {
      "title": "ERRATA DO EDITAL - PREGÃO ELETRÔNICO nº. 76/2025",
      "context": "errata de licitação",
      "url": "https://www.saovicente.sp.gov.br/transparencia/licitacoes/pregao/76-2025",
      "status": "REJECTED_PROCUREMENT"
    },
    {
      "title": "Presidência",
      "context": "Institucional",
      "url": "https://www.tjsp.jus.br/QuemSomos/Presidencia",
      "status": "REJECTED_NAVIGATION"
    }
  ],
  "accept": [
    {
      "title": "Processo Seletivo para Professor Visitante – Edital nº 150/2026/DDP",
      "context": "processo seletivo para professor visitante; inscrições abertas",
      "url": "https://concursos.ufsc.br/2026/06/26/processo-seletivo-para-professor-visitante-edital-no-1502026ddp"
    },
    {
      "title": "Edital Técnico-Administrativo em Educação",
      "context": "concurso público para técnico-administrativo em educação",
      "url": "https://concursos.ufsc.br/editais-tecnico-administrativo-em-educacao"
    }
  ]
}
```

- [ ] **Step 2: Add parametrized regression test**

```python
import json
from pathlib import Path

CASES = json.loads(Path("tests/fixtures/relevance_cases.json").read_text(encoding="utf-8"))


def test_regression_rejections():
    for case in CASES["reject"]:
        d = evaluate_candidate(case["title"], case["context"], case["url"])
        assert d.status == case["status"], case


def test_regression_accepts():
    for case in CASES["accept"]:
        d = evaluate_candidate(case["title"], case["context"], case["url"])
        assert d.status == "ACCEPTED", case
```

- [ ] **Step 3: Run tests**

Run: `python -m pytest tests/test_relevance_filter.py -q`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add tests/fixtures/relevance_cases.json tests/test_relevance_filter.py
git commit -m "test(filter): lock known junk out of contest feed"
```

### Task 3: Integrar filtro ao crawler genérico

**Files:**
- Modify: `new_contests.py`
- Modify: `config/new_contests_sources.json`
- Test: `tests/test_new_contests.py`

**Interfaces:**
- Consumes: `evaluate_candidate()`.
- Produces: `candidate_links()` returning only accepted contest candidates plus decisions for diagnostics.

- [ ] **Step 1: Write failing integration test**

```python
def test_candidate_pipeline_drops_procurement(monkeypatch):
    html = '''<main>
      <a href="/transparencia/licitacoes/pregao/55">AVISO PUBLICAÇÃO DE EDITAL - PREGÃO ELETRÔNICO nº 55/2026</a>
      <a href="/concursos/42">Concurso público para técnico administrativo - Edital 42/2026</a>
    </main>'''
    # monkeypatch fetch() to return this HTML using the test helper already present in this test file.
    items, _ = new_contests.candidate_links(TEST_SOURCE, ["edital", "concurso publico"], [])
    assert [x["title"] for x in items] == ["Concurso público para técnico administrativo - Edital 42/2026"]
```

- [ ] **Step 2: Run and verify failure**

Run: `python -m pytest tests/test_new_contests.py -q`

Expected: FAIL because current `candidate_links()` accepts `edital` before domain validation.

- [ ] **Step 3: Apply decision before `classify()`/append**

Inside `candidate_links()`:

```python
from relevance_filter import evaluate_candidate

# ... after clean_title/context/href are available
relevance = evaluate_candidate(clean_title, context, href)
if relevance.status != "ACCEPTED":
    decisions.append({
        "source_id": source["id"],
        "title": clean_title,
        "url": href,
        "status": relevance.status,
        "reason": relevance.reason,
        "confidence": relevance.confidence,
    })
    continue
```

Change return signature to:

```python
def candidate_links(...) -> tuple[list[dict], list[dict], str]:
    ...
    return out, decisions, r.url
```

Update callers accordingly.

- [ ] **Step 4: Tighten broad include terms**

In `config/new_contests_sources.json`, remove bare `"edital"` as a standalone broad include term. Keep explicit recruitment phrases such as `"edital de abertura"` only when combined with recruitment context via `evaluate_candidate()`.

Add server-maintained arrays:

```json
"interest_profile": {
  "scope": ["federal", "estadual", "municipal"],
  "regions": ["Brasil", "SC", "Sul", "SP", "Baixada"],
  "education": ["nível médio", "nível técnico", "nível superior", ""],
  "areas": [],
  "types": [],
  "include_keywords": [],
  "exclude_keywords": []
}
```

- [ ] **Step 5: Run crawler tests**

Run: `python -m pytest tests/test_new_contests.py tests/test_relevance_filter.py -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add new_contests.py config/new_contests_sources.json tests/test_new_contests.py
git commit -m "fix(filter): gate generic crawler on recruitment relevance"
```

### Task 4: Persistir quarentena sem contaminar o feed

**Files:**
- Modify: `new_contests.py`
- Create: `state/relevance_diagnostics.json` (generated baseline may be committed once)
- Test: `tests/test_new_contests.py`

**Interfaces:**
- Produces: `state/relevance_diagnostics.json` schema version 1.

- [ ] **Step 1: Add failing diagnostic-state test**

```python
def test_state_contains_only_accepted_and_diagnostics_hold_rejections(tmp_path, monkeypatch):
    # arrange one procurement and one recruitment candidate
    # run main() with NEW_CONTESTS_STATE and RELEVANCE_DIAGNOSTICS_STATE redirected to tmp_path
    feed = json.loads((tmp_path / "feed.json").read_text())
    diag = json.loads((tmp_path / "diag.json").read_text())
    assert all(i.get("relevance_status", "ACCEPTED") == "ACCEPTED" for i in feed["items"])
    assert any(i["status"] == "REJECTED_PROCUREMENT" for i in diag["items"])
```

- [ ] **Step 2: Implement diagnostics writer**

Add:

```python
DIAGNOSTICS = Path(os.getenv("RELEVANCE_DIAGNOSTICS_STATE", "state/relevance_diagnostics.json"))
```

Write schema:

```python
diagnostics = {
    "schema_version": 1,
    "updated_at": stamp,
    "counts": dict(Counter(d["status"] for d in decisions)),
    "items": decisions[-1000:],
}
save(DIAGNOSTICS, diagnostics)
```

Accepted items should include `relevance_status="ACCEPTED"` and `relevance_confidence` for auditability.

- [ ] **Step 3: Run tests**

Run: `python -m pytest tests/test_new_contests.py tests/test_relevance_filter.py -q`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add new_contests.py state/relevance_diagnostics.json tests/test_new_contests.py
git commit -m "feat(filter): quarantine ambiguous and rejected discoveries"
```

### Task 5: Implementar perfil de interesse sem afetar watchlist

**Files:**
- Modify: `relevance_filter.py`
- Modify: `new_contests.py`
- Test: `tests/test_relevance_filter.py`

**Interfaces:**
- Produces: `matches_interest_profile(item: dict, profile: dict) -> bool`

- [ ] **Step 1: Write failing profile tests**

```python
def test_interest_profile_accepts_sc_federal_middle_level():
    item = {"scope": "federal", "uf": "SC", "region": "SC", "education": "nível médio", "area": "Administrativo", "type": "concurso público", "title": "Técnico administrativo"}
    profile = {"scope": ["federal"], "regions": ["SC"], "education": ["nível médio"], "areas": [], "types": [], "include_keywords": [], "exclude_keywords": []}
    assert matches_interest_profile(item, profile)


def test_exclude_keyword_wins():
    item = {"scope": "federal", "uf": "SC", "region": "SC", "education": "nível superior", "area": "Docência", "type": "docência", "title": "Professor de Odontologia"}
    profile = {"scope": [], "regions": [], "education": [], "areas": [], "types": [], "include_keywords": [], "exclude_keywords": ["odontologia"]}
    assert not matches_interest_profile(item, profile)
```

- [ ] **Step 2: Implement deterministic profile matcher**

Rules:

```python
# empty list means no restriction for that field
# exclude_keywords always wins
# include_keywords, when non-empty, require at least one match
# region matches uf OR region OR named aliases resolved in one helper
# this matcher is NOT called by priority_scanner.py for exact watches
```

- [ ] **Step 3: Apply profile after contest classification**

In `new_contests.py`, after `classify()` and structured fields are assembled, call `matches_interest_profile()`. Items that are valid recruitment but outside the profile should be diagnosed as `FILTERED_INTEREST_PROFILE`, not as invalid domain. This status is diagnostic-only and is not one of the five domain decisions.

- [ ] **Step 4: Run tests**

Run: `python -m pytest tests/test_relevance_filter.py tests/test_new_contests.py -q`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add relevance_filter.py new_contests.py tests/test_relevance_filter.py
git commit -m "feat(filter): add configurable contest interest profile"
```

### Task 6: Wire filtering regression gate into CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Python test suite.
- Produces: hard CI gate preventing known junk from re-entering.

- [ ] **Step 1: Add explicit relevance test step**

```yaml
- name: Relevance anti-noise regression
  run: |
    python -m pytest tests/test_relevance_filter.py tests/test_new_contests.py -q
```

- [ ] **Step 2: Add static guard against bare `edital` broad matching**

```yaml
- name: Guard broad contest terms
  run: |
    python - <<'PY'
    import json
    cfg = json.load(open('config/new_contests_sources.json', encoding='utf-8'))
    assert 'edital' not in [x.casefold().strip() for x in cfg.get('include_terms', [])]
    PY
```

- [ ] **Step 3: Run the same commands locally/Actions**

Run: `python -m pytest tests/test_relevance_filter.py tests/test_new_contests.py -q`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(filter): block procurement and navigation regressions"
```
