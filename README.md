# Verificação redundante dos concursos

Monitor operacional focado em dois acompanhamentos prioritários:

- **São Vicente — Concurso nº 02/2026 — Assistente-Técnico de Gestão**
- **Praia Grande — Concurso nº 004/2024 — Agente Comunitário de Saúde (ACS)**

## Arquitetura

```text
Fontes oficiais
   ├── GitHub Actions (~15 min) ── Telegram / ntfy / Resend / GitHub Issue
   ├── Cloudflare Worker (~10-20 min) ── Telegram / ntfy / Cloudflare Email
   └── ChatGPT Watch (~1 h) ── pesquisa independente

GitHub ──heartbeat──> Cloudflare KV
GitHub <──/health──── Cloudflare
```

A camada GitHub tenta ler novos PDFs; a camada Cloudflare é mais simples e deliberadamente sensível. Cada uma mantém estado próprio. Se uma fonte falhar repetidamente, isso também vira alerta.

## Fontes

### São Vicente
- página oficial do Concurso nº 02/2026;
- IBAM/SP do Concurso nº 02/2026;
- área de convocações de 2026;
- Boletim Oficial do Município.

### Praia Grande
- página oficial de concursos e processos seletivos;
- Diário Oficial Eletrônico (DIOENET/Plenus).

## Alertas

Suporte previsto para:

- Telegram;
- ntfy;
- e-mail pelo GitHub usando Resend;
- e-mail independente pela Cloudflare Email Service;
- Issue automática no GitHub para registrar eventos do detector GitHub.

Nenhum segredo ou endereço pessoal deve ser commitado no repositório público.

## Instalação

Veja [`docs/SETUP.md`](docs/SETUP.md).
