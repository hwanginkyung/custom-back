param(
  [string]$EnvFile = "$(Join-Path $PSScriptRoot '.env.local-agent')",
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$AgentArgs
)

if (-not (Test-Path $EnvFile)) {
  Write-Error "[sync] missing env file: $EnvFile"
  Write-Host "[sync] copy template: Copy-Item (Join-Path $PSScriptRoot 'local_agent_sync.env.example') (Join-Path $PSScriptRoot '.env.local-agent')"
  exit 1
}

Get-Content $EnvFile | ForEach-Object {
  $line = $_.Trim()
  if ($line -eq '' -or $line.StartsWith('#')) { return }
  $parts = $line.Split('=', 2)
  if ($parts.Count -ne 2) { return }
  $key = $parts[0].Trim()
  $value = $parts[1].Trim()
  [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
  Write-Error "[sync] python not found"
  exit 1
}

python (Join-Path $PSScriptRoot 'local_agent_push_sync.py') @AgentArgs
