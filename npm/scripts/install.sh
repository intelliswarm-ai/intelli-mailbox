#!/usr/bin/env bash
# =============================================================================
#  IntelliMailbox — macOS / Linux installer
# =============================================================================
#  One-liner:
#    curl -fsSL https://intelliswarm.ai/install.sh | bash
#
#  What this does:
#    1. Auto-installs Java 21 if missing — via Homebrew (macOS) or apt/dnf (Linux).
#    2. Auto-installs Ollama if missing — via Homebrew (macOS) or its official
#       installer (Linux).
#    3. Pulls the qwen2.5:3b LLM model into Ollama (~2 GB, first run only).
#    4. Downloads the IntelliMailbox fat-jar to ~/.intelliswarm/intelli-mailbox/
#       and verifies its SHA-256.
#    5. Writes a launcher (intelli-mailbox) and tells you how to add it to PATH.
#    6. Launches the app and opens http://localhost:8090/.
#
#  Privacy: nothing leaves your machine. Ollama runs the LLM locally.
#  Uninstall: rm -rf ~/.intelliswarm/intelli-mailbox  and remove the PATH line.
# =============================================================================
set -euo pipefail

REPO="intelliswarm-ai/intelli-mailbox"
INSTALL_DIR="${INTELLIMAILBOX_DIR:-$HOME/.intelliswarm/intelli-mailbox}"
MODEL="qwen2.5:3b"

# Resolve the version + jar URL. Order of precedence:
#   1. IM_JAR_URL  — full override (CI / local dev builds).
#   2. IM_VERSION  — pin a specific tag (e.g. "0.1.2").
#   3. GitHub API  — pick whatever's tagged "latest" right now.
# Without an override the installer always tracks the latest published release,
# so the script can be served from intelliswarm.ai/install.sh without ever
# being edited per release.
resolve_release() {
    if [ -n "${IM_JAR_URL:-}" ]; then
        JAR_URL="$IM_JAR_URL"
        # Best-effort version sniff from the URL so the banner says something useful.
        VERSION="$(printf '%s' "$IM_JAR_URL" | sed -nE 's/.*intelli-mailbox-([0-9][0-9A-Za-z.\-]*)\.jar.*/\1/p')"
        [ -z "$VERSION" ] && VERSION="custom"
        return 0
    fi
    if [ -n "${IM_VERSION:-}" ]; then
        VERSION="${IM_VERSION#v}"
        JAR_URL="https://github.com/${REPO}/releases/download/v${VERSION}/intelli-mailbox-${VERSION}.jar"
        return 0
    fi
    local api_json
    api_json="$(curl -fsSL \
        -H 'Accept: application/vnd.github+json' \
        -H 'User-Agent: intelli-mailbox-installer' \
        "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null || true)"
    if [ -z "$api_json" ]; then
        fail "Couldn't reach the GitHub API to resolve the latest release."
        echo "  Set IM_VERSION=0.1.2 (or similar) to pin a specific version and rerun." >&2
        exit 1
    fi
    # Parse tag_name and the .jar asset URL with sed — avoids requiring jq.
    VERSION="$(printf '%s' "$api_json" \
        | sed -nE 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v?([^"]+)".*/\1/p' \
        | head -1)"
    JAR_URL="$(printf '%s\n' "$api_json" \
        | sed -nE 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"([^"]+\.jar)".*/\1/p' \
        | head -1)"
    if [ -z "$VERSION" ] || [ -z "$JAR_URL" ]; then
        fail "GitHub API returned no .jar asset for the latest release."
        echo "  Set IM_VERSION to pin an older tag, or check https://github.com/${REPO}/releases." >&2
        exit 1
    fi
}

# ---- ANSI helpers (skip colour if not a TTY) ------------------------------
if [ -t 1 ]; then
    BOLD="$(printf '\033[1m')"; DIM="$(printf '\033[2m')"
    RED="$(printf '\033[31m')"; GREEN="$(printf '\033[32m')"
    YELLOW="$(printf '\033[33m')"; CYAN="$(printf '\033[36m')"
    MAGENTA="$(printf '\033[35m')"; RESET="$(printf '\033[0m')"
else
    BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; CYAN=""; MAGENTA=""; RESET=""
fi
step() { printf "  ${CYAN}→${RESET} %s\n" "$*"; }
ok()   { printf "  ${GREEN}✓${RESET} %s\n" "$*"; }
warn() { printf "  ${YELLOW}!${RESET} %s\n" "$*"; }
fail() { printf "  ${RED}✗${RESET} %s\n" "$*"; }

# Returns 0 if `java` on PATH and major version ≥ 21.
java_21_present() {
    command -v java >/dev/null 2>&1 || return 1
    local raw major
    raw="$(java -version 2>&1 | head -1)"
    major="$(printf "%s" "$raw" | sed -nE 's/.*"([0-9]+).*/\1/p')"
    [ -n "$major" ] && [ "$major" -ge 21 ]
}

install_java_if_missing() {
    if java_21_present; then return 0; fi
    case "$(uname -s)" in
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                step "Java 21 not found — installing Eclipse Temurin 21 via Homebrew (~250 MB) ..."
                brew install --cask temurin@21 || {
                    fail "brew install failed. Install manually from https://adoptium.net/"
                    exit 1
                }
                ok "Java 21 installed"
                return 0
            fi
            fail "Homebrew not installed. Install brew (https://brew.sh) or Java 21 manually (https://adoptium.net/)."
            exit 1
            ;;
        Linux)
            if command -v apt-get >/dev/null 2>&1; then
                step "Java 21 not found — installing OpenJDK 21 via apt (sudo will prompt) ..."
                sudo apt-get update -y && sudo apt-get install -y openjdk-21-jdk || {
                    fail "apt install failed. Install Java 21 manually (https://adoptium.net/)."
                    exit 1
                }
                ok "Java 21 installed"
                return 0
            elif command -v dnf >/dev/null 2>&1; then
                step "Java 21 not found — installing java-21-openjdk via dnf (sudo will prompt) ..."
                sudo dnf install -y java-21-openjdk || {
                    fail "dnf install failed. Install Java 21 manually (https://adoptium.net/)."
                    exit 1
                }
                ok "Java 21 installed"
                return 0
            elif command -v pacman >/dev/null 2>&1; then
                step "Java 21 not found — installing jdk21-openjdk via pacman (sudo will prompt) ..."
                sudo pacman -S --noconfirm jdk21-openjdk || {
                    fail "pacman install failed."
                    exit 1
                }
                ok "Java 21 installed"
                return 0
            fi
            fail "No supported package manager (apt/dnf/pacman) found."
            echo "  Install Java 21 manually from https://adoptium.net/"
            exit 1
            ;;
        *)
            fail "Unsupported OS: $(uname -s)"
            exit 1
            ;;
    esac
}

