[CmdletBinding()]
param(
    [string]$Repo = "menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos",
    [switch]$SkipOptional,
    [switch]$NoDispatch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step([string]$Text) {
    Write-Host "`n==> $Text" -ForegroundColor Cyan
}

function Assert-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name não encontrado. $InstallHint"
    }
}

function Read-SecretPlain([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

function Set-RepoSecret([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Valor vazio para secret $Name"
    }

    # gh secret set lê o valor pela entrada padrão quando --body não é usado.
    # Isso evita gravar secrets em arquivo e evita colocá-los na linha de comando.
    $Value | & gh secret set $Name -R $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao gravar secret $Name"
    }
    Write-Host "  [OK] $Name" -ForegroundColor Green
}

function Set-RepoVariable([string]$Name, [string]$Value) {
    $Value | & gh variable set $Name -R $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao gravar variable $Name"
    }
    Write-Host "  [OK] variável $Name=$Value" -ForegroundColor Green
}

function Get-SecretNames {
    $json = & gh secret list -R $Repo --json name
    if ($LASTEXITCODE -ne 0) {
        throw "Não foi possível listar secrets do repositório $Repo"
    }
    if ([string]::IsNullOrWhiteSpace($json)) { return @() }
    return @($json | ConvertFrom-Json | ForEach-Object { $_.name })
}

function Secret-Exists([string]$Name) {
    return (Get-SecretNames) -contains $Name
}

function New-WatchdogToken {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Ask-YesNo([string]$Prompt, [bool]$Default = $false) {
    $suffix = if ($Default) { "[S/n]" } else { "[s/N]" }
    $answer = Read-Host "$Prompt $suffix"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim().ToLowerInvariant() -in @("s", "sim", "y", "yes")
}

function Ensure-Secret([string]$Name, [string]$Prompt, [switch]$GenerateWatchdog) {
    if (Secret-Exists $Name) {
        Write-Host "  [já existe] $Name" -ForegroundColor DarkGreen
        return
    }

    if ($GenerateWatchdog) {
        $value = New-WatchdogToken
        try { Set-RepoSecret $Name $value }
        finally { $value = $null }
        return
    }

    $value = Read-SecretPlain $Prompt
    try { Set-RepoSecret $Name $value }
    finally { $value = $null }
}

function Optional-Secret([string]$Name, [string]$Prompt) {
    if (Secret-Exists $Name) {
        Write-Host "  [já existe] $Name" -ForegroundColor DarkGreen
        return
    }
    $value = Read-SecretPlain $Prompt
    try { Set-RepoSecret $Name $value }
    finally { $value = $null }
}

Write-Host "Concursos Watch — bootstrap seguro para Windows" -ForegroundColor White
Write-Host "Repositório: $Repo"
Write-Host "Secrets são enviados ao GitHub pela entrada padrão do gh e não são gravados em arquivo."

Assert-Command "gh" "Instale o GitHub CLI e execute 'gh auth login'."

Write-Step "Validando autenticação GitHub"
& gh auth status
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI não autenticado. Execute: gh auth login"
}

& gh repo view $Repo --json nameWithOwner | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Não foi possível acessar $Repo com a conta atual do gh."
}
Write-Host "  [OK] acesso ao repositório" -ForegroundColor Green

Write-Step "Configurando a infraestrutura Cloudflare obrigatória"
Ensure-Secret "CLOUDFLARE_ACCOUNT_ID" "Cole o CLOUDFLARE_ACCOUNT_ID"
Ensure-Secret "CLOUDFLARE_API_TOKEN" "Cole o CLOUDFLARE_API_TOKEN (Workers + KV)"
Ensure-Secret "WATCHDOG_TOKEN" "" -GenerateWatchdog

