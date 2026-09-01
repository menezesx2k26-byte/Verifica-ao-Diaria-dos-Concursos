# Concursos Watch v4 — Design final

Data: 2026-08-24
Status: APROVADO

Este documento supersede `docs/superpowers/specs/2026-08-23-concursos-watch-hybrid-rust-dashboard-design.md` e incorpora a regra aprovada de filtragem em camadas, quarentena e perfil de interesse.

## 1. Objetivo

Evoluir o Concursos Watch para uma arquitetura híbrida em que:

- Rust é a única camada pública de leitura exposta à internet;
- Python executa coleta, parsing, PDFs, normalização, classificação, deduplicação e automações;
- somente a Home é um painel dinâmico em HTML/CSS;
- o restante do Android permanece nativo;
- alterações frequentes de conteúdo, composição, filtros e estilo da Home não exigem novo APK;
- nenhuma atualização dinâmica executa JavaScript remoto;
- itens irrelevantes, licitações e navegação institucional nunca chegam ao app como concursos.

## 2. Princípios fixos

1. Rust é a única camada pública consumida pelo APK.
2. Python não atende tráfego público e não expõe endpoint público de escrita.
3. Alertas, Concursos, Favoritos, Detalhes, Configurações, Room, WorkManager, notificações, busca e deep links permanecem nativos.
4. A Home dinâmica usa HTML/CSS sem JavaScript.
5. A WebView não acessa a internet diretamente: renderiza somente bundle previamente baixado, validado e cacheado pelo código nativo.
6. Toda publicação dinâmica passa por CI, validação de segurança e QA visual automatizado obrigatório.
7. O app mantém `last_known_good` e continua utilizável offline.
8. O servidor pode escolher conteúdo e composição dentro de contratos conhecidos; nunca envia código executável.
9. Entrada proveniente de órgãos públicos é sempre considerada não confiável até ser normalizada e validada.
10. Em dúvida, o sistema falha fechado: item incerto vai para quarentena, não para o usuário.

## 3. Arquitetura

```text
Fontes oficiais
      |
      v
Python collectors / parsers / PDF scanners
      |
      v
Filtro de domínio + normalização + health + deduplicação
      |
      +--> rejeitados / quarentena (diagnóstico interno)
      |
      v
Snapshot aceito
      |
      v
GitHub Actions com credencial Cloudflare restrita
      |
      v
D1 canônico
      |
      v
Rust Worker/WASM read-only
      |
      +------------------------------+
      |                              |
      v                              v
/api/v1/*                    dashboard bundle
JSON nativo                  manifest + HTML + CSS
      |                              |
      +---------------+--------------+
                      |
                      v
             Android native HTTP
                      |
          valida schema/host/hash/MIME
                      |
          +-----------+-----------+
          |                       |
          v                       v
      Room/telas             bundle privado
       nativas                    |
                                 v
                          WebView local
                          sem rede / sem JS
```

## 4. Filtragem de relevância — regra obrigatória

O pipeline não pode considerar `edital` como evidência suficiente de concurso.

### 4.1 Camada 1 — rejeição dura de domínio

Antes da classificação, rejeitar candidatos cujo título, contexto ou URL indiquem contratação pública de bens/serviços ou navegação institucional.

Categorias mínimas de rejeição:

- licitação;
- pregão / pregão eletrônico;
- concorrência pública de compras;
- registro de preços;
- dispensa ou inexigibilidade de licitação;
- fornecedor / credenciamento de fornecedor;
- aquisição, compra ou fornecimento;
- contratação de empresa ou prestação de serviço;
- leilão;
- chamamento relacionado a compras/fornecedores;
- URLs como `/licitacoes/`, `/pregao/`, `/compras/`, `/fornecedores/` quando não houver sinal inequívoco de seleção de pessoas;
- links meramente institucionais, menu, presidência, corregedoria, seções administrativas e navegação genérica.

Motivos padronizados:

```text
REJECTED_PROCUREMENT
REJECTED_NAVIGATION
```

### 4.2 Camada 2 — gate positivo de recrutamento

Depois da rejeição dura, o item só pode ser aceito se houver evidência positiva de seleção/recrutamento de pessoas.

Sinais aceitos incluem combinações como:

- concurso público;
- processo seletivo / processo seletivo simplificado;
- seleção de servidor;
- técnico-administrativo;
- professor/docente/substituto/temporário;
- estágio / estagiário;
- residência;
- emprego público;
- contratação temporária de pessoal;
- cargo, vagas, remuneração ou escolaridade em contexto de recrutamento.

`edital`, `resultado`, `aviso` ou `publicação` isoladamente não contam como sinal positivo.

Sem sinal suficiente:

```text
REJECTED_NO_RECRUITMENT_SIGNAL
```

### 4.3 Camada 3 — classificação e confiança

