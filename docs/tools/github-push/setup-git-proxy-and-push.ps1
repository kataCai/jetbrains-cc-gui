param(
  [string]$ProxyScheme = "socks5h",
  [string]$ProxyHost = "127.0.0.1",
  [int]$ProxyPort = 7897,
  [switch]$NoPush,
  [string]$Remote = "origin",
  [string]$Branch,
  [int]$MaxPushRetries = 3,
  [int]$MaxCheckRetries = 3
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
  param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GitArgs
  )

  & git @GitArgs
  if ($LASTEXITCODE -ne 0) {
    throw ("git " + ($GitArgs -join ' ') + " failed (exit $LASTEXITCODE)")
  }
}

function Get-BranchHead {
  param(
    [Parameter(Mandatory = $true)]
    [string]$BranchName
  )

  return (& git rev-parse $BranchName).Trim()
}

function Get-RemoteBranchHead {
  param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteName,
    [Parameter(Mandatory = $true)]
    [string]$BranchName
  )

  $remoteLine = (& git ls-remote $RemoteName "refs/heads/$BranchName").Trim()
  if (-not $remoteLine) {
    return ""
  }

  return ($remoteLine -split "\s+")[0]
}

function Test-RemoteMatchesLocal {
  param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteName,
    [Parameter(Mandatory = $true)]
    [string]$BranchName
  )

  $local = Get-BranchHead -BranchName $BranchName
  $remote = Get-RemoteBranchHead -RemoteName $RemoteName -BranchName $BranchName
  return ($local -and $remote -and ($local -eq $remote))
}

function Test-RemoteReachable {
  param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteName,
    [int]$Retries = 3
  )

  for ($attempt = 1; $attempt -le $Retries; $attempt++) {
    & git ls-remote $RemoteName | Out-Null
    if ($LASTEXITCODE -eq 0) {
      return $true
    }

    if ($attempt -lt $Retries) {
      Write-Host ("[Retry] Remote check failed, attempt {0}/{1}, retrying in 2 seconds..." -f $attempt, $Retries) -ForegroundColor Yellow
      Start-Sleep -Seconds 2
    }
  }

  return $false
}

# Keep the effect scoped to the current repository only.
if (-not (Test-Path .git)) {
  throw "No .git directory found. Run this script from the repository root."
}

Invoke-Git --version | Out-Null

# Use Windows certificate store and conservative HTTP settings for GitHub HTTPS push.
Invoke-Git config --local http.sslBackend schannel
Invoke-Git config --local http.version HTTP/1.1
Invoke-Git config --local http.expect false
Invoke-Git config --local http.sslVerify true
Invoke-Git config --local http.maxRequests 1
Invoke-Git config --local core.compression 0
Invoke-Git config --local http.lowSpeedLimit 0
Invoke-Git config --local http.postBuffer 524288000

# Remove broad proxy settings first to avoid affecting other remotes.
& git config --local --unset-all http.proxy 2>$null
& git config --local --unset-all https.proxy 2>$null
& git config --local --unset http.https://github.com.proxy 2>$null

$proxyUrl = "${ProxyScheme}://${ProxyHost}:$ProxyPort"
Invoke-Git config --local "http.https://github.com.proxy" $proxyUrl

Write-Host "[Info] Repository-local GitHub proxy configured: $proxyUrl" -ForegroundColor Green
Write-Host "[Check] Testing read-only connectivity to $Remote ..." -ForegroundColor Cyan
if (-not (Test-RemoteReachable -RemoteName $Remote -Retries $MaxCheckRetries)) {
  Write-Host "[Warn] Remote connectivity check failed. The script will continue so push retries and hash verification can still decide the result." -ForegroundColor Yellow
}

if (-not $Branch) {
  $Branch = (& git rev-parse --abbrev-ref HEAD).Trim()
  if (-not $Branch) {
    $Branch = "main"
  }
}

if ($NoPush) {
  Write-Host "[Done] Local repository config updated. Push was skipped because -NoPush was provided." -ForegroundColor Green
  Write-Host "[Hint] Run: git push -u $Remote $Branch" -ForegroundColor Yellow
  exit 0
}

Write-Host "[Push] Pushing to $Remote/$Branch ..." -ForegroundColor Cyan
$pushSucceeded = $false
for ($attempt = 1; $attempt -le $MaxPushRetries; $attempt++) {
  try {
    Write-Host ("[Attempt] Push {0}/{1}" -f $attempt, $MaxPushRetries) -ForegroundColor DarkCyan
    Invoke-Git push -u $Remote $Branch
    $pushSucceeded = $true
    break
  }
  catch {
    if (Test-RemoteMatchesLocal -RemoteName $Remote -BranchName $Branch) {
      Write-Host "[Info] Push connection was interrupted, but remote HEAD already matches local HEAD." -ForegroundColor Yellow
      $pushSucceeded = $true
      break
    }

    if ($attempt -ge $MaxPushRetries) {
      throw
    }

    Write-Host ("[Retry] Push failed on attempt {0}, retrying in 2 seconds..." -f $attempt) -ForegroundColor Yellow
    Start-Sleep -Seconds 2
  }
}

if (-not $pushSucceeded) {
  throw "Push did not succeed."
}

$local = Get-BranchHead -BranchName $Branch
$remote = Get-RemoteBranchHead -RemoteName $Remote -BranchName $Branch
if ($local -and $remote -and ($local -eq $remote)) {
  Write-Host "[Success] Local and remote hashes match: $local" -ForegroundColor Green
  exit 0
}

Write-Host "[Warn] Push finished but hashes differ: local=$local remote=$remote" -ForegroundColor Yellow
exit 1
