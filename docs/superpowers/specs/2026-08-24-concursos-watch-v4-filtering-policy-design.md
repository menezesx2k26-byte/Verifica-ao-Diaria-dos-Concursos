# Concursos Watch v4 — Política de relevância, filtros e quarentena

Data: 2026-08-24
Status: aprovado em conversa; parte normativa da arquitetura v4.

Este documento complementa `2026-08-23-concursos-watch-hybrid-rust-dashboard-design.md` e é obrigatório para a implementação da v4.

## 1. Objetivo

Impedir que o Concursos Watch trate páginas genéricas, licitações, pregões, compras públicas, navegação institucional ou editais sem relação com recrutamento de pessoas como concursos, processos seletivos, estágios ou oportunidades de interesse.

A regra central é:

> Um item só entra no domínio `Contest` depois de provar que é uma oportunidade de recrutamento de pessoas.

Palavras genéricas como `edital`, `resultado`, `publicação`, `aviso` ou `processo` nunca são suficientes sozinhas.

## 2. Pipeline obrigatório de decisão

Todo candidato descoberto deve passar, nesta ordem, por cinco etapas:

```text
fonte oficial
  -> hard reject anti-lixo
  -> recruitment gate positivo
  -> classificação semântica
  -> filtro de interesse
  -> ACCEPTED / QUARANTINED / REJECTED
```

Nenhum item pode pular etapas.

## 3. Hard reject anti-lixo

Antes de qualquer classificação de concurso, o pipeline deve rejeitar conteúdos claramente pertencentes a outros domínios administrativos.

### 3.1 Termos proibidos de contratação pública

Exemplos mínimos:

```text
licitação
licitacao
pregão
pregao
concorrência
concorrencia
leilão
leilao
registro de preços
registro de precos
ata de registro de preços
ata de registro de precos
compras públicas
compras publicas
fornecedor
fornecedores
contratação de empresa
contratacao de empresa
aquisição
quisicao
contrato administrativo
dispensa de licitação
dispensa de licitacao
inexigibilidade
credenciamento de fornecedores
cotação
cotacao
termo de referência
termo de referencia
```

### 3.2 Sinais de URL proibidos

Exemplos mínimos:

```text
/licitacao/
/licitacoes/
/pregao/
/compras/
/fornecedor/
/fornecedores/
/contratos/
/aquisicoes/
/leilao/
```

Esses sinais têm precedência sobre o termo `edital`.

Exemplo:

```text
"AVISO DE LICITAÇÃO – PREGÃO ELETRÔNICO – EDITAL 95/2025"
```

Resultado obrigatório:

```text
REJECTED_PROCUREMENT
```

Nunca `Contest`.

## 4. Recruitment gate positivo

Depois de sobreviver ao hard reject, o candidato precisa apresentar evidência positiva de recrutamento de pessoas.

### 4.1 Sinais fortes

Exemplos:

```text
concurso público
concurso publico
processo seletivo
processo seletivo simplificado
seleção pública
selecao publica
emprego público
emprego publico
servidor
servidores
provimento de cargo
cargo efetivo
técnico-administrativo
tecnico-administrativo
professor
professor substituto
professor temporário
professor temporario
docente
estágio
estagio
estagiário
estagiario
residência
residencia
contratação temporária
contratacao temporaria
admissão de pessoal
admissao de pessoal
recrutamento
vagas para
inscrições para candidatos
inscricoes para candidatos
```

### 4.2 `edital` não é sinal forte

O termo `edital` isolado não pode mais abrir o gate.

Se o candidato contiver apenas termos administrativos genéricos, deve ser:

```text
REJECTED_NO_RECRUITMENT_SIGNAL
```

### 4.3 Navegação institucional

Links como:

```text
Presidência
Vice-Presidência
Corregedoria
Institucional
Contato
Transparência
Ouvidoria
```

não podem virar concursos apenas por estarem dentro de uma página oficial de concursos.

Resultado obrigatório quando não houver sinal positivo de recrutamento:

```text
REJECTED_NAVIGATION
```

## 5. Classificação semântica

Somente após o recruitment gate o item pode ser classificado como:

```text
concurso_publico
processo_seletivo
processo_seletivo_simplificado
estagio
residencia
contratacao_temporaria
docencia
emprego_publico
outro_recrutamento
```

A categoria genérica `edital` não deve existir como tipo final de concurso.

Se houver evidência de recrutamento, mas o subtipo estiver incerto, usar `outro_recrutamento` e marcar confiança reduzida.

## 6. Filtro de interesse configurável

Depois de confirmar que o item é recrutamento válido, aplicar o perfil de interesse.

O perfil deve ser declarativo, versionado e alterável sem novo APK.

Campos mínimos:

```text
regions
states
cities
scopes
education_levels
areas
contest_types
include_keywords
exclude_keywords
minimum_remuneration
open_only
priority_sources
exact_watches
```

Exemplo conceitual:

```json
{
  "regions": ["Brasil", "Sul", "SC", "Baixada Santista"],
  "scopes": ["federal", "estadual", "municipal"],
  "education_levels": ["médio", "técnico", "superior"],
  "areas": ["Administrativo", "Matemática", "Docência", "TI", "Mecatrônica", "Estágio"],
  "exclude_keywords": ["licitação", "pregão", "fornecedor"],
  "open_only": false
}
```

## 7. Exact watches

Acompanhamentos explícitos do usuário não dependem do filtro genérico de descoberta.

Eles são definidos por identidade canônica e regras dedicadas, por exemplo:

```text
contest_id
notice_number
year
organization
role
city
match_groups
event_types
```

