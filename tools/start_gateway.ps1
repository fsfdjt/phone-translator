param([int]$Port = 8765)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskPython = Join-Path $taskRoot '.toolchain/gateway-venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) {
    throw 'Create .toolchain/gateway-venv and install gateway/requirements.txt first (see docs).'
}
$tokenFile = Join-Path $taskRoot '.toolchain/gateway-token.txt'
if (-not (Test-Path -LiteralPath $tokenFile)) {
    $taskToken = & $taskPython -c 'import secrets; print(secrets.token_urlsafe(32))'
    [IO.File]::WriteAllText($tokenFile, $taskToken.Trim(), [Text.UTF8Encoding]::new($false))
}
$env:GATEWAY_TOKEN = [IO.File]::ReadAllText($tokenFile).Trim()
$env:GATEWAY_MODE = 'mock'
Write-Host 'Mock gateway only: no real telephone calls.'
Write-Host "Token saved in $tokenFile. Copy its contents into the phone gateway token field."
Write-Host "Phone URL after adb reverse: http://127.0.0.1:$Port"
& $taskPython -m uvicorn gateway.server:app --host 127.0.0.1 --port $Port --workers 1 --ws-max-size 65536 --no-access-log
