# E-mail redundante

O monitor suporta **três caminhos de e-mail** que podem coexistir. A intenção é evitar um único provedor de entrega.

## 1. GitHub Actions → Resend

Secrets:

- `RESEND_API_KEY`
- `ALERT_EMAIL_FROM`
- `ALERT_EMAIL_TO` — múltiplos destinatários separados por vírgula

Use um remetente/domínio validado no Resend.

## 2. GitHub Actions → Gmail SMTP

Este caminho é independente do Resend e é opcional.

Secrets:

- `GMAIL_SMTP_USER` — conta Google que enviará o aviso
- `GMAIL_SMTP_APP_PASSWORD` — **App Password**, nunca a senha normal da conta
- `GMAIL_ALERT_TO` — destinatários separados por vírgula
- `GMAIL_SMTP_FROM` — opcional; se omitido, usa `GMAIL_SMTP_USER`

O código usa por padrão `smtp.gmail.com:465` com TLS. Não há segredo, e-mail pessoal ou senha no repositório público.

Se `GMAIL_ALERT_TO` estiver ausente, o módulo pode reutilizar `ALERT_EMAIL_TO`.

## 3. Cloudflare Worker → Cloudflare Email Service

Este caminho pertence à segunda infraestrutura do monitor.

Depois de habilitar o domínio no Cloudflare Email Service, configure:

- repository variable `CF_EMAIL_ENABLED=true`
- secret `CF_EMAIL_FROM`
- secret `CF_EMAIL_TO`

O workflow de deploy gera o binding `EMAIL` com allowlist de remetente e destinatários.

## Comportamento em falha

Os canais são independentes. Se Telegram, Resend ou Gmail falhar, o erro é registrado, mas os outros canais continuam sendo tentados. O detector não transforma uma falha de e-mail em falha da leitura do concurso.

Para eventos reais detectados pelo GitHub, uma GitHub Issue também é aberta e atribuída ao proprietário do repositório. Isso fornece trilha permanente e ainda pode resultar em notificação por e-mail conforme as preferências de notificações da conta do GitHub.

## Teste

Depois de configurar os secrets:

1. abra **Actions → Concursos Watch — GitHub**;
2. use **Run workflow**;
3. marque `send_test_alert=true`;
4. confirme quais canais receberam o teste.

Quando Resend e Gmail SMTP estiverem ambos configurados, receber duas cópias de um teste/evento crítico é esperado e demonstra independência dos caminhos de entrega.
