# Setup operacional — Concursos Watch v4

A v4 separa claramente coleta, publicação, leitura pública e aplicativo:

```text
Python -> snapshot SQL validado -> GitHub Actions -> D1
Rust/WASM -> API pública somente leitura + dashboard
Android -> API nativa + Home HTML/CSS validada localmente
```

Não existe endpoint público de ingestão no estado final. `WATCHDOG_TOKEN`, `/run`, `/test-alert` e o Worker JS antigo pertencem ao legado v3 e não devem ser usados como caminho de publicação da v4.

## 1. GitHub Actions — monitor Python

O workflow `Concursos Watch — GitHub` coleta fontes, lê PDFs, normaliza, classifica relevância, deduplica, detecta eventos e gera estado canônico.

Canais de alerta continuam opcionais e independentes:

### Telegram
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

### ntfy
- `NTFY_TOPIC`
- `NTFY_SERVER` (opcional)
- `NTFY_TOKEN` (opcional)

### E-mail
- `RESEND_API_KEY`
- `ALERT_EMAIL_FROM`
- `ALERT_EMAIL_TO`
- ou os secrets SMTP já suportados pelo monitor GitHub.

### Termos privados de prioridade
- `WATCH_PRIORITY_TERMS`

Identificadores pessoais nunca devem ser commitados em arquivo público. Mantenha-os somente em GitHub Secrets.

## 2. Filtragem antes do D1

A publicação é fail-closed:

1. rejeitar licitação/pregão/compras e navegação institucional;
2. exigir sinal positivo de recrutamento de pessoas;
3. classificar confiança;
4. mandar ambíguos para quarentena;
5. aplicar perfil de interesse;
6. publicar somente `relevance_status = ACCEPTED`.

`state/relevance_diagnostics.json` é diagnóstico interno do pipeline e não é feed público.

## 3. Credenciais Cloudflare

Em **Settings -> Secrets and variables -> Actions** deste repositório, configure:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

O token deve ser restrito à conta correta e às permissões mínimas necessárias para Workers Scripts e D1. Não use token global e não copie credenciais para o APK.

Esses secrets são necessários para:

- reconciliar schema/migrations do D1 remoto;
- aplicar `state/d1_snapshot.sql`;
- criar/atualizar draft e promoção do dashboard;
- implantar o Worker Rust staging/produção;
- executar smoke remoto.

Sem esses secrets, os gates locais continuam testáveis, mas o staging remoto e o merge de produção permanecem bloqueados.

## 4. D1

O schema para instalação nova é `cloudflare/schema.sql`.

Banco existente deve usar as migrations versionadas, incluindo `cloudflare/migrations/0002_v4.sql`. O helper `ensure_d1_v4.py` reconcilia banco novo, legado e já-v4 sem repetir `ALTER TABLE ... ADD COLUMN` de forma destrutiva.

O monitor gera:

```text
state/d1_snapshot.sql
```

A transação grava pais de concurso antes de documentos/eventos, preserva FKs e usa upsert/idempotência. Concursos ausentes do snapshot aceito são arquivados (`active = 0`) dentro da mesma transação.

## 5. Worker Rust/WASM

A superfície pública prevista é somente leitura:

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

Qualquer `POST /api/v1/ingest` deve responder 404. Métodos mutáveis em rotas conhecidas não são uma interface de escrita pública.

O CI valida Rust com:

```bash
cargo fmt --manifest-path edge/Cargo.toml --check
cargo clippy --manifest-path edge/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path edge/Cargo.toml
cargo check --manifest-path edge/Cargo.toml --target wasm32-unknown-unknown
```

Além disso, `Rust Edge CI` sobe o Worker com Wrangler e D1 local e faz smoke HTTP real, sem depender de credenciais Cloudflare.

## 6. Staging e cutover

O workflow `Deploy Cloudflare Rust Edge` usa a branch para staging e `main` para produção.

Antes do cutover devem passar:

1. CI Python/anti-ruído;
2. Rust/WASM e runtime local D1;
3. Build Android;
4. Android Visual QA;
5. Dashboard Visual QA;
6. staging Rust remoto com D1 real;
7. smoke de `/health`, concursos, manifest, HTML/CSS;
8. confirmação de `POST /api/v1/ingest -> 404`.

Somente depois disso o Worker JS/publisher legado pode ser removido e a produção promovida.

## 7. Dashboard declarativo

A fonte versionada é `config/dashboard.json`. Ela não aceita HTML arbitrário.

Fluxo:

```text
config -> validate_dashboard_config.py -> draft SQL
       -> renderer Rust determinístico
       -> validate_dashboard_bundle.py
       -> Dashboard Visual QA
       -> promoção SQL atômica
```

A promoção preserva a versão anterior como rollback (`superseded`).

HTML não pode conter conteúdo ativo e CSS não pode carregar recursos remotos. O dashboard servido pelo Rust inclui hashes e headers de segurança.

## 8. Android v4

O APK usa HTTP nativo para buscar o dashboard. Antes de armazenar ele valida:

- schema suportado;
- `min_app_version`;
- HTTPS e host oficial;
- MIME;
- limite de tamanho;
- SHA-256 de HTML/CSS;
- estrutura HTML/CSS segura.

Depois da validação o bundle é promovido atomicamente para `last_known_good`.

A WebView:

```text
JavaScript OFF
DOM storage OFF
file access OFF
content access OFF
mixed content BLOCK
network loads BLOCK
JS bridge NONE
```

Sem cache válido, a Home cai para Compose nativo. Alertas, Concursos, Salvos, Detalhes e Ajustes continuam nativos.

## 9. QA obrigatório

`Android Visual QA` captura:

- Home dinâmica;
- Home offline/fallback;
- Alertas;
- Concursos;
- Salvos;
- Ajustes.

`Dashboard Visual QA` captura o painel em viewport de telefone e viewport ampla com JavaScript desativado.

Não promova dashboard nem mergeie a v4 se algum desses gates estiver vermelho.

## 10. Release

O aplicativo v4 usa versionamento nativo `4.x.y` e `versionCode` crescente. Dashboard tem versionamento independente (`dashboard_version` e `style_version`).

Depois de merge em `main`:

1. validar deploy Rust de produção;
2. validar leitura do D1 remoto;
3. validar manifest/hash/HTML/CSS;
4. validar ausência de ingest público;
5. obter o APK produzido pelo workflow de release/build;
6. manter a versão anterior do Worker/dashboard disponível para rollback operacional.
