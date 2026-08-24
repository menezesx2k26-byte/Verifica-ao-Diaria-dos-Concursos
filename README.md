# Concursos Watch v4

Monitor e aplicativo Android para concursos e oportunidades públicas, com filtragem fail-closed e Home dinâmica segura.

## Arquitetura

```text
Fontes oficiais
   │
   v
Python collectors / parsers / PDFs
   │
   ├── rejeitados e quarentena -> diagnóstico interno
   │
   v
snapshot com relevance_status = ACCEPTED
   │
   v
GitHub Actions -> SQL validado -> Cloudflare D1
   │
   v
Rust Worker/WASM read-only
   │
   ├── /api/v1/contests
   ├── /api/v1/alerts
   ├── /api/v1/sources
   ├── /api/v1/dashboard-manifest
   ├── /dashboard
   └── /assets/dashboard.css
   │
   v
Android v4
   ├── Room + Alertas/Concursos/Salvos/Ajustes nativos
   └── Home HTML/CSS validada e carregada localmente, sem JS/rede na WebView
```

Python não atende tráfego público e o Worker Rust não possui endpoint público de escrita. A publicação operacional é feita somente pelo GitHub Actions usando uma credencial Cloudflare restrita.

## Anti-ruído

O pipeline rejeita licitação, pregão, compras públicas e navegação institucional antes da publicação. `edital` isolado não conta como sinal positivo de recrutamento. Itens ambíguos ficam em quarentena e somente registros explicitamente marcados como `ACCEPTED` podem chegar ao D1 público.

A watchlist prioritária usa IDs canônicos e parsers dedicados; ela não depende da busca ampla para continuar sendo acompanhada.

## Dashboard dinâmico

Somente a Home é dinâmica. O servidor escolhe conteúdo e composição dentro de um contrato declarativo conhecido. Não há JavaScript remoto nem HTML arbitrário vindo de scraper.

O Android baixa manifest, HTML e CSS por HTTP nativo, valida host HTTPS, schema, versão mínima, MIME, tamanho e SHA-256 e só então promove o bundle para `last_known_good`. A WebView renderiza uma origem local controlada com JavaScript, DOM storage, file/content access e network loads desativados.

## Versionamento

- APK: `4.x.y`, com `versionCode` crescente quando a capacidade nativa muda.
- Dashboard: `schema_version`, `dashboard_version` e `style_version` independentes do APK.

Alterações seguras de conteúdo/ordem/estilo da Home não exigem recompilar o aplicativo.

## CI e release gates

Antes de merge/deploy são exigidos:

- testes Python e fixtures anti-licitação;
- geração idempotente do snapshot D1;
- Rust fmt + Clippy + testes + WASM;
- smoke do Worker com D1 local via Wrangler;
- validação HTML/CSS/CSP;
- build e testes Android;
- Android Visual QA com Home dinâmica e fallback offline;
- Dashboard Visual QA com JavaScript desativado;
- smoke remoto do Rust staging antes do cutover de produção.

## Cloudflare

O staging/produção precisa destes GitHub Secrets no próprio repositório:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

O token deve ter apenas as permissões necessárias para D1 e Workers da conta usada pelo Concursos Watch. Não coloque token, account ID privado ou credencial no APK/repositório.

Veja [`docs/SETUP.md`](docs/SETUP.md) para o fluxo operacional completo.
