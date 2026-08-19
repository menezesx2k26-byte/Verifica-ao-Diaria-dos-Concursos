# Ativação em um comando no Windows

O repositório inclui `scripts/bootstrap-windows.ps1` para reduzir a ativação externa a um único fluxo interativo.

Ele:

- valida `gh` e a autenticação GitHub;
- grava `CLOUDFLARE_ACCOUNT_ID` e `CLOUDFLARE_API_TOKEN` como GitHub Actions Secrets;
- gera automaticamente um `WATCHDOG_TOKEN` criptograficamente aleatório quando ele ainda não existe;
- permite configurar `WATCH_PRIORITY_TERMS`, Jina, Gmail SMTP, Telegram, ntfy, Resend e Cloudflare Email sem salvar os valores em arquivos;
- dispara `cloudflare-deploy.yml`, `deep-audit.yml`, `search-probe.yml` (quando Jina estiver configurado) e um teste do monitor principal;
- mostra as execuções recentes no final.

## Pré-requisito

Instale o GitHub CLI e autentique:

```powershell
gh auth login
```

## Executar

Na raiz do repositório:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-windows.ps1
```

No PowerShell 7 também pode usar:

```powershell
pwsh .\scripts\bootstrap-windows.ps1
```

Os secrets são enviados ao `gh secret set` pela entrada padrão. O script não cria `.env`, arquivo temporário de credenciais nem commit com segredos.

## Apenas infraestrutura obrigatória

Para configurar somente Cloudflare + watchdog e pular canais opcionais:

```powershell
pwsh .\scripts\bootstrap-windows.ps1 -SkipOptional
```

## Configurar sem disparar workflows

```powershell
pwsh .\scripts\bootstrap-windows.ps1 -NoDispatch
```

## Depois da ativação

Veja as execuções:

```powershell
gh run list -R menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos
```

Acompanhe uma execução específica:

```powershell
gh run watch <RUN_ID> -R menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos
```

O `Deploy Cloudflare Watch` já faz preflight, criação/reutilização do KV, dry-run, deploy, descoberta da URL pública, `/health`, baseline e persistência de `config/runtime.json`.