Itens que passaram pelo gate são classificados em domínio, tipo, escolaridade, área, região, esfera, status e confiança.

Estados finais do classificador:

```text
ACCEPTED
QUARANTINED_LOW_CONFIDENCE
REJECTED_PROCUREMENT
REJECTED_NAVIGATION
REJECTED_NO_RECRUITMENT_SIGNAL
```

Somente `ACCEPTED` entra no feed operacional e no D1 público.

`QUARANTINED_LOW_CONFIDENCE` fica exclusivamente no diagnóstico interno e nunca é tratado como concurso pelo app.

### 4.4 Camada 4 — perfil de interesse

Após confirmar que o item é realmente oportunidade de pessoal, aplicam-se filtros específicos de interesse.

O contrato deve aceitar:

```text
scope       federal | estadual | municipal
region      Brasil | SC | Sul | SP | Baixada | ...
uf          SC | PR | RS | SP | ...
education   médio | técnico | superior | qualquer
area        administrativo | matemática | docência | TI | mecatrônica | ...
type        concurso | processo seletivo | estágio | residência | docência | ...
status      open | closing_soon | announced | detected
include_keywords[]
exclude_keywords[]
min_remuneration (quando estruturada)
```

A API Rust suporta filtros explícitos nesses campos. `DashboardConfig` pode definir um perfil padrão versionado sem novo APK, e o Android pode aplicar filtros locais adicionais usando suas configurações nativas.

### 4.5 Watchlist prioritária

Acompanhamentos exatos conhecidos usam IDs canônicos e regras dedicadas. Eles não dependem da busca genérica para continuar sendo monitorados.

A watchlist pode manter eventos como classificação, homologação, convocação, nomeação, posse, reclassificação e documentos relacionados ao processo específico.

Ela continua sujeita à validação de identidade do concurso/documento, mas não é descartada por filtros genéricos de área ou escolaridade.

### 4.6 Fixtures de regressão anti-lixo

Itens errados já observados no feed, especialmente licitações/pregões e links institucionais, tornam-se fixtures permanentes de teste.

O CI deve provar que exemplos equivalentes a:

```text
AVISO DE LICITAÇÃO – PREGÃO ELETRÔNICO
ERRATA DO EDITAL – PREGÃO ELETRÔNICO
AVISO DE RETIFICAÇÃO DE EDITAL DE LICITAÇÃO
Presidência
Vice-Presidência
Corregedoria-Geral da Justiça
```

não resultam em `ACCEPTED`.

Também deve haver fixtures positivas de concursos reais para impedir filtro excessivamente agressivo.

## 5. Python — ingestão e automações

Python permanece responsável por:

- scraping por fonte;
- parsers dedicados;
- leitura/extração de PDF;
- hashing de documentos;
- classificação de domínio e relevância;
- deduplicação e identidade canônica;
- detecção de eventos;
- health semântico;
- comparação temporal;
- anti-ruído;
- geração de snapshot idempotente;
- diagnóstico de rejeitados/quarentena.

A escrita no D1 ocorre somente por CI/credencial Cloudflare restrita, sem endpoint público de ingestão.

## 6. Rust — camada pública read-only

Endpoints previstos:

```text
GET /health
GET /api/v1/contests
GET /api/v1/contests/:id
GET /api/v1/alerts
GET /api/v1/sources
GET /api/v1/dashboard-manifest
GET /dashboard
GET /assets/dashboard.css
```

Não haverá `POST`, `PUT`, `PATCH` ou `DELETE` públicos nesta etapa.

Requisitos:

- queries parametrizadas;
- validação estrita de IDs/query strings;
- paginação limitada;
- escaping de toda entrada externa;
- MIME correto;
- ETag e Cache-Control;
- rate limiting no edge;
- erros sem vazamento de segredo;
- respostas de concursos compostas somente por registros `ACCEPTED`.

## 7. Dashboard dinâmico HTML/CSS

Somente a Home é dinâmica.

Componentes permitidos incluem:

```text
hero_status
priority_watch
alert_card
contest_card
section_heading
source_warning
empty_state
```

`DashboardConfig.sections_json` é declarativo; não contém HTML arbitrário fornecido por usuário.

O CSS:

- não usa `@import` externo;
- não carrega fontes ou recursos de terceiros;
- não contém URLs arbitrárias;
- usa tokens versionados no repositório;
- não recebe valores originados diretamente do scraper.

O dashboard pode mudar sem APK:

- conteúdo;
- ordem das seções;
- filtros padrão;
- watch cards;
- textos curtos;
- spacing, tipografia, cores e radius dentro do sistema aprovado.

## 8. Manifest e validação no Android

`GET /api/v1/dashboard-manifest` retorna no mínimo:

```text
schema_version
dashboard_version
style_version
min_app_version
published_at
html_url
css_url
html_sha256
css_sha256
etag
```

