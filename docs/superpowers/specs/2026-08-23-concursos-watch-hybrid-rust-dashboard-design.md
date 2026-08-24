# Concursos Watch v4 — Arquitetura híbrida Rust + Python + HTML/CSS + Android nativo

Data: 2026-08-23
Status: design aprovado em conversa; auto-revisão concluída; aguardando revisão final do documento antes do plano de implementação.

## 1. Objetivo

Evoluir o Concursos Watch para uma arquitetura em que a parte exposta publicamente na internet seja pequena, previsível e escrita em Rust; as automações de coleta e interpretação permaneçam em Python; a Home do aplicativo seja um painel dinâmico em HTML/CSS; e todo o restante do Android continue nativo e estável.

A meta é permitir alterações frequentes de conteúdo, composição da Home, acompanhamentos prioritários, destaques e estilo sem gerar um novo APK, sem transformar o aplicativo em um site encapsulado e sem permitir execução de código remoto arbitrário.

## 2. Princípios fixos

1. Rust é a única camada pública de leitura consumida pelo aplicativo.
2. Python continua responsável por coleta, PDFs, parsing, classificação, deduplicação e automações.
3. Somente a Home/painel é dinâmica em HTML/CSS.
4. Não haverá JavaScript no painel.
5. Alertas, Concursos, Favoritos, Detalhes, Configurações, Room, WorkManager, notificações e deep links continuam nativos no APK.
6. A WebView não acessa a internet diretamente; ela renderiza somente artefatos previamente baixados, validados e cacheados pelo código nativo.
7. O servidor pode escolher conteúdo e composição dentro de um contrato conhecido, mas não envia código executável ao Android.
8. O aplicativo sempre mantém um último painel válido local para funcionamento degradado/offline.
9. Mudança dinâmica nunca deve ter poder de quebrar as telas nativas.
10. Toda publicação de painel passa por CI, validação de segurança e QA visual antes de ficar disponível.
11. GitHub continua como código, histórico, CI e auditoria; D1 é o estado operacional canônico.
12. A automação Python não expõe endpoint público de escrita.

## 3. Arquitetura de alto nível

```text
Fontes oficiais
      |
      v
Python collectors / parsers / PDF scanners
      |
      v
normalização + validação + health semântico
      |
      v
GitHub Actions com credencial Cloudflare restrita
      |
      v
D1 (estado canônico)
      |
      v
Rust public service — Cloudflare Worker/WASM
      |
      +-------------------------------+
      |                               |
      v                               v
/api/v1/*                     dashboard bundle
JSON nativo                   manifest + HTML + CSS
      |                               |
      +---------------+---------------+
                      |
                      v
          Android native network layer
          valida versão/hash/contrato
                      |
          +-----------+-----------+
          |                       |
          v                       v
      Room / telas          cache do dashboard
        nativas                  |
                                 v
                         WebView local restrita
                         sem acesso à internet
```

## 4. Camada pública em Rust

### 4.1 Responsabilidade

A camada Rust será o único serviço público consumido pelo APK. Ela deve permanecer pequena, read-only e sem lógica de scraping.

Responsabilidades:

- servir API versionada;
- ler dados canônicos do D1;
- validar parâmetros de entrada;
- aplicar limites e paginação;
- gerar HTML da Home a partir de templates seguros;
- servir CSS próprio;
- fornecer manifest com versão, hashes e compatibilidade;
- expor health público mínimo;
- aplicar cabeçalhos de segurança;
- controlar cache e ETag;
- negar métodos e rotas não previstas;
- nunca reutilizar HTML vindo dos órgãos como HTML confiável.

### 4.2 Endpoints previstos

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

Não haverá `POST`, `PUT`, `PATCH` ou `DELETE` públicos para ingestão nesta etapa.

### 4.3 Contrato de segurança

O serviço Rust deve:

- aceitar somente métodos previstos;
- validar IDs e query strings por allowlist/formato;
- limitar paginação e tamanho de resposta;
- nunca interpolar texto externo sem escaping;
- usar consultas parametrizadas no D1;
- emitir `Content-Type` correto em todas as respostas;
- aplicar rate limiting no edge;
- aplicar `Cache-Control` por endpoint;
- usar ETag/fingerprint;
- registrar erros sem vazar segredos;
- não conter credenciais administrativas acessíveis pelo cliente.

