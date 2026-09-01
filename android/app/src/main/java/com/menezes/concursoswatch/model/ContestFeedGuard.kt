package com.menezes.concursoswatch.model

import java.text.Normalizer

object ContestFeedGuard {
    private val procurementTerms = listOf(
        "licitacao",
        "pregao",
        "registro de precos",
        "inexigibilidade",
        "dispensa de licitacao",
        "fornecedor",
        "aquisicao",
        "fornecimento",
        "contratacao de empresa",
        "prestacao de servico",
        "leilao",
        "compras publicas",
    )

    private val procurementPaths = listOf(
        "/licitacoes/",
        "/licitacao/",
        "/pregao/",
        "/compras/",
        "/fornecedores/",
    )

    private val navigationTerms = listOf(
        "institucional",
        "presidencia",
        "vice-presidencia",
        "corregedoria",
        "quem somos",
        "secao de direito",
        "decanato",
    )

    private val navigationPaths = listOf(
        "/institucional",
        "/quemsomos/",
        "/presidencia",
        "/vicepresidencia",
        "/corregedoria",
        "/decanato",
        "/links/index",
    )

    private val recruitmentTerms = listOf(
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

    fun accepts(contest: Contest): Boolean {
        val title = fold(contest.title)
        val url = fold(contest.url)
        val type = fold(contest.type)
        val source = fold(contest.source)
        val organization = fold(contest.organization)
        val corpus = "$title $url $type $source $organization"

        if (procurementTerms.any { containsTerm(corpus, it) }) return false
        if (procurementPaths.any { it in url }) return false

        if (navigationTerms.any { containsTerm(title, it) }) return false
        if (navigationPaths.any { it in url }) return false

        return recruitmentTerms.any { containsTerm(corpus, it) }
    }

    private fun fold(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsTerm(corpus: String, term: String): Boolean {
        val pattern = Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])")
        return pattern.containsMatchIn(corpus)
    }
}
