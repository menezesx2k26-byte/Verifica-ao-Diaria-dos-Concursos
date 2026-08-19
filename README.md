# Verificação redundante dos concursos

Monitor externo focado em dois acompanhamentos prioritários:

- **São Vicente — Concurso nº 02/2026 — Assistente-Técnico de Gestão**
- **Praia Grande — Concurso nº 004/2024 — Agente Comunitário de Saúde (ACS)**

## Arquitetura

```text
Fontes oficiais
   │
   ├── GitHub Actions — parser profundo + PDFs (~15 min)
   │      ├── Telegram
   │      ├── ntfy
   │      ├── Resend / e-mail
   │      └── GitHub Issue
   │
   ├── Cloudflare Worker — detector independente + KV (~15 min, intercalado)
   │      ├── Telegram
   │      ├── ntfy
   │      └── Cloudflare Email Service (opcional)
   │
   └── ChatGPT Watch — terceira pesquisa independente

GitHub ── heartbeat autenticado ──> Cloudflare KV
GitHub <──────── /health ───────── Cloudflare
```

A camada GitHub lê novos PDFs quando possível e alerta também quando um documento fortemente relacionado não pôde ser extraído. A camada Cloudflare usa outro scheduler, outro estado e outro algoritmo de matching. Uma fonte que falha repetidamente também vira evento.

## Fontes monitoradas

### São Vicente
- página oficial do Concurso nº 02/2026;
- IBAM/SP do Concurso nº 02/2026;
- área oficial de convocações de 2026;
- Boletim Oficial do Município.

### Praia Grande
- página oficial de concursos e processos seletivos;
- Diário Oficial Eletrônico (DIOENET/Plenus).

## Segurança e prioridade pessoal

Dados pessoais e tokens **não ficam no repositório público**. Nome, número de inscrição ou outros identificadores podem ser cadastrados no secret `WATCH_PRIORITY_TERMS`; se encontrados nas fontes monitoradas, o evento é elevado para prioridade.

## Autoverificação

- GitHub e Cloudflare trocam heartbeat e acusam a ausência um do outro;
- há alerta de recuperação quando o serviço volta;
- cada infraestrutura envia um heartbeat diário de saúde depois das 09:00 de São Paulo quando há canal externo configurado;
- CI valida parser Python, JSON, Worker e `wrangler deploy --dry-run` antes de mudanças relevantes chegarem ao `main`.

## Implantação

A Cloudflare pode ser provisionada pelo próprio GitHub Actions: o workflow cria/reutiliza KV, injeta o binding, envia secrets, faz dry-run, deploy, inicializa baseline, testa `/health` e grava a URL do Worker em `config/runtime.json`.

Veja o passo a passo e a lista de secrets em [`docs/SETUP.md`](docs/SETUP.md).
