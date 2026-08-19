# Setup operacional

Este repositório usa dois executores externos independentes (GitHub Actions e Cloudflare Worker) e pode manter o ChatGPT Watch como uma terceira camada de pesquisa. O código público não deve conter nome completo, número de inscrição, tokens ou endereços privados; esses dados entram por Secrets.

## 1. GitHub Actions — monitor principal

O workflow `Concursos Watch — GitHub` roda nos minutos **07, 22, 37 e 52** de cada hora e também pode ser executado manualmente.

Em **Settings → Secrets and variables → Actions**, configure os canais que quiser usar.

### Telegram
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

### ntfy
- `NTFY_TOPIC`
- `NTFY_SERVER` (opcional; padrão `https://ntfy.sh`)
- `NTFY_TOKEN` (opcional)

### E-mail independente pelo GitHub/Resend
- `RESEND_API_KEY`
- `ALERT_EMAIL_FROM` — remetente validado no Resend
- `ALERT_EMAIL_TO` — um ou mais destinatários separados por vírgula

### Termos pessoais de prioridade
- `WATCH_PRIORITY_TERMS` — opcional; termos separados por vírgula, ponto e vírgula ou quebra de linha.

Use este secret para nome completo, número de inscrição ou outro identificador que você não queira publicar no repositório. Se qualquer termo aparecer numa página/PDF monitorado, o evento é elevado para **PRIORIDADE**.

### Watchdog GitHub ↔ Cloudflare
- `WATCHDOG_TOKEN` — segredo aleatório compartilhado pelos dois sistemas.

Os secrets `CLOUDFLARE_HEARTBEAT_URL` e `CLOUDFLARE_HEALTH_URL` continuam aceitos como override, mas normalmente **não são necessários**: depois do primeiro deploy o workflow da Cloudflare descobre a URL do Worker e grava automaticamente `config/runtime.json`.

O `GITHUB_TOKEN` nativo cria uma Issue em eventos reais detectados pelo monitor GitHub. O heartbeat diário não abre Issue para não poluir o repositório.

## 2. Cloudflare — deploy automático pelo GitHub

Não é mais necessário criar KV manualmente nem editar ID no `wrangler.jsonc`.

Crie estes GitHub Secrets:

- `CLOUDFLARE_ACCOUNT_ID`
- `CLOUDFLARE_API_TOKEN`
- `WATCHDOG_TOKEN` — exatamente o mesmo valor usado pelo monitor GitHub

O token da Cloudflare precisa conseguir implantar Workers e ler/criar Workers KV. Restrinja o token somente à sua conta e ao mínimo de permissões necessário.

Depois execute **Actions → Deploy Cloudflare Watch → Run workflow**.

O workflow fará automaticamente:

1. validar as credenciais;
2. criar ou reutilizar o namespace KV `concursos-watch-state`;
3. gerar `wrangler.generated.jsonc` com o binding `WATCH_STATE`;
4. enviar os runtime secrets sem imprimi-los no log;
5. executar `wrangler deploy --dry-run`;
6. implantar o Worker e seus Cron Triggers;
7. descobrir a URL `workers.dev` pela API da Cloudflare;
8. testar `/health`;
9. gravar a URL em `config/runtime.json` no repositório;
10. apagar os arquivos temporários contendo configuração/secrets.

O Worker roda nos minutos **02, 17, 32 e 47** de cada hora, intercalado com o GitHub.

## 3. E-mail independente pela Cloudflare

O e-mail do Worker é propositalmente separado do Resend usado pelo GitHub.

Primeiro habilite/onboarde seu domínio no **Cloudflare Email Service → Email Sending**. Depois crie:

### Repository variable
- `CF_EMAIL_ENABLED` = `true`

### GitHub Secrets
- `CF_EMAIL_FROM` — remetente pertencente ao domínio habilitado
- `CF_EMAIL_TO` — destinatários separados por vírgula