## 5. Escrita no D1

A escrita operacional não passa por endpoint público.

Fluxo aprovado:

```text
Python gera snapshot/eventos normalizados
      -> testes Python
      -> CI valida schema e invariantes
      -> GitHub Actions usa token Cloudflare restrito
      -> operação idempotente no D1
      -> Rust passa a ler o novo estado
```

O token Cloudflare fica somente em GitHub Secrets e deve ter o menor escopo possível. O APK nunca recebe esse segredo.

## 6. Painel dinâmico HTML/CSS

### 6.1 Escopo

Somente a Home do app será dinâmica.

Ela pode conter:

- Atenção agora;
- acompanhamentos prioritários;
- concursos com inscrições abertas;
- oportunidades por região;
- encerrando nesta semana;
- avisos de monitoramento;
- status resumido das fontes;
- texto editorial curto;
- ordem das seções;
- tokens visuais permitidos.

### 6.2 O que não pode ser dinâmico

O painel não pode:

- executar JavaScript;
- carregar scripts externos;
- criar bridges nativas;
- alterar permissões;
- acessar storage local;
- substituir telas nativas;
- instalar APKs;
- modificar Room diretamente;
- comandar WorkManager;
- carregar recursos arbitrários da internet.

### 6.3 Renderização

O Rust gera HTML a partir de dados já normalizados no D1. Textos vindos de editais, órgãos e automações são sempre escapados como texto.

O HTML usa um conjunto restrito de componentes/templates:

```text
hero_status
priority_watch
alert_card
contest_card
section_heading
source_warning
empty_state
```

O servidor escolhe quais componentes aparecem e em qual ordem, mas `DashboardConfig` nunca contém HTML bruto fornecido por usuário.

### 6.4 CSS

O CSS é versionado e servido pelo mesmo serviço Rust.

Regras:

- sem `@import` externo;
- sem fontes remotas de terceiros;
- sem URLs arbitrárias;
- sem recursos externos por CSS;
- tokens de cor, tipografia, spacing e radius controlados no repositório;
- layout responsivo para a largura real da WebView;
- nenhuma propriedade ou URL originada diretamente de texto coletado dos órgãos.

## 7. Dashboard manifest

`GET /api/v1/dashboard-manifest` retorna dados suficientes para o Android validar o bundle antes de ativá-lo.

Campos mínimos:

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

O Android rejeita o bundle se:

- schema não for suportado;
- `min_app_version` for maior que a versão instalada;
- hash não conferir;
- URL não usar HTTPS e host oficial;
- MIME type não for o esperado;
- tamanho ultrapassar limite local;
- HTML contiver elementos proibidos;
- CSS violar as regras de validação previstas.

## 8. Android: download nativo e WebView sem rede

A WebView não abre `https://servidor/dashboard` diretamente.

Fluxo:

1. camada HTTP nativa solicita o manifest;
2. compara ETag/versão;
3. baixa HTML e CSS por HTTPS;
4. valida host, MIME, tamanho, versão e SHA-256;
5. faz validação estrutural adicional;
6. grava bundle em armazenamento privado do app;
7. marca o bundle como `last_known_good` somente após validação completa;
8. WebView carrega o bundle por origem local controlada, usando mecanismo equivalente a `WebViewAssetLoader`;
9. WebView bloqueia qualquer request que não seja da origem local controlada.

Configuração obrigatória:

```text
JavaScript: desativado
DOM storage: desativado
file access: desativado
content access: desativado
mixed content: bloqueado
safe browsing: ativado quando disponível
native JS bridge: inexistente
network loads na WebView: bloqueados
origem permitida: somente origem local controlada do app
```

Assim o conteúdo pode ser atualizado pela internet, mas o motor que renderiza a Home não possui acesso direto à rede.

## 9. Links do painel

Links não navegam livremente dentro da WebView.

