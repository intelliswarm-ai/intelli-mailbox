#!/usr/bin/env bash
# ============================================================================
# Local end-to-end test harness for the self-update flow (issue #3).
#
# What this does
#   Spins up a tiny HTTP server on localhost that mimics GitHub's
#   /releases/latest endpoint, hosting a release.json that points the
#   running app at an MSI sitting in target/installers/. With the override
#   env var below, the installed app polls THIS server instead of GitHub,
#   sees a "newer" version, downloads it, and runs msiexec — exercising
#   every line of UpdateChecker / UpdateInstaller / UpdateController and
#   the banner state machine, no GitHub release needed.
#
# How to use
#   1. Build an MSI you want to serve as the "new" version:
#        # On Windows:  build-msi-debug.bat
#        # The MSI ends up under target/installers/.
#   2. Make sure an OLDER version is actually installed on Windows
#      (the in-app banner only shows when the served version is greater
#      than the running version). Easiest: install 0.1.4 from the GitHub
#      release first, build target/installers/ at 0.1.5, point the
#      installed 0.1.4 at this server.
#   3. From WSL (or any shell with python3), run:
#        ./scripts/serve-mock-release.sh
#      Pass --version 0.1.99 to override the version reported.
#   4. The script prints the env var to set on Windows. In a Windows
#      shell, BEFORE launching Intelli Mailbox.exe, set it, e.g.:
#        set INTELLIMAILBOX_RELEASES_URL=http://localhost:9999/release.json
#        "%LOCALAPPDATA%\Intelli Mailbox\Intelli Mailbox.exe"
#      Or edit "%LOCALAPPDATA%\Intelli Mailbox\app\Intelli Mailbox.cfg"
#      and add a -D form:
#        java-options=-Dintellimailbox.update.releases-url=http://localhost:9999/release.json
#   5. Open the app, wait for the banner ("X.Y.Z is available"), click
#      "Download & install". Progress bar fills, msiexec launches, app
#      restarts.
#
# Cleanup
#   Ctrl-C this script. Remove the env var from your Windows shell.
# ============================================================================

set -euo pipefail

PORT="${PORT:-9999}"
SERVE_DIR="${SERVE_DIR:-$(mktemp -d -t intelli-mailbox-mock-XXXX)}"
VERSION="0.1.99"
ASSET_PATH=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        --port)    PORT="$2"; shift 2 ;;
        --msi)     ASSET_PATH="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,40p' "$0"
            exit 0
            ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

# Auto-discover an MSI under target/installers/ if the caller didn't pin one.
if [[ -z "$ASSET_PATH" ]]; then
    ASSET_PATH=$(ls -1t "$(dirname "$0")/../target/installers/"*.msi 2>/dev/null | head -1 || true)
fi
if [[ -z "$ASSET_PATH" || ! -f "$ASSET_PATH" ]]; then
    echo "ERROR: no .msi found. Build one (build-msi-debug.bat on Windows)" >&2
    echo "       or pass --msi /path/to/installer.msi" >&2
    exit 1
fi

ASSET_NAME=$(basename "$ASSET_PATH")
ASSET_SIZE=$(stat -c%s "$ASSET_PATH" 2>/dev/null || stat -f%z "$ASSET_PATH")

# Spaces in the filename (e.g. "Intelli Mailbox-0.1.4.msi") would produce
# an invalid URL when embedded in the JSON's browser_download_url, so the
# JVM's URI.create rejects it and the download fails silently. Url-encode
# spaces for the URL while keeping the on-disk name unchanged so msiexec
# launches against the file under its real Windows name.
ASSET_NAME_URL=$(printf '%s' "$ASSET_NAME" | sed 's/ /%20/g')

# Symlink the MSI into the served dir under its real filename so the
# download lands with the correct name and msiexec is happy.
ln -sf "$(realpath "$ASSET_PATH")" "$SERVE_DIR/$ASSET_NAME"

# Mimic GitHub's /releases/latest JSON shape — only the fields UpdateChecker
# reads (tag_name, html_url, assets[].name / browser_download_url / size).
cat > "$SERVE_DIR/release.json" <<JSON
{
  "tag_name": "v${VERSION}",
  "html_url": "http://localhost:${PORT}/release-notes.html",
  "assets": [
    {
      "name": "${ASSET_NAME}",
      "browser_download_url": "http://localhost:${PORT}/${ASSET_NAME_URL}",
      "size": ${ASSET_SIZE}
    }
  ]
}
JSON

cat > "$SERVE_DIR/release-notes.html" <<HTML
<!doctype html><title>Mock release ${VERSION}</title>
<h1>Mock release ${VERSION}</h1>
<p>This is a local test fixture for the self-update flow.</p>
HTML

cat <<EOF
==============================================================================
 Mock release server ready
==============================================================================
  Version served: ${VERSION}
  MSI asset:      ${ASSET_NAME}  (${ASSET_SIZE} bytes)
  Serve dir:      ${SERVE_DIR}
  URL:            http://localhost:${PORT}/release.json

  In your Windows shell, BEFORE launching Intelli Mailbox.exe, set:

      set INTELLIMAILBOX_RELEASES_URL=http://localhost:${PORT}/release.json

  Or pass it as a JVM property by editing Intelli Mailbox.cfg.

  Then open the app and watch the banner. Ctrl-C here when done.
==============================================================================
EOF

cd "$SERVE_DIR"
exec python3 -m http.server "$PORT"