No próximo deploy, o bootstrap adiciona automaticamente o binding `EMAIL` e restringe remetentes/destinatários à allowlist informada.

Se `CF_EMAIL_ENABLED` não estiver ativo, o Worker continua funcionando normalmente sem o canal de e-mail Cloudflare.

## 4. Primeira execução e baseline

A primeira execução de cada detector cria sua própria linha de base e **não deve alertar por tudo que já existia**. A partir da segunda execução, novos contextos/documentos relevantes geram eventos.

O parser GitHub usa três níveis de proteção:

- contexto de página com grupos obrigatórios;
- leitura de PDFs/documentos novos quando possível;
- alerta de `PDF NÃO EXTRAÍVEL`/`CANDIDATO NÃO LIDO` quando um documento parece fortemente relacionado, mas não pôde ser interpretado.

## 5. Testes operacionais

### GitHub

Em **Actions → Concursos Watch — GitHub → Run workflow**, marque `send_test_alert = true`.

O teste passa pelos canais configurados: Telegram, ntfy, Resend/e-mail e GitHub Issue.

### Cloudflare

Depois do deploy:

```bash
curl -X POST "https://SEU-WORKER/test-alert" \
  -H "Authorization: Bearer SEU_WATCHDOG_TOKEN"
```

Para executar uma verificação completa manualmente:

```bash
curl -X POST "https://SEU-WORKER/run" \
  -H "Authorization: Bearer SEU_WATCHDOG_TOKEN"
```

O endpoint público de saúde é:

```text
https://SEU-WORKER/health
```

Ele não expõe tokens; mostra apenas timestamps/estado do watchdog.

## 6. Watchdog cruzado

Depois que `config/runtime.json` existir:

- GitHub envia heartbeat autenticado ao Worker em cada execução;
- GitHub lê `/health` e alerta se a Cloudflare ficar desatualizada;
- Cloudflare guarda o heartbeat do GitHub no KV e alerta se ele ficar antigo;
- se o GitHub nunca tiver enviado heartbeat, a Cloudflare concede uma janela inicial de 90 minutos e depois acusa a ausência;
- quando um monitor volta, é enviado evento de recuperação.

Assim, `nenhuma novidade` e `o monitor morreu` deixam de ser situações indistinguíveis.

## 7. Heartbeat diário

Depois das 09:00 no fuso `America/Sao_Paulo`, cada infraestrutura tenta enviar **uma confirmação de saúde por dia** pelos canais externos configurados.

O GitHub resume o estado de todas as fontes e a presença da Cloudflare. O Worker resume quantas fontes responderam e mantém o watchdog do GitHub em paralelo.

O heartbeat só é marcado como entregue quando pelo menos um canal externo realmente aceitou a notificação.

## 8. Redundância efetiva

1. GitHub Actions — parser profundo e leitura de documentos/PDFs.
2. Cloudflare Worker — segundo scheduler, segundo estado em KV e matching independente.
3. ChatGPT Watch — terceira camada de pesquisa independente, quando mantido ativo.
4. Telegram.
5. ntfy.
6. E-mail GitHub/Resend.
7. E-mail Cloudflare Email Service.
8. GitHub Issues como trilha permanente para eventos do detector GitHub.
9. GitHub e Cloudflare vigiam o heartbeat um do outro.
10. Heartbeat diário confirma que silêncio significa `sem novidade`, não `sistema morto`.

## 9. Keepalive do GitHub

Repositórios públicos podem ter workflows agendados desativados após longos períodos sem atividade. O workflow grava `.watch-keepalive` nos dias 1 e 15, mantendo atividade periódica no repositório.

## 10. CI

O workflow `Concursos Watch — CI` roda em PRs e mudanças no `main` e valida:

- sintaxe do Python;
- autotestes de normalização/matching;
- JSON das fontes;
- sintaxe do Worker;
- geração offline do KV binding;
- `wrangler deploy --dry-run` com secret de teste.

Isso impede que uma alteração de código quebre silenciosamente os dois monitores ao mesmo tempo.