Contratos permitidos:

```text
concursoswatch://contest/<id>
concursoswatch://alerts
concursoswatch://contests?filter=open
https://<fonte-oficial-validada>/...
```

O Android intercepta todos os cliques:

- rota `concursoswatch://` conhecida -> abre tela nativa;
- HTTPS oficial previamente validado -> abre navegador externo;
- qualquer outra rota -> bloqueia.

## 10. Cache e modo degradado

A Home nunca depende de conectividade instantânea para existir.

Fluxo de abertura:

1. renderizar imediatamente o `last_known_good` local;
2. atualizar manifest em background;
3. se houver versão nova, baixar e validar;
4. ativar somente após validação completa;
5. se qualquer etapa falhar, manter bundle anterior.

O cache guarda no mínimo:

```text
dashboard_version
schema_version
style_version
fetched_at
etag
html_sha256
css_sha256
bundle_path
validation_status
```

Se nunca houve bundle válido, o app mostra uma Home Compose mínima de bootstrap com estado "Conferindo seus concursos" e acesso às demais abas.

## 11. Python nas automações

Python continua como camada de ingestão e inteligência de coleta.

Responsabilidades:

- scraping por fonte;
- parsers dedicados;
- leitura e extração de PDF;
- hashing de documentos;
- normalização de concursos;
- detecção de eventos;
- classificação de prioridade;
- health semântico;
- comparação temporal;
- anti-ruído;
- deduplicação;
- geração de snapshot idempotente para publicação pelo CI.

Python não serve tráfego público do aplicativo e não mantém servidor exposto nesta arquitetura.

## 12. Modelo de dados canônico

O modelo permanece orientado a domínio:

```text
Contest
Document
Event
Alert
Source
SourceObservation
DashboardConfig
```

### DashboardConfig

Campos mínimos:

```text
id
version
schema_version
published_at
sections_json
style_version
min_app_version
status
```

`sections_json` contém somente configuração declarativa que o Rust converte para templates conhecidos.

Exemplo:

```json
{
  "sections": [
    {"type": "attention", "limit": 3},
    {"type": "priority_watch", "ids": ["pg-acs-004-2024", "sv-atg-02-2026", "pg-math-002-2025"]},
    {"type": "open_contests", "region": "SC", "limit": 5}
  ]
}
```

## 13. Publicação dinâmica

A alteração de painel segue obrigatoriamente:

```text
commit no Git
   -> schema validation
   -> testes Rust
   -> testes de configuração
   -> renderização de teste
   -> validação de HTML/CSS/CSP
   -> QA visual automatizado obrigatório
   -> publicação da DashboardConfig
   -> Rust passa a servir a versão nova
   -> Android recebe no próximo refresh
```

Nenhuma alteração visual dinâmica pula CI ou QA visual.

## 14. Segurança HTTP do servidor

O HTML entregue pelo Rust usa no mínimo:

```text
Content-Security-Policy:
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

X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=()
Cross-Origin-Resource-Policy: same-origin
```

O bundle cacheado localmente preserva política equivalente na origem local da WebView.

## 15. Separação entre conteúdo confiável e não confiável

Dados obtidos dos órgãos públicos são entrada não confiável, mesmo quando vêm de domínio oficial.

Portanto:

- HTML de órgão nunca é reutilizado no painel;
- somente texto normalizado e campos estruturados entram no D1;
- títulos e descrições são escapados;
- URLs passam por canonicalização e validação HTTPS;
- documentos são tratados como links, não incorporados;
- imagens remotas externas não são carregadas na Home;
- CSS nunca é composto com valores vindos do scraper.

## 16. Compatibilidade com o Android atual

Continuam nativos e sem redesign obrigatório:

- Alertas;
- Concursos;
- Favoritos;
- Detalhes;
- Configurações;
- Room;
- migrações;
- WorkManager;
- notificações;
- filtros;
- busca;
- deep links;
- atualização do banco local.

A única substituição estrutural no frontend é a Home Compose atual, que passa a hospedar a WebView local segura e mantém fallback nativo mínimo.

## 17. Versionamento

Haverá dois eixos independentes.

