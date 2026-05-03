# =============================================================================
#  IntelliMailbox — Windows installer
# =============================================================================
#  One-liner:
#    powershell -c "irm https://intelliswarm.ai/install.ps1 | iex"
#
#  What this does:
#    1. Auto-installs Java 21 (Temurin) via winget if missing.
#    2. Auto-installs Ollama via winget if missing.
#    3. Pulls the qwen2.5:3b LLM model into Ollama (~2 GB, first run only).
#    4. Downloads the IntelliMailbox fat-jar to %LOCALAPPDATA%\IntelliSwarm\IntelliMailbox
#       and verifies its SHA-256.
#    5. Writes a launcher (intelli-mailbox.cmd) and adds it to your user PATH.
#    6. Launches the app and opens http://localhost:8090/ in your default browser.
#
#  Privacy: nothing leaves your machine. Ollama runs the LLM locally.
#  Uninstall: remove %LOCALAPPDATA%\IntelliSwarm\IntelliMailbox and the PATH entry.
# =============================================================================

$ErrorActionPreference = 'Stop'

# Pinned fallback if GitHub's API is unreachable / rate-limited. Bumping
# this is OPTIONAL — the script's first move is to ask the GitHub API for
# the latest release tag and use that. Set $env:IM_VERSION='x.y.z' to
# bypass the API call (useful for pinned CI installs).
$FALLBACK_VERSION = '0.1.2'

# Always-latest by default. Resolution priority:
#   1. $env:IM_VERSION         → strict pin, no network call
#   2. GitHub releases/latest  → live lookup, what most users hit
#   3. $FALLBACK_VERSION       → if GitHub is unreachable
function Resolve-LatestVersion {
    if ($env:IM_VERSION) { return $env:IM_VERSION }
    try {
        $r = Invoke-RestMethod -Uri 'https://api.github.com/repos/intelliswarm-ai/intelli-mailbox/releases/latest' `
                               -TimeoutSec 8 -ErrorAction Stop
        if ($r.tag_name) {
            return ($r.tag_name -replace '^v','')
        }
    } catch {
        # API offline / rate-limited / 404 — silently fall through.
    }
    return $FALLBACK_VERSION
}

$VERSION       = Resolve-LatestVersion
$INSTALL_DIR   = Join-Path $env:LOCALAPPDATA 'IntelliSwarm\IntelliMailbox'
$MODEL         = 'qwen2.5:3b'
# GitHub Releases hosts the actual jar — `intelliswarm.ai` redirects via the
# website to keep URLs short / brand-aligned. Override IM_JAR_URL to install
# from a local build during development.
$JAR_URL = if ($env:IM_JAR_URL) { $env:IM_JAR_URL } else {
    "https://github.com/intelliswarm-ai/intelli-mailbox/releases/download/v$VERSION/intelli-mailbox-$VERSION.jar"
}

function Write-Step($msg)    { Write-Host "  → $msg" -ForegroundColor Cyan }
function Write-Ok($msg)      { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "  ! $msg" -ForegroundColor Yellow }
function Write-Fail($msg)    { Write-Host "  ✗ $msg" -ForegroundColor Red }
function Write-Banner {
    Write-Host ''
    Write-Host '  ✦ IntelliMailbox' -ForegroundColor Magenta
    Write-Host '    AI-preprocessed inbox · local-by-default' -ForegroundColor DarkGray
    Write-Host "    Installer v$VERSION" -ForegroundColor DarkGray
    Write-Host ''
}

# Pull a fresh PATH from the registry into the current PowerShell session.
# Required after winget installs a tool — the new path entry is in the registry
# but not yet in this process's $env:Path.
function Refresh-Path {
    $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [System.Environment]::GetEnvironmentVariable('Path', 'User')
}

# Returns true if java is on PATH and major version >= 21.
function Test-Java21 {
    $j = Get-Command java -ErrorAction SilentlyContinue
    if (-not $j) { return $false }
    $line = (& java -version 2>&1 | Select-Object -First 1).ToString()
    if ($line -match '"(\d+)') { return ([int]$matches[1] -ge 21) }
    return $false
}

Write-Banner

# ---------- 1. Java 21+ (auto-install via winget) --------------------------
if (-not (Test-Java21)) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Write-Fail 'Java 21+ not found and winget is unavailable on this machine.'
        Write-Host '  Install Java 21 manually from https://adoptium.net/ and rerun.' -ForegroundColor Yellow
        Write-Host '  (winget ships with Windows 10 1809+ via App Installer.)' -ForegroundColor DarkGray
        exit 1
    }
    Write-Step 'Java 21 not found — installing Eclipse Temurin 21 via winget (~150 MB) ...'
    & winget install --id EclipseAdoptium.Temurin.21.JDK -e --silent `
        --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'winget install failed for Temurin 21.'
        Write-Host '  Install manually from https://adoptium.net/ and rerun.' -ForegroundColor Yellow
        exit 1
    }
    Refresh-Path
    if (-not (Test-Java21)) {
        Write-Fail 'Java 21 installed but not yet on PATH.'
        Write-Host '  Open a NEW PowerShell window and rerun the installer.' -ForegroundColor Yellow
        exit 1
    }
}
Write-Ok "Java 21+ detected"