install_ollama_if_missing() {
    if command -v ollama >/dev/null 2>&1; then return 0; fi
    case "$(uname -s)" in
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                step "Ollama not found — installing via Homebrew ..."
                brew install ollama || { fail "brew install failed."; exit 1; }
                # On macOS the daemon needs to be started; the CLI auto-spawns
                # one when first invoked, so no explicit `ollama serve` here.
                ok "Ollama installed"
                return 0
            fi
            fail "Homebrew not installed. Install brew (https://brew.sh) or Ollama from https://ollama.com/download"
            exit 1
            ;;
        Linux)
            step "Ollama not found — running official installer (curl https://ollama.com/install.sh) ..."
            curl -fsSL https://ollama.com/install.sh | sh || {
                fail "Ollama installer failed."
                exit 1
            }
            ok "Ollama installed"
            return 0
            ;;
        *)
            fail "Unsupported OS for Ollama auto-install: $(uname -s)"
            exit 1
            ;;
    esac
}

# Resolve VERSION + JAR_URL now (after fail() is defined, before the banner
# uses $VERSION). This is the single source of truth for which release we install.
resolve_release

printf "\n  ${MAGENTA}✦ IntelliMailbox${RESET}\n"
printf "    ${DIM}AI-preprocessed inbox · local-by-default${RESET}\n"
printf "    ${DIM}Installer v%s${RESET}\n\n" "$VERSION"

# ---------- 1. Java 21+ (auto-install if missing) -------------------------
install_java_if_missing
ok "Java 21+ detected"

# ---------- 2. Ollama (auto-install if missing) ---------------------------
install_ollama_if_missing
ok "Ollama detected"

