# TokenGuard installer for Windows (plan §7 M8). Use:
#   irm https://tokenguard.io/install.ps1 | iex
$ErrorActionPreference = 'Stop'

$repo = 'tokenguard/token-guard'
$version = if ($env:TOKEN_GUARD_VERSION) { $env:TOKEN_GUARD_VERSION } else { 'latest' }
$installDir = if ($env:TOKEN_GUARD_INSTALL_DIR) { $env:TOKEN_GUARD_INSTALL_DIR } else { "$env:LOCALAPPDATA\token-guard" }

$arch = if ([Environment]::Is64BitOperatingSystem) { 'x64' } else { throw 'unsupported architecture' }
$target = "windows-$arch"
$bin = "token-guard-$target.exe"
$base = if ($version -eq 'latest') {
  "https://github.com/$repo/releases/latest/download"
} else {
  "https://github.com/$repo/releases/download/$version"
}

Write-Host "Installing token-guard ($target, $version)..."
$tmp = New-Item -ItemType Directory -Path ([System.IO.Path]::GetTempPath()) -Name ([guid]::NewGuid())
try {
  $exe = Join-Path $tmp 'token-guard.exe'
  Invoke-WebRequest -Uri "$base/$bin" -OutFile $exe -UseBasicParsing

  try {
    $sumFile = Join-Path $tmp 'sum'
    Invoke-WebRequest -Uri "$base/$bin.sha256" -OutFile $sumFile -UseBasicParsing
    $expected = (Get-Content $sumFile -Raw).Split(' ')[0].Trim()
    $actual = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
    if ($expected -ne $actual) { throw "checksum mismatch (expected $expected, got $actual)" }
    Write-Host 'checksum verified'
  } catch {
    Write-Host 'warning: no published checksum; skipping verification'
  }

  New-Item -ItemType Directory -Force -Path $installDir | Out-Null
  Move-Item -Force $exe (Join-Path $installDir 'token-guard.exe')
  Write-Host "installed to $installDir\token-guard.exe"

  $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
  if ($userPath -notlike "*$installDir*") {
    Write-Host "note: add $installDir to your PATH"
  }
} finally {
  Remove-Item -Recurse -Force $tmp
}

@"

Add this to your IDE's mcp.json:

  {
    "mcpServers": {
      "token-guard": {
        "command": "$installDir\token-guard.exe",
        "args": ["--license-key", "YOUR_LICENSE_KEY"],
        "env": { "TOKEN_GUARD_CACHE_DIR": "~/.token-guard/cache" }
      }
    }
  }

No license key? TokenGuard runs in free-local mode. Get a key at https://tokenguard.io.
"@ | Write-Host
