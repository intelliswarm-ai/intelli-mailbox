#!/usr/bin/env bash
#
# Intelli-mailbox launcher
#
# What it does:
#   1. Auto-launches your real Chrome with a dedicated CDP profile at
#      ~/.intelliswarm/intelli-mailbox-chrome (separate from your everyday Chrome).
#   2. Boots the Spring Boot web app on http://localhost:8090.
#   3. Pre-warms Gmail; opens the Intelli-mailbox UI in a new tab in the same Chrome.
#   4. As you click "Refresh inbox", every visible email is analyzed locally —
#      badges + structured CTAs stream back over SSE.
#
# Default LLM:  local Ollama (privacy by default).
# Cloud opt-in: SPRING_PROFILES_ACTIVE=openai-mini OPENAI_API_KEY=sk-… ./run.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PROFILE_DIR="${INTELLIMAILBOX_PROFILE_DIR:-$HOME/.intelliswarm/intelli-mailbox-chrome}"

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-ollama}"

MVN="mvn"
[ -x "./mvnw" ] && MVN="./mvnw"

# WSL2 detection: when the script runs under WSL, java's os.name=Linux and
# RealChromeLauncher searches /usr/bin/google-chrome (won't exist). Real Chrome
# lives on Windows under /mnt/c/. We export the chrome-path + user-data-dir as
# env vars (NOT spring-boot.run.arguments) because Maven's argument tokenizer
# splits values at spaces — and "Program Files" is a path with a space that
# would otherwise get truncated to "/mnt/c/Program".
if grep -qi -e microsoft -e wsl /proc/version 2>/dev/null; then
  for p in \
      "/mnt/c/Program Files/Google/Chrome/Application/chrome.exe" \
      "/mnt/c/Program Files (x86)/Google/Chrome/Application/chrome.exe" \
      "/mnt/c/Users/$USER/AppData/Local/Google/Chrome/Application/chrome.exe" \
      "/mnt/c/Program Files/Microsoft/Edge/Application/msedge.exe"; do
    if [ -x "$p" ]; then
      export SWARMAI_TOOLS_BROWSER_CHROME_PATH="$p"
      # Mirror the Linux profile dir to a Windows path Chrome.exe can write to.
      WIN_USER="$(/mnt/c/Windows/System32/cmd.exe /c "echo %USERNAME%" 2>/dev/null | tr -d '\r\n')"
      if [ -n "$WIN_USER" ]; then
        WIN_PROFILE="/mnt/c/Users/$WIN_USER/AppData/Local/intelli-mailbox-chrome"
        mkdir -p "$WIN_PROFILE"
        PROFILE_DIR="$WIN_PROFILE"
      fi
      export SWARMAI_TOOLS_BROWSER_USER_DATA_DIR="$PROFILE_DIR"
      echo "[run.sh] WSL2 detected — using Windows Chrome at: $p"
      echo "[run.sh] Profile dir: $PROFILE_DIR"
      break
    fi
  done
fi

# When SWARMAI_TOOLS_BROWSER_USER_DATA_DIR is set above we drop the cli flag
# so the env var (which handles spaces correctly) is the source of truth.
USER_DATA_DIR_ARG=""
if [ -z "${SWARMAI_TOOLS_BROWSER_USER_DATA_DIR:-}" ]; then
  USER_DATA_DIR_ARG="--swarmai.tools.browser.user-data-dir=$PROFILE_DIR"
fi

echo "==== [run.sh] launch summary ===================================="
echo "  CHROME_PATH    : ${SWARMAI_TOOLS_BROWSER_CHROME_PATH:-(unset, will auto-detect)}"
echo "  USER_DATA_DIR  : ${SWARMAI_TOOLS_BROWSER_USER_DATA_DIR:-$PROFILE_DIR}"
echo "  PROFILE        : $SPRING_PROFILES_ACTIVE"
echo "================================================================="

exec $MVN -DskipTests spring-boot:run \
    -Dspring-boot.run.arguments="\
--swarmai.tools.browser.enabled=true \
$USER_DATA_DIR_ARG \
--swarmai.tools.browser.allowed-hosts=google.com,gmail.com \
--server.port=8090"