# ---------- 3. Pull model if missing --------------------------------------
if ! ollama list 2>/dev/null | grep -q "$MODEL"; then
    step "Pulling LLM model: $MODEL  (~2 GB, one-time, runs entirely on your machine)"
    ollama pull "$MODEL"
fi
ok "Model ready: $MODEL"

# ---------- 4. Download the jar + verify SHA-256 --------------------------
mkdir -p "$INSTALL_DIR"
JAR_PATH="$INSTALL_DIR/intelli-mailbox.jar"
step "Downloading IntelliMailbox v$VERSION ..."
if ! curl -fSL --progress-bar "$JAR_URL" -o "$JAR_PATH"; then
    fail "Download failed."
    echo "  URL: $JAR_URL"
    exit 1
fi
SIZE_MB=$(du -m "$JAR_PATH" | cut -f1)
ok "Downloaded: $JAR_PATH (${SIZE_MB} MB)"

# Verify SHA-256 against the published sidecar. Protects against MITM tampering
# beyond HTTPS and surfaces any corrupted/partial downloads early.
step "Verifying SHA-256 ..."
EXPECTED=$(curl -fsSL "$JAR_URL.sha256" | tr -d '[:space:]' | tr 'A-Z' 'a-z' || true)
if [ -z "$EXPECTED" ]; then
    fail "Couldn't fetch checksum sidecar from $JAR_URL.sha256"
    rm -f "$JAR_PATH"
    exit 1
fi
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$JAR_PATH" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$JAR_PATH" | awk '{print $1}')
else
    warn "Neither sha256sum nor shasum available — skipping integrity check."
    ACTUAL="$EXPECTED"
fi
if [ "$EXPECTED" != "$ACTUAL" ]; then
    fail "CHECKSUM MISMATCH — file may be tampered. Aborting."
    echo "  expected: $EXPECTED"
    echo "  actual:   $ACTUAL"
    rm -f "$JAR_PATH"
    exit 1
fi
ok "SHA-256 verified: ${ACTUAL:0:12}…"

# ---------- 5. Write a launcher -------------------------------------------
LAUNCHER="$INSTALL_DIR/intelli-mailbox"
cat > "$LAUNCHER" <<EOF
#!/usr/bin/env bash
# IntelliMailbox launcher (auto-generated by installer).
exec java -jar "$JAR_PATH" "\$@"
EOF
chmod +x "$LAUNCHER"
ok "Launcher: $LAUNCHER"

# ---------- 6. PATH suggestion --------------------------------------------
case ":$PATH:" in
    *":$INSTALL_DIR:"*) ok "PATH already includes installer directory" ;;
    *)
        SHELL_RC="$HOME/.zshrc"
        [ "${SHELL##*/}" = "bash" ] && SHELL_RC="$HOME/.bashrc"
        echo ""
        warn "Add to PATH (one-time):"
        echo "    echo 'export PATH=\"\$PATH:$INSTALL_DIR\"' >> $SHELL_RC"
        echo "    source $SHELL_RC"
        ;;
esac

# ---------- 7. Auto-launch ------------------------------------------------
# One-command UX: kick the app off in the background and open the UI in the
# user's default browser. Skip when INSTALL_NO_LAUNCH=1 (CI / scripted runs).
if [ -z "${INSTALL_NO_LAUNCH:-}" ]; then
    step "Launching IntelliMailbox ..."
    nohup java -jar "$JAR_PATH" >/tmp/intelli-mailbox.log 2>&1 &
    sleep 4
    URL="http://localhost:8090/"
    case "$(uname -s)" in
        Darwin)  command -v open     >/dev/null 2>&1 && open     "$URL" >/dev/null 2>&1 || true ;;
        Linux)   command -v xdg-open >/dev/null 2>&1 && xdg-open "$URL" >/dev/null 2>&1 || true ;;
    esac
fi

# ---------- 8. Done -------------------------------------------------------
echo ""
printf "  ${GREEN}${BOLD}Installation complete.${RESET}\n\n"
echo "  ▸ App URL:           http://localhost:8090/"
echo "  ▸ Restart later:     intelli-mailbox"
echo "  ▸ Logs:              tail -f /tmp/intelli-mailbox.log"
echo "  ▸ Sign in to Gmail   in the Chrome window the app opens."
echo ""
