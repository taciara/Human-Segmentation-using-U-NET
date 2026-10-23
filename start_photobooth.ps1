$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$cloudflared = Join-Path $env:USERPROFILE ".cloudflared\cloudflared.exe"

Set-Location $root
Write-Host "Iniciando photobooth em http://127.0.0.1:5000"
Start-Process -FilePath "python" -ArgumentList "app.py" -WorkingDirectory $root -WindowStyle Hidden

Start-Sleep -Seconds 4

if (-not (Test-Path $cloudflared)) {
    Write-Error "cloudflared nao encontrado em $cloudflared"
}

Write-Host "Ligando tunel Cloudflare -> https://filtro.seuprojeto.online"
& $cloudflared tunnel run a89219e6-6f83-4aae-b3f1-7072e25c5c55
