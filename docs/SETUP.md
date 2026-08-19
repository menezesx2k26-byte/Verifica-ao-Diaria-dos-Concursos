# Setup operacional

## 1. GitHub Actions

O workflow roda nos minutos 07, 22, 37 e 52 de cada hora. Isso evita o minuto 0, em que o próprio GitHub documenta maior chance de atraso sob carga.

Em **Settings → Secrets and variables → Actions**, configure o que quiser usar:

### Alertas imediatos
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`
- `NTFY_TOPIC`
- `NTFY_SERVER` (opcional; padrão `https://ntfy.sh`)
- `NTFY_TOKEN` (opcional)

### E-mail pelo GitHub/Resend
- `RESEND_API_KEY`
- `ALERT_EMAIL_FROM` — remetente validado no Resend
- `ALERT_EMAIL_TO` — um ou mais destinatários separados por vírgula

### Watchdog Cloudflare
- `CLOUDFLARE_HEARTBEAT_URL` — `https://SEU-WORKER/heartbeat/github`
- `CLOUDFLARE_HEALTH_URL` — `https://SEU-WORKER/health`
- `WATCHDOG_TOKEN` — segredo aleatório compartilhado com o Worker

O `GITHUB_TOKEN` nativo cria uma Issue quando há evento detectado pelo monitor GitHub, deixando registro permanente.

## 2. Cloudflare

Entre em `cloudflare/` e execute:

```bash
npm install
npx wrangler kv namespace create WATCH_STATE
```

Copie o ID retornado para `wrangler.jsonc` em `kv_namespaces[0].id`.

Cadastre os secrets:

```bash
npx wrangler secret put WATCHDOG_TOKEN
npx wrangler secret put TELEGRAM_BOT_TOKEN
npx wrangler secret put TELEGRAM_CHAT_ID
npx wrangler secret put NTFY_TOPIC
npx wrangler secret put NTFY_SERVER
npx wrangler secret put NTFY_TOKEN
```

Depois:

```bash
npx wrangler deploy
```

O Worker roda nos minutos 02, 12, 32 e 42. O cron é propositalmente diferente do GitHub.

## 3. E-mail independente pela Cloudflare

Cloudflare Email Service exige um domínio usando Cloudflare DNS e o domínio precisa ser onboarded em **Compute → Email Service → Email Sending**.

Depois, acrescente ao `wrangler.jsonc`:

```jsonc
"send_email": [
  {
    "name": "EMAIL"
  }
]
```

E configure variáveis/secrets:

- `CF_EMAIL_FROM` — endereço do domínio habilitado
- `CF_EMAIL_TO` — destinatários separados por vírgula

Assim o e-mail do Cloudflare não depende do Resend usado pelo GitHub.

## 4. Primeira execução

A primeira execução cria a linha de base e **não alerta por tudo que já existia**. Depois disso, alterações relevantes passam a gerar eventos.

Para testar o GitHub, use **Actions → Concursos Watch — GitHub → Run workflow**.

Para testar o Worker depois do deploy:

```bash
curl -X POST https://SEU-WORKER/run \
  -H "Authorization: Bearer SEU_TOKEN"
```

## 5. O que é redundante

1. GitHub Actions: parser mais profundo, inclusive PDFs novos.
2. Cloudflare Worker: segunda infraestrutura e segundo estado em KV.
3. ChatGPT Watches existentes: terceira camada de pesquisa independente.
4. Telegram.
5. ntfy.
6. E-mail GitHub/Resend.
7. E-mail Cloudflare Email Service.
8. GitHub Issues como trilha permanente.
9. GitHub e Cloudflare trocam heartbeat e acusam a morte um do outro.

## 6. Keepalive

Repositórios públicos podem ter workflows agendados desativados após 60 dias sem atividade. O workflow grava `.watch-keepalive` nos dias 1 e 15, evitando uma longa janela sem atividade do repositório.
