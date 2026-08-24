# Concursos Watch v4 — Arquitetura híbrida Rust + Python + HTML/CSS + Android nativo

Data: 2026-08-23
Status: design aprovado em conversa; aguardando revisão final do documento antes do plano de implementação.

## 1. Objetivo

Evoluir o Concursos Watch para uma arquitetura em que a parte exposta publicamente na internet seja pequena, previsível e escrita em Rust; as automações de coleta e interpretação permaneçam em Python; a Home do aplicativo seja um painel dinâmico em HTML/CSS; e todo o restante do Android continue nativo e estável.

O objetivo principal é reduzir a necessidade de gerar e instalar um novo APK para alterações de conteúdo, composição da Home, acompanhamentos prioritários e destaques, sem transformar o aplicativo em um site encapsulado nem permitir execução de código remoto arbitrário.

## 2. Princípios fixos

1. Rust é a camada pública de rede e autoridade de leitura para o aplicativo.
2. Python continua responsável por coleta, PDFs, parsing, classificação, deduplicação e automações.
3. Somente a Home/painel é dinâmica em HTML/CSS.
4. Não haverá JavaScript remoto no painel.
5. Alertas, Concursos, Favoritos, Detalhes, Configurações, Room, WorkManager, notificações e deep links continuam nativos no APK.
6. O servidor pode escolher conteúdo e composição dentro de um contrato conhecido, mas não pode enviar código executável para o Android.
7. O aplicativo sempre mantém um último estado válido local para funcionamento degradado/offline.
8. Mudança dinâmica nunca deve ter poder de quebrar as telas nativas.
9. Toda publicação de painel passa por validação automatizada antes de ficar disponível.
10. GitHub continua como código, histórico, CI e auditoria; não como banco operacional primário do app.

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
D1 (estado canônico)
      |
      +-------------------------------+
      |                               |
      v                               v
Rust public service              Python jobs internos
Cloudflare Worker/WASM           GitHub Actions / cron
      |
      +-------------------------------+
      |                               |
      v                               v