if (-not $SkipOptional) {
    Write-Step "Prioridade pessoal"
    if (-not (Secret-Exists "WATCH_PRIORITY_TERMS") -and (Ask-YesNo "Configurar nome/inscrição para alertas PRIORIDADE?" $true)) {
        Optional-Secret "WATCH_PRIORITY_TERMS" "Digite nome completo, inscrição e identificadores separados por vírgula/;"
    }

    Write-Step "Radar auxiliar do IBAM"
    if (-not (Secret-Exists "JINA_API_KEY") -and (Ask-YesNo "Configurar JINA_API_KEY para o Search Probe do IBAM?" $true)) {
        Optional-Secret "JINA_API_KEY" "Cole a JINA_API_KEY"
    }

    Write-Step "Gmail SMTP redundante"
    if (Ask-YesNo "Configurar envio redundante por Gmail SMTP?" $false) {
        Optional-Secret "GMAIL_SMTP_USER" "Conta Gmail remetente"
        Optional-Secret "GMAIL_SMTP_APP_PASSWORD" "Google App Password (não a senha normal)"
        Optional-Secret "GMAIL_ALERT_TO" "E-mail(s) destinatário(s), separados por vírgula"
    }

    Write-Step "Telegram"
    if (Ask-YesNo "Configurar alertas por Telegram?" $false) {
        Optional-Secret "TELEGRAM_BOT_TOKEN" "Cole o TELEGRAM_BOT_TOKEN"
        Optional-Secret "TELEGRAM_CHAT_ID" "Cole o TELEGRAM_CHAT_ID"
    }

    Write-Step "ntfy"
    if (Ask-YesNo "Configurar alertas por ntfy?" $false) {
        Optional-Secret "NTFY_TOPIC" "Digite o tópico ntfy"
        if (Ask-YesNo "Usar servidor ntfy diferente de https://ntfy.sh?" $false) {
            Optional-Secret "NTFY_SERVER" "Digite a URL do servidor ntfy"
        }
        if (Ask-YesNo "Seu tópico ntfy exige token?" $false) {
            Optional-Secret "NTFY_TOKEN" "Cole o NTFY_TOKEN"
        }
    }

    Write-Step "Resend"
    if (Ask-YesNo "Configurar e-mail adicional via Resend?" $false) {
        Optional-Secret "RESEND_API_KEY" "Cole a RESEND_API_KEY"
        Optional-Secret "ALERT_EMAIL_FROM" "Remetente verificado no Resend"
        Optional-Secret "ALERT_EMAIL_TO" "Destinatário(s), separados por vírgula"
    }

    Write-Step "Cloudflare Email Service"
    if (Ask-YesNo "Cloudflare Email Service já está habilitado para um domínio seu?" $false) {
        Optional-Secret "CF_EMAIL_FROM" "Remetente autorizado no Cloudflare Email"
        Optional-Secret "CF_EMAIL_TO" "Destinatário(s), separados por vírgula"
        Set-RepoVariable "CF_EMAIL_ENABLED" "true"
    }
}

Write-Step "Resumo dos secrets existentes"
$names = Get-SecretNames | Sort-Object
$interesting = @(
    "CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_API_TOKEN", "WATCHDOG_TOKEN",
    "WATCH_PRIORITY_TERMS", "JINA_API_KEY",
    "GMAIL_SMTP_USER", "GMAIL_SMTP_APP_PASSWORD", "GMAIL_ALERT_TO",
    "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID",
    "NTFY_TOPIC", "NTFY_SERVER", "NTFY_TOKEN",
    "RESEND_API_KEY", "ALERT_EMAIL_FROM", "ALERT_EMAIL_TO",
    "CF_EMAIL_FROM", "CF_EMAIL_TO"
)
foreach ($name in $interesting) {
    $mark = if ($names -contains $name) { "OK" } else { "--" }
    Write-Host ("  [{0}] {1}" -f $mark, $name) -ForegroundColor $(if ($mark -eq "OK") { "Green" } else { "DarkGray" })
}

if (-not $NoDispatch) {
    Write-Step "Disparando ativação e testes"

    & gh workflow run cloudflare-deploy.yml -R $Repo
    if ($LASTEXITCODE -ne 0) { throw "Falha ao disparar cloudflare-deploy.yml" }
    Write-Host "  [OK] deploy Cloudflare solicitado" -ForegroundColor Green

    & gh workflow run deep-audit.yml -R $Repo
    if ($LASTEXITCODE -ne 0) { throw "Falha ao disparar deep-audit.yml" }
    Write-Host "  [OK] Deep Audit solicitado" -ForegroundColor Green

    if (Secret-Exists "JINA_API_KEY") {
        & gh workflow run search-probe.yml -R $Repo
        if ($LASTEXITCODE -ne 0) { throw "Falha ao disparar search-probe.yml" }
        Write-Host "  [OK] Search Probe solicitado" -ForegroundColor Green
    }

    & gh workflow run monitor.yml -R $Repo -f send_test_alert=true
    if ($LASTEXITCODE -ne 0) { throw "Falha ao disparar monitor.yml" }
    Write-Host "  [OK] teste operacional do monitor solicitado" -ForegroundColor Green

    Start-Sleep -Seconds 4
    Write-Step "Execuções recentes"
    & gh run list -R $Repo --limit 10
}

Write-Host "`nBootstrap concluído." -ForegroundColor Green
Write-Host "Se o deploy Cloudflare falhar, rode: gh run list -R $Repo"
Write-Host "Depois acompanhe a execução com: gh run watch <RUN_ID> -R $Repo"