# ---------- 2. Ollama (auto-install via winget) ----------------------------
if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Write-Fail 'Ollama not found and winget is unavailable on this machine.'
        Write-Host '  Install Ollama from https://ollama.com/download/windows and rerun.' -ForegroundColor Yellow
        exit 1
    }
    Write-Step 'Ollama not found — installing via winget ...'
    & winget install --id Ollama.Ollama -e --silent `
        --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'winget install failed for Ollama.'
        Write-Host '  Install manually from https://ollama.com/download/windows and rerun.' -ForegroundColor Yellow
        exit 1
    }
    Refresh-Path
    if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
        Write-Fail 'Ollama installed but not yet on PATH.'
        Write-Host '  Open a NEW PowerShell window and rerun the installer.' -ForegroundColor Yellow
        exit 1
    }
}
Write-Ok 'Ollama detected'

# ---------- 3. Pull the LLM model if missing --------------------------------
$models = & ollama list 2>$null
if ($models -notmatch [regex]::Escape($MODEL)) {
    Write-Step "Pulling LLM model: $MODEL  (~2 GB, one-time, runs entirely on your machine)"
    & ollama pull $MODEL
    if ($LASTEXITCODE -ne 0) { Write-Fail 'ollama pull failed'; exit 1 }
}
Write-Ok "Model ready: $MODEL"

# ---------- 4. Download the jar + verify SHA-256 ---------------------------
New-Item -ItemType Directory -Force -Path $INSTALL_DIR | Out-Null
$jarPath = Join-Path $INSTALL_DIR 'intelli-mailbox.jar'
Write-Step "Downloading IntelliMailbox v$VERSION ..."
try {
    Invoke-WebRequest -Uri $JAR_URL -OutFile $jarPath -UseBasicParsing
} catch {
    Write-Fail "Download failed: $($_.Exception.Message)"
    Write-Host "  URL: $JAR_URL" -ForegroundColor DarkGray
    exit 1
}
$sizeMB = [math]::Round((Get-Item $jarPath).Length / 1MB, 1)
Write-Ok "Downloaded: $jarPath ($sizeMB MB)"

# Verify SHA-256 against the published .sha256 sidecar. Protects against MITM
# tampering on the download URL beyond the HTTPS guarantee, and surfaces any
# corrupted partial downloads early.
Write-Step 'Verifying SHA-256 ...'
try {
    $expected = (Invoke-WebRequest -Uri "$JAR_URL.sha256" -UseBasicParsing).Content.Trim().ToLower()
} catch {
    Write-Fail "Couldn't fetch checksum sidecar: $($_.Exception.Message)"
    Remove-Item $jarPath -Force
    exit 1
}
$actual = (Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLower()
if ($expected -ne $actual) {
    Write-Fail 'CHECKSUM MISMATCH — file may be tampered. Aborting.'
    Write-Host "  expected: $expected" -ForegroundColor DarkGray
    Write-Host "  actual:   $actual"   -ForegroundColor DarkGray
    Remove-Item $jarPath -Force
    exit 1
}
Write-Ok "SHA-256 verified: $($actual.Substring(0,12))…"

# ---------- 5. Write a launcher --------------------------------------------
$launcher = Join-Path $INSTALL_DIR 'intelli-mailbox.cmd'
@"
@echo off
rem IntelliMailbox launcher (Windows). Use 'javaw' to detach from the console.
start "IntelliMailbox" /B javaw -jar "$jarPath" %*
"@ | Out-File -FilePath $launcher -Encoding ASCII -Force

# ---------- 6. Add to user PATH --------------------------------------------
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$onPath = ($userPath -split ';') -contains $INSTALL_DIR
if (-not $onPath) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$INSTALL_DIR", 'User')
    Write-Ok "Added $INSTALL_DIR to your user PATH"
}

# ---------- 7. Auto-launch -------------------------------------------------
# Streamline: end the install by launching the app and opening the browser at
# the UI. One command goes from nothing-installed to working app.
# Skip if INSTALL_NO_LAUNCH is set (CI / scripted installs).
if (-not $env:INSTALL_NO_LAUNCH) {
    Write-Step 'Launching IntelliMailbox ...'
    Start-Process -FilePath 'javaw' -ArgumentList '-jar', $jarPath -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 4
    # Open the UI in the user's default browser. The app itself also opens a
    # tab inside the attached Chrome it spawns — this is a fallback for users
    # who closed that Chrome before the UI loaded.
    Start-Process 'http://localhost:8090/'
}

# ---------- 8. Done --------------------------------------------------------
Write-Host ''
Write-Host '  Installation complete.' -ForegroundColor Green
Write-Host ''
Write-Host '  ▸ App URL:           http://localhost:8090/' -ForegroundColor White
Write-Host '  ▸ Restart later:     intelli-mailbox  (in a NEW terminal so PATH refreshes)' -ForegroundColor White
Write-Host '  ▸ Sign in to Gmail   in the Chrome window the app opens.' -ForegroundColor DarkGray
Write-Host ''
