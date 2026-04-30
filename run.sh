#!/usr/bin/env bash
#
# IntelliMailbox launcher
#
# What it does:
#   1. Auto-launches your real Chrome with a dedicated CDP profile at
#      ~/.intelliswarm/intellimailbox-chrome (separate from your everyday Chrome).
#   2. Boots the Spring Boot web app on http://localhost:8090.
#   3. Pre-warms Gmail; opens the IntelliMailbox UI in a new tab in the same Chrome.
#   4. As you click "Refresh inbox", every visible email is analyzed locally —
#      badges + structured CTAs stream back over SSE.
#
# Default LLM:  local Ollama (privacy by default).
# Cloud opt-in: SPRING_PROFILES_ACTIVE=openai-mini OPENAI_API_KEY=sk-… ./run.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PROFILE_DIR="${INTELLIMAILBOX_PROFILE_DIR:-$HOME/.intelliswarm/intellimailbox-chrome}"

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-ollama}"

MVN="mvn"
[ -x "./mvnw" ] && MVN="./mvnw"

exec $MVN -q -DskipTests spring-boot:run \
    -Dspring-boot.run.arguments="\
--swarmai.tools.browser.enabled=true \
--swarmai.tools.browser.user-data-dir=$PROFILE_DIR \
--swarmai.tools.browser.allowed-hosts=google.com,gmail.com \
--server.port=8090"
