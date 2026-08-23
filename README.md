# Verificação redundante dos concursos

Monitor externo focado nos acompanhamentos prioritários de concursos e oportunidades públicas do Gabriel.

## Arquitetura

```text
Fontes oficiais
   │
   ├── GitHub Actions — parser profundo + PDFs (~15 min)
   │      ├── Telegram
   │      ├── ntfy
   │      ├── Resend / e-mail
   │      └── Gmail SMTP
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

### PGFN — estágio nacional 2026
- página oficial da 1ª Seleção Nacional de Estagiários da PGFN;
- página do processo de graduação no CIEE;
- monitor separado em `PGFN Estágio Watch`, executado a cada 15 minutos;
- foco em regras da prova online: consulta, materiais externos, IA/ferramentas externas, troca de abas, tempo, desconexão, fiscalização, eliminação e retificações;
- estado independente em `state/pgfn.json`, evitando misturar alterações da PGFN com os concursos prioritários;
- alertas usam os canais externos já configurados no repositório; GitHub Issue não é tratado como canal principal.

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