/api/v1/*                     /dashboard
JSON nativo                   HTML + CSS, sem JS
      |                               |
      v                               v
Android nativo                WebView restrita da Home
(Room/WorkManager/etc.)       + cache último painel válido
```

## 4. Camada pública em Rust

### 4.1 Responsabilidade

A camada Rust será o único serviço público consumido pelo APK. Ela deve ser pequena e sem lógica de scraping.

Responsabilidades:

- servir a API versionada;
- ler dados canônicos do D1;
- validar parâmetros de entrada;
- aplicar limites e paginação;
- gerar o HTML da Home a partir de templates seguros;
- servir CSS próprio;
- expor health público mínimo;
- aplicar cabeçalhos de segurança;
- controlar cache e ETag;
- negar métodos e rotas não previstas;
- nunca executar conteúdo proveniente dos órgãos como HTML confiável.

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

A API JSON continua atendendo as telas nativas. `/dashboard` atende exclusivamente a Home dinâmica.

### 4.3 Contrato de segurança

O serviço Rust deve:

- aceitar somente métodos previstos;
- rejeitar payloads acima de limites definidos;
- validar IDs e query strings;
- nunca interpolar texto externo sem escaping;
- usar consultas parametrizadas no D1;
- emitir `Content-Type` correto em todas as respostas;
- aplicar rate limiting no edge;
- aplicar `Cache-Control` por endpoint;
- usar ETag/fingerprint para evitar downloads desnecessários;
- registrar erros sem vazar segredos;
- não expor endpoints de administração no mesmo contrato público sem autenticação forte.

## 5. Painel dinâmico HTML/CSS

### 5.1 Escopo

Somente a Home do app será entregue como painel dinâmico.

Ela pode conter:

- Atenção agora;
- acompanhamentos prioritários;
- concursos com inscrições abertas;
- oportunidades por região;
- encerrando nesta semana;
- avisos de monitoramento;
- status resumido das fontes;
- texto editorial e explicações curtas;
- ordem das seções;
- estilos e tokens visuais permitidos.

### 5.2 O que não pode ser dinâmico

O painel não pode:

- executar JavaScript;
- carregar scripts externos;
- criar bridges nativas;
- alterar permissões do app;
- abrir arquivos locais;
- acessar storage local do Android;
- substituir telas nativas;
- instalar APKs;
- modificar banco Room diretamente;
- enviar comandos para WorkManager;
- navegar para hosts não aprovados.

### 5.3 Renderização

O Rust gera HTML a partir de dados já normalizados no D1. Textos vindos de editais, órgãos e automações são sempre escapados como texto.

O HTML deve usar um conjunto restrito de componentes/templates, por exemplo:

```text
hero_status
priority_watch
alert_card
contest_card
section_heading
source_warning
empty_state
```

O servidor escolhe quais componentes aparecem e em qual ordem, mas não envia templates arbitrários cadastrados por usuário.

### 5.4 CSS

O CSS será servido pelo mesmo domínio do Rust e poderá evoluir sem novo APK.

Regras:

- sem `@import` externo;
- sem fontes remotas de terceiros;
- sem URLs arbitrárias;
- sem conteúdo gerado por CSS que carregue recursos externos;
- tokens de cor, tipografia, spacing e radius controlados no repositório;
- layout responsivo para a largura real da WebView.

## 6. WebView Android

A WebView será usada somente pela rota Home.

Configuração obrigatória:

```text
JavaScript: desativado
DOM storage: desativado salvo necessidade comprovada
file access: desativado
content access: desativado
mixed content: bloqueado
safe browsing: ativado quando disponível
zoom arbitrário: desativado
native JS bridge: inexistente
navegação externa dentro da WebView: bloqueada
host permitido: somente domínio oficial do Concursos Watch
```

Links de concursos ou documentos não devem navegar livremente dentro da WebView. O Android intercepta o clique e decide:

- concurso conhecido -> abre a tela nativa de detalhes;
- fonte oficial HTTPS permitida -> abre navegador externo;
- qualquer outra URL -> bloqueia.

## 7. Cache e modo degradado

A Home nunca pode depender de conectividade instantânea para existir.

Fluxo:

1. abrir aplicativo;
2. mostrar imediatamente o último painel válido cacheado;
3. solicitar a versão atual em background;
4. comparar ETag/versão;
5. validar a nova resposta;
6. somente então substituir o cache;
7. se houver erro, preservar o painel anterior.

O cache guarda no mínimo:

```text
dashboard_version
schema_version
fetched_at
etag
html
css_version
sha256
```

Se nunca houve uma carga válida, o app mostra uma Home nativa mínima de bootstrap com estado "Conferindo seus concursos" e acesso às demais abas.

## 8. Python nas automações

Python continua como camada de ingestão porque é onde o projeto ganha velocidade e flexibilidade para lidar com fontes públicas inconsistentes.

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
- persistência no D1 por endpoint interno autenticado ou acesso controlado de CI.

Python não serve tráfego público do aplicativo.

## 9. Modelo de dados canônico

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

`sections_json` não contém HTML bruto. Ele contém somente dados declarativos que o Rust converte para templates conhecidos.

Exemplo conceitual:

```json
{
  "sections": [
    {"type": "attention", "limit": 3},
    {"type": "priority_watch", "ids": ["pg-acs-004-2024", "sv-atg-02-2026", "pg-math-002-2025"]},
    {"type": "open_contests", "region": "SC", "limit": 5}
  ]
}
```

## 10. Publicação dinâmica

A alteração de painel deve seguir:

```text
commit no Git
   -> CI valida configuração
   -> testes de schema
   -> renderização de teste
   -> verificação de links/CSP
   -> snapshot visual opcional
   -> publicação da nova DashboardConfig
   -> Rust passa a servir a versão nova
   -> Android atualiza no próximo refresh
```

Nenhuma alteração visual dinâmica pula CI.

## 11. Segurança HTTP do painel

Cabeçalhos mínimos:

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

O painel não usa formulários nem requests client-side.

## 12. Separação entre conteúdo confiável e não confiável

Dados obtidos dos órgãos públicos são considerados entrada não confiável, mesmo quando vêm de domínio oficial.

Portanto:

- HTML de órgão nunca é reutilizado como HTML do painel;
- somente texto normalizado e campos estruturados entram no D1;
- títulos e descrições são escapados;
- URLs passam por canonicalização e validação HTTPS;
- documentos são tratados como links, não incorporados no painel;
- imagens remotas externas não são carregadas na Home.

## 13. Compatibilidade com o Android atual

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

A única substituição estrutural no frontend é a Home Compose atual, que passa a hospedar o painel seguro, mantendo um fallback nativo mínimo.

## 14. Versionamento

Haverá dois eixos independentes:

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

O Rust inclui em resposta:

```text
X-Dashboard-Version
ETag
X-Min-App-Version
```

Se um painel exigir capacidade ainda inexistente no APK, o servidor continua entregando a última versão compatível para aquele app.

## 15. Observabilidade

A camada Rust deve registrar:

- requests por rota;
- status HTTP;
- latência;
- erros de consulta;
- versão de painel servida;
- taxa de cache hit;
- respostas incompatíveis por versão de app.

As automações Python continuam registrando:

- health por fonte;
- parser status;
- item count anomaly;
- última coleta válida;
- documentos examinados;
- eventos detectados;
- falhas parciais.

O Android não precisa expor telemetria pessoal para essa arquitetura funcionar.

## 16. Falhas e comportamento esperado

### Rust indisponível

App usa painel cacheado e telas nativas continuam funcionando com dados locais.

### D1 indisponível

Rust retorna erro controlado; app mantém último painel válido.

### HTML inválido

CI deve impedir publicação. Se ainda assim chegar inválido, Android não substitui cache.

### CSS inválido

Versão de CSS é validada e cacheada separadamente; fallback para CSS anterior.

### Python falha em uma fonte

Health marca a fonte como degradada; não equivale a "sem novidade".

### Painel exige app mais novo

Servidor entrega versão compatível anterior ou painel mínimo compatível.

## 17. Testes obrigatórios

### Rust

- unit tests de validação;
- testes de escaping;
- testes de rotas;
- testes de queries;
- testes de ETag/cache;
- testes de CSP/cabeçalhos;
- testes de versão mínima;
- fuzz/property tests para parâmetros e renderização quando aplicável.

### Python

- parsers dedicados;
- PDFs de fixture;
- deduplicação;
- classificação;
- stale detection;
- health semântico;
- publicação idempotente;
- falhas parciais.

### Android

- WebView com JavaScript efetivamente desativado;
- bloqueio de hosts externos;
- interceptação de links;
- fallback offline;
- cache do último painel válido;
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
- validação de CSP e ausência de JavaScript no painel.

## 18. Critérios de aceite

A arquitetura v4 só pode ser considerada concluída quando:

1. a Home pode mudar de conteúdo e ordem sem novo APK;
2. nenhuma mudança dinâmica executa JavaScript no Android;
3. a API pública está servida pela camada Rust;
4. Python não atende tráfego público do APK;
5. o app funciona com último painel válido quando offline;
6. links externos são interceptados e controlados pelo Android;
7. o restante das telas nativas continua funcional;
8. CI impede publicação de painel inválido;
9. a versão do painel é independente da versão do APK;
10. screenshots e testes comprovam o comportamento em aparelho/emulador real.

## 19. Fora de escopo nesta etapa

- Play Store;
- atualização silenciosa de APK;
- painel administrativo visual completo para edição por navegador;
- JavaScript na Home;
- plugins remotos;
- temas arbitrários enviados por usuário;
- substituição das telas nativas por WebView;
- autenticação de usuário final.

Esses itens podem ser avaliados depois sem alterar os princípios centrais desta arquitetura.