### App nativo

```text
app_version = 4.x.y
version_code = crescente
```

Muda apenas quando há alteração de capacidade nativa.

### Painel

```text
dashboard_schema = 1
dashboard_version = crescente
style_version = crescente
```

Pode mudar sem APK novo.

Se um painel exigir capacidade inexistente no APK, o Rust entrega a última versão compatível ou o Android mantém seu `last_known_good`.

## 18. Observabilidade

### Rust

Registrar:

- requests por rota;
- status HTTP;
- latência;
- erros de consulta;
- versão de painel servida;
- cache hit/miss;
- incompatibilidades por versão de app.

### Python

Registrar:

- health por fonte;
- parser status;
- item count anomaly;
- última coleta válida;
- documentos examinados;
- eventos detectados;
- falhas parciais.

### Android

Registrar localmente somente diagnóstico técnico necessário para sincronização do painel, sem depender de telemetria pessoal remota.

## 19. Falhas e comportamento esperado

### Rust indisponível

App usa painel `last_known_good`; telas nativas continuam disponíveis.

### D1 indisponível

Rust retorna erro controlado; app mantém bundle anterior.

### Manifest inválido

Android rejeita atualização e mantém bundle anterior.

### HTML ou CSS com hash incorreto

Android rejeita atualização e mantém bundle anterior.

### HTML/CSS estruturalmente inválido

CI deve impedir publicação; Android ainda faz validação defensiva antes de ativar.

### Python falha em uma fonte

Health marca fonte como degradada; isso nunca significa "sem novidade".

### Painel exige app mais novo

Servidor entrega versão compatível anterior; se não houver, Android mantém `last_known_good` ou fallback nativo.

## 20. Testes obrigatórios

### Rust

- unit tests de validação;
- escaping;
- rotas;
- queries parametrizadas;
- ETag/cache;
- CSP/cabeçalhos;
- manifest;
- versão mínima;
- property/fuzz tests onde agregarem valor.

### Python

- parsers dedicados;
- PDFs de fixture;
- deduplicação;
- classificação;
- stale detection;
- health semântico;
- snapshot idempotente;
- falhas parciais.

### Android

- JavaScript efetivamente desativado;
- DOM storage desativado;
- WebView sem acesso à rede;
- bloqueio de hosts/rotas não permitidos;
- interceptação de deep links;
- validação SHA-256;
- fallback offline;
- last-known-good;
- incompatibilidade de versão;
- telas nativas existentes sem regressão;
- screenshots automáticos da Home e abas nativas.

### CI de segurança

- `cargo fmt --check`;
- `cargo clippy` sem warnings relevantes;
- `cargo test`;
- auditoria de dependências Rust;
- testes Python;
- testes Android;
- build APK;
- visual QA;
- validação de ausência de JavaScript;
- validação de CSP;
- validação de URLs e hashes do dashboard.

## 21. Critérios de aceite

A arquitetura v4 só é considerada concluída quando:

1. a Home muda de conteúdo, ordem e CSS sem novo APK;
2. nenhuma mudança dinâmica executa JavaScript;
3. a WebView não possui acesso direto à internet;
4. a API pública é servida pela camada Rust;
5. Python não atende tráfego público do APK;
6. Python/CI escreve no D1 sem endpoint público de ingestão;
7. o app funciona com último painel válido quando offline;
8. hashes e compatibilidade são validados antes de ativar uma nova Home;
9. links são interceptados pelo Android;
10. as demais telas nativas continuam funcionais;
11. CI impede publicação de painel inválido;
12. QA visual é obrigatório para publicar painel;
13. versão do painel é independente da versão do APK;
14. testes em emulador comprovam o fluxo completo.

## 22. Fora de escopo nesta etapa

- Play Store;
- atualização silenciosa de APK;
- painel administrativo visual completo para edição por navegador;
- JavaScript na Home;
- plugins remotos;
- temas arbitrários enviados por usuário;
- substituição das telas nativas por WebView;
- autenticação de usuário final.

Esses itens podem ser avaliados depois sem alterar os princípios centrais desta arquitetura.