O Android rejeita atualização se:

- schema não for suportado;
- `min_app_version` exceder a versão instalada;
- hash não conferir;
- URL não for HTTPS/host oficial;
- MIME estiver incorreto;
- tamanho exceder limite;
- HTML contiver elementos proibidos;
- CSS violar o contrato.

## 9. WebView local restrita

Fluxo:

1. Android HTTP baixa manifest;
2. baixa HTML/CSS;
3. valida host, MIME, tamanho, schema, versão e SHA-256;
4. valida estrutura;
5. grava em armazenamento privado;
6. promove a `last_known_good` somente após validação completa;
7. WebView carrega por origem local controlada;
8. qualquer request de rede da WebView é bloqueado.

Configuração obrigatória:

```text
JavaScript = OFF
DOM storage = OFF
file access = OFF
content access = OFF
mixed content = BLOCK
native JS bridge = NONE
network loads = BLOCK
```

Links `concursoswatch://` conhecidos abrem tela nativa; links HTTPS oficiais validados abrem navegador externo; demais rotas são bloqueadas.

## 10. Cache e modo degradado

Na abertura:

1. renderizar `last_known_good` imediatamente;
2. atualizar manifest em background;
3. baixar e validar versão nova;
4. ativar apenas se tudo passar;
5. qualquer falha preserva bundle anterior.

Sem bundle válido inicial, mostrar fallback Compose mínimo com acesso às demais abas.

## 11. Modelo de dados

Manter:

```text
Contest
Document
Event
Alert
Source
SourceObservation
DashboardConfig
```

Adicionar diagnóstico interno suficiente para registrar decisão de relevância, confiança e motivo de rejeição/quarentena sem expor lixo no feed público.

## 12. Segurança HTTP

CSP mínima do dashboard:

```text
default-src 'none';
style-src 'self';
img-src 'self' data:;
font-src 'self';
script-src 'none';
connect-src 'none';
object-src 'none';
frame-src 'none';
frame-ancestors 'none';
form-action 'none';
base-uri 'none';
```

Também aplicar:

```text
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=()
Cross-Origin-Resource-Policy: same-origin
```

## 13. Versionamento

Dois eixos independentes:

```text
App: app_version = 4.x.y; version_code crescente
Painel: dashboard_schema = 1; dashboard_version crescente; style_version crescente
```

Mudar dashboard não exige APK novo.

## 14. Publicação dinâmica

```text
commit
 -> testes Python + fixtures anti-lixo
 -> schema validation
 -> testes Rust
 -> renderização do dashboard
 -> validação HTML/CSS/CSP
 -> QA visual automatizado obrigatório
 -> snapshot idempotente no D1
 -> deploy Rust
 -> Android recebe versão compatível no refresh
```

Nenhuma mudança visual dinâmica pula CI ou QA visual.

## 15. Testes obrigatórios

### Python

- procurement rejection;
- navigation rejection;
- positive recruitment gate;
- low-confidence quarantine;
- fixtures negativas históricas;
- fixtures positivas;
- parsers dedicados;
- PDFs;
- deduplicação;
- health semântico;
- snapshot idempotente;
- falhas parciais.

### Rust

- filtros de query;
- somente registros aceitos;
- escaping;
- rotas;
- queries parametrizadas;
- ETag/cache;
- CSP/headers;
- manifest;
- compatibilidade de versão.

### Android

- JavaScript/DOM storage/rede da WebView desativados;
- validação SHA-256;
- host/rota bloqueados;
- deep links interceptados;
- last-known-good;
- fallback offline;
- versão incompatível;
- telas nativas sem regressão;
- screenshots automáticos da Home e abas nativas.

## 16. Critérios de aceite

A v4 só é concluída quando:

1. licitação, pregão e navegação institucional não entram no feed;
2. `edital` isolado não classifica item como concurso;
3. itens incertos ficam em quarentena invisível ao usuário;
4. filtros específicos de interesse funcionam por contrato;
5. watchlist prioritária permanece independente da busca genérica;
6. Home muda conteúdo/ordem/CSS/filtros padrão sem APK;
7. nenhuma mudança dinâmica executa JavaScript;
8. WebView não possui acesso direto à internet;
9. API pública é Rust read-only;
10. Python não atende tráfego público;
11. app funciona com `last_known_good` offline;
12. hashes e compatibilidade são validados antes de ativação;
13. demais telas nativas continuam funcionando;
14. CI impede publicação inválida e exige QA visual;
15. teste em emulador comprova o fluxo completo.

## 17. Fora de escopo

- Play Store;
- atualização silenciosa de APK;
- painel administrativo visual completo;
- JavaScript na Home;
- plugins remotos;
- substituição das demais telas nativas por WebView;
- autenticação de usuário final.
