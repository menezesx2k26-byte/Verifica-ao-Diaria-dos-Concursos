#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
import re
import unicodedata
from urllib.parse import urlparse


ACCEPTED = "ACCEPTED"
QUARANTINED_LOW_CONFIDENCE = "QUARANTINED_LOW_CONFIDENCE"
REJECTED_PROCUREMENT = "REJECTED_PROCUREMENT"
REJECTED_NAVIGATION = "REJECTED_NAVIGATION"
REJECTED_NO_RECRUITMENT_SIGNAL = "REJECTED_NO_RECRUITMENT_SIGNAL"

STATUSES = {
    ACCEPTED,
    QUARANTINED_LOW_CONFIDENCE,
    REJECTED_PROCUREMENT,
    REJECTED_NAVIGATION,
    REJECTED_NO_RECRUITMENT_SIGNAL,
}


@dataclass(frozen=True)
class RelevanceDecision:
    status: str
    reason: str
    confidence: int
    positive_signals: tuple[str, ...] = ()
    negative_signals: tuple[str, ...] = ()


def fold_relevance(text: str) -> str:
    value = unicodedata.normalize("NFKD", text or "")
    value = "".join(c for c in value if not unicodedata.combining(c)).casefold()
    return re.sub(r"\s+", " ", value).strip()


PROCUREMENT_TERMS = (
    "licitacao",
    "pregao",
    "registro de precos",
    "inexigibilidade",
    "dispensa de licitacao",
    "fornecedor",
    "credenciamento de fornecedor",
    "aquisicao",
    "fornecimento",
    "contratacao de empresa",
    "prestacao de servico",
    "leilao",
    "compras publicas",
)
PROCUREMENT_PATHS = (
    "/licitacoes/",
    "/licitacao/",
    "/pregao/",
    "/compras/",
    "/fornecedores/",
)
NAVIGATION_TERMS = (
    "presidencia",
    "vice-presidencia",
    "corregedoria",
    "quem somos",
    "secao de direito",
    "decanato",
    "institucional",
)
NAVIGATION_PATHS = (
    "/quemsomos/",
    "/presidencia",
    "/vicepresidencia",
    "/corregedoria",
    "/decanato",
    "/links/index",
)
RECRUITMENT_TERMS = (
    "concurso publico",
    "processo seletivo",
    "processo seletivo simplificado",
    "selecao de servidor",
    "selecao de servidores",
    "tecnico-administrativo",
    "tecnico administrativo",
    "professor substituto",
    "professor temporario",
    "professor visitante",
    "docente",
    "selecao de estagiarios",
    "estagio",
    "estagiario",
    "residencia",
    "emprego publico",
    "contratacao temporaria de pessoal",
    "admissao de efetivos",
)
AMBIGUOUS_PERSONNEL_TERMS = (
    "aviso de selecao",
    "selecao temporaria",
    "selecao simplificada",
    "contratacao temporaria",
    "cadastro de candidatos",
)


def _contains_term(corpus: str, term: str) -> bool:
    return re.search(rf"(?<!\w){re.escape(term)}(?!\w)", corpus) is not None


def _hits(corpus: str, terms: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(term for term in terms if _contains_term(corpus, term))


def evaluate_candidate(title: str, context: str, url: str) -> RelevanceDecision:
    path = fold_relevance(urlparse(url).path)
    corpus = fold_relevance(f"{title} {context} {path}")

    procurement_hits = _hits(corpus, PROCUREMENT_TERMS)
    procurement_path_hits = tuple(p for p in PROCUREMENT_PATHS if p in path)
    if procurement_hits or procurement_path_hits:
        negatives = tuple(dict.fromkeys(procurement_hits + procurement_path_hits))
        return RelevanceDecision(
            status=REJECTED_PROCUREMENT,
            reason="contratação pública de bens/serviços detectada",
            confidence=100,
            negative_signals=negatives,
        )

    navigation_hits = _hits(corpus, NAVIGATION_TERMS)
    navigation_path_hits = tuple(p for p in NAVIGATION_PATHS if p in path)
    positive_hits = _hits(corpus, RECRUITMENT_TERMS)
    if (navigation_hits or navigation_path_hits) and not positive_hits:
        negatives = tuple(dict.fromkeys(navigation_hits + navigation_path_hits))
        return RelevanceDecision(
            status=REJECTED_NAVIGATION,
            reason="link institucional ou de navegação sem recrutamento",
            confidence=98,
            negative_signals=negatives,
        )

    if positive_hits:
        strength = 90
        if any(x in positive_hits for x in ("concurso publico", "processo seletivo", "processo seletivo simplificado")):
            strength = 98
        return RelevanceDecision(
            status=ACCEPTED,
            reason="sinal explícito de seleção ou recrutamento de pessoas",
            confidence=strength,
            positive_signals=positive_hits,
        )

    ambiguous_hits = _hits(corpus, AMBIGUOUS_PERSONNEL_TERMS)
    if ambiguous_hits:
        return RelevanceDecision(
            status=QUARANTINED_LOW_CONFIDENCE,
            reason="possível seleção de pessoas sem evidência suficiente",
            confidence=55,
            positive_signals=ambiguous_hits,
        )

    return RelevanceDecision(
        status=REJECTED_NO_RECRUITMENT_SIGNAL,
        reason="nenhum sinal positivo de recrutamento foi encontrado",
        confidence=95,
    )


def _fold_values(values) -> set[str]:
    return {fold_relevance(str(v)) for v in (values or []) if str(v).strip()}


def matches_interest_profile(item: dict, profile: dict) -> bool:
    if not profile:
        return True

    title_corpus = fold_relevance(" ".join(str(item.get(k, "")) for k in ("title", "organization", "city", "area", "type")))

    excluded = _fold_values(profile.get("exclude_keywords"))
    if any(_contains_term(title_corpus, term) for term in excluded):
        return False

    included = _fold_values(profile.get("include_keywords"))
    if included and not any(_contains_term(title_corpus, term) for term in included):
        return False

    allowed_scopes = _fold_values(profile.get("scope"))
    if allowed_scopes and fold_relevance(str(item.get("scope", ""))) not in allowed_scopes:
        return False

    allowed_regions = _fold_values(profile.get("regions"))
    if allowed_regions:
        item_regions = {
            fold_relevance(str(item.get("region", ""))),
            fold_relevance(str(item.get("uf", ""))),
            fold_relevance(str(item.get("city", ""))),
        }
        if not any(
            allowed == candidate or (allowed and allowed in candidate)
            for allowed in allowed_regions
            for candidate in item_regions
        ):
            return False

    allowed_education = _fold_values(profile.get("education"))
    if allowed_education and fold_relevance(str(item.get("education", ""))) not in allowed_education:
        return False

    allowed_areas = _fold_values(profile.get("areas"))
    if allowed_areas and fold_relevance(str(item.get("area", ""))) not in allowed_areas:
        return False

    allowed_types = _fold_values(profile.get("types"))
    if allowed_types and fold_relevance(str(item.get("type", ""))) not in allowed_types:
        return False

    return True