O pipeline dedicado continua podendo observar convocação, nomeação, posse, homologação, reclassificação, atribuição e outros eventos mesmo quando o concurso já está fechado para novas inscrições.

## 8. Quarentena

Itens ambíguos não aparecem no aplicativo.

Estados obrigatórios:

```text
ACCEPTED
QUARANTINED_LOW_CONFIDENCE
REJECTED_PROCUREMENT
REJECTED_NAVIGATION
REJECTED_NO_RECRUITMENT_SIGNAL
REJECTED_INTEREST_FILTER
REJECTED_STALE
REJECTED_DUPLICATE
```

### 8.1 Política conservadora

Quando houver conflito entre sinais positivos e negativos e a confiança não alcançar o limite configurado, o resultado é:

```text
QUARANTINED_LOW_CONFIDENCE
```

Nunca publicar por dúvida.

### 8.2 Dados de auditoria

Cada decisão deve guardar:

```text
candidate_id
source_id
url
title
decision
confidence
positive_signals
negative_signals
matched_filters
rejected_filters
parser_version
checked_at
```

Isso permite explicar por que um item entrou, saiu ou foi colocado em quarentena.

## 9. Contrato entre Python, D1, Rust e Android

### Python

Responsável por descobrir, validar, classificar e decidir o estado do candidato.

Somente itens `ACCEPTED` entram na coleção operacional de concursos consumida pelo app.

Quarentena e rejeições ficam disponíveis apenas para diagnóstico e testes.

### D1

Deve separar logicamente:

```text
contests
candidate_decisions
quarantine
```

A API pública não lista quarentena por padrão.

### Rust

A API Rust deve servir apenas entidades `ACCEPTED` nas rotas normais de concursos.

Filtros de query da API nunca podem reabilitar um item previamente rejeitado pelo pipeline.

### Android

O Android recebe apenas concursos aceitos.

Filtros locais refinam o conjunto aceito; eles não são a primeira barreira anti-lixo.

## 10. Correção do legado atual

O estado atual contém falsos positivos de contratação pública e navegação institucional.

A migração v4 deve:

1. reprocessar o estado histórico com a nova política;
2. remover do feed operacional todos os itens de licitação/pregão/compras;
3. remover links institucionais sem recrutamento;
4. preservar IDs canônicos apenas dos concursos válidos;
5. não disparar notificações de remoção em massa;
6. registrar a limpeza como migração de qualidade de dados.

## 11. Fixtures de regressão obrigatórias

Os falsos positivos já observados no repositório devem virar fixtures permanentes de teste.

Exemplos obrigatórios de rejeição:

```text
AVISO DE LICITAÇÃO – REPUBLICAÇÃO DO PREGÃO ELETRÔNICO nº 23/2025
ERRATA DO EDITAL - PREGÃO ELETRÔNICO nº 76/2025
AVISO DE LICITAÇÃO – EDITAL nº 95/2025
AVISO DE RETIFICAÇÃO DE EDITAL DE LICITAÇÃO – PREGÃO ELETRÔNICO nº 99/2025RR
AVISO PUBLICAÇÃO DE EDITAL - PREGÃO ELETRÔNICO nº 55/2026
Presidência
Vice-Presidência
Corregedoria-Geral da Justiça
```

Resultados esperados:

```text
pregões/licitações -> REJECTED_PROCUREMENT
links institucionais -> REJECTED_NAVIGATION ou REJECTED_NO_RECRUITMENT_SIGNAL
```

Fixtures positivas também são obrigatórias para evitar overblocking:

```text
Concurso Público para servidores
Processo Seletivo Simplificado
Professor Substituto
Técnico-Administrativo em Educação
Seleção de Estagiários
Residência
```

## 12. Testes obrigatórios

### Unitários

- `edital` isolado não abre recruitment gate;
- `edital + pregão` é rejeitado;
- URL `/licitacoes/` é rejeitada mesmo contendo `edital`;
- `processo seletivo` válido passa;
- `professor substituto` passa;
- navegação institucional é rejeitada;
- sinais acentuados e não acentuados têm comportamento equivalente;
- filtros de interesse funcionam independentemente do gate de recrutamento;
- exact watch não é descartado porque inscrições fecharam;
- item ambíguo vai para quarentena.

### Integração

- crawl de página mista não mistura licitações com concursos;
- D1 recebe apenas `ACCEPTED` em `contests`;
- Rust nunca retorna quarentena em `/api/v1/contests`;
- Android nunca recebe falsos positivos conhecidos;
- migração do estado legado não gera tempestade de notificações.

### Anti-ruído

O CI deve falhar se qualquer fixture conhecida de licitação reaparecer como concurso.

## 13. Observabilidade de qualidade

A automação deve publicar métricas internas como:

```text
candidates_seen
accepted_count
quarantined_count
rejected_procurement_count
rejected_navigation_count
rejected_no_recruitment_count
rejected_interest_count
false_positive_regression_count
```

`false_positive_regression_count > 0` bloqueia publicação.

## 14. Critérios de aceite

A política só é considerada concluída quando:

1. nenhum exemplo conhecido de pregão/licitação entra como concurso;
2. `edital` isolado não é suficiente para inclusão;
3. links institucionais genéricos não entram no feed;
4. concursos reais continuam passando;
5. itens ambíguos ficam invisíveis ao usuário em quarentena;
6. filtros específicos podem ser alterados sem novo APK;
7. exact watches continuam funcionando mesmo fora do período de inscrição;
8. decisões são auditáveis;
9. CI contém regressões negativas e positivas;
10. limpeza do legado não dispara alertas falsos.

## 15. Decisão final

A v4 adota abordagem conservadora:

> Precisão primeiro. Cobertura duvidosa vai para quarentena; falsos positivos não chegam ao usuário.
