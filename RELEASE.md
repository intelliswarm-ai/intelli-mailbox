# Releasing IntelliMailbox

This repo publishes to two places on every push to `main`:

- **GitHub Releases** — the fat JAR and its SHA-256 sidecar, downloaded by the install scripts on `intelliswarm.ai`.
- **npm** — the `intelli-mailbox` CLI wrapper, what users get with `npm install -g intelli-mailbox`.

Plus the website (`intelliswarm.ai/products/intelli-mailbox`) hosts the install scripts at the root URL and the product page.

---

## One-time setup

### 1. Push the repo to GitHub

The repo expects to live at **`github.com/intelliswarm/intelli-mailbox`**. The install scripts and the npm package both hardcode that URL.

```bash
cd D:\Intelliswarm.ai\intelli-mailbox
git init -b main
git add .
git commit -m "init"
git remote add origin git@github.com:intelliswarm/intelli-mailbox.git
git push -u origin main
```

The first push triggers `.github/workflows/release.yml`. With no `NPM_TOKEN` set yet, it will:
- ✅ Build the fat JAR + SHA-256
- ✅ Create release `v0.1.0` on GitHub with both files attached
- ⚠ Skip npm publish (logs a warning — that's expected at this point)

### 2. Add the `NPM_TOKEN` secret

This unlocks step 3 of the workflow.

1. Sign in to npmjs.com (or create the account if needed).
2. Reserve the name from the package dir to confirm it's available:
   ```bash
   cd D:\Intelliswarm.ai\intelli-mailbox\npm
   npm login
   npm publish --dry-run
   ```
   If `npm publish --dry-run` shows the tarball contents (≈ 8.5 kB, 6 files) without errors, the name is yours.
3. Generate an **Automation** token at <https://www.npmjs.com/settings/YOUR_USER/tokens>. Automation tokens bypass 2FA, which is what CI needs.
4. Add it as a repo secret: `Settings → Secrets and variables → Actions → New repository secret` → name `NPM_TOKEN`, value the token.

After this, the next push to `main` (any code change, or just bump `<version>` in `pom.xml`) will publish to npm automatically.

### 3. Deploy the website

The website at `D:\Intelliswarm.ai\intelliswarm.ai\website\` already has:

- `src/install.ps1` and `src/install.sh` — registered in `angular.json` build assets so they ship at the **root** (`intelliswarm.ai/install.ps1` / `/install.sh`)
- `src/app/pages/products/intelli-mailbox/` — the product page at `/products/intelli-mailbox` (private preview: not in nav, `noindex,nofollow`, robots.txt `Disallow`)

Build + deploy with your existing flow:

```bash
cd D:\Intelliswarm.ai\intelliswarm.ai\website
npm install        # if dependencies changed
ng build           # produces dist/intelliswarm-website
# … then your usual deploy step (S3 / nginx / Docker / whatever)
```

After deploy, smoke-test:

```bash
curl -fsS https://intelliswarm.ai/install.sh  | head -1   # → "#!/usr/bin/env bash"
curl -fsS https://intelliswarm.ai/install.ps1 | head -1   # → "# ====... IntelliMailbox installer"
curl -fsS -o /dev/null -w "%{http_code}\n" https://intelliswarm.ai/products/intelli-mailbox/   # → 200
```

---

## Cutting subsequent releases

The version in `pom.xml` is the source of truth. Bump it on `main`:

```bash
# Edit pom.xml: <version>0.1.0</version> → <version>0.2.0</version>
git add pom.xml
git commit -m "release 0.2.0"
git push origin main
```

The workflow will then:

1. Build `target/intelli-mailbox-0.2.0.jar` and `…jar.sha256`.
2. Create release `v0.2.0` on GitHub with both files attached (or update if the tag already exists — idempotent).
3. Sync `install/install.{ps1,sh}` → `npm/scripts/`, set `npm/package.json` version to `0.2.0`, and `npm publish --access public`. If `0.2.0` is already on npm, it skips cleanly without erroring.

Users who already installed via npm pick up the new version with:

```bash
npm install -g intelli-mailbox    # idempotent — pulls latest
intelli-mailbox install           # downloads the new jar
```

Users who installed via the direct script just need to re-run their one-liner.

---

## Verifying a release end-to-end

After a successful workflow run:

```bash
# 1. GitHub Release exists with both assets
gh release view v0.2.0 -R intelliswarm/intelli-mailbox

# 2. JAR is downloadable and matches its sha256
curl -fsSL -o /tmp/im.jar       https://github.com/intelliswarm/intelli-mailbox/releases/download/v0.2.0/intelli-mailbox-0.2.0.jar
curl -fsSL                       https://github.com/intelliswarm/intelli-mailbox/releases/download/v0.2.0/intelli-mailbox-0.2.0.jar.sha256
sha256sum /tmp/im.jar           # → should match the .sha256 file content

# 3. npm package is on the registry
npm view intelli-mailbox version    # → 0.2.0
npm view intelli-mailbox dist.tarball

# 4. Website serves the install scripts
curl -fsS https://intelliswarm.ai/install.sh  | grep VERSION
curl -fsS https://intelliswarm.ai/install.ps1 | grep VERSION

# 5. End-to-end install (in a clean VM / container ideally)
npm install -g intelli-mailbox      # should land you on http://localhost:8090/
```

---

## Pieces of the system, at a glance

```
intelli-mailbox/                      ← THIS REPO (push to github.com/intelliswarm/intelli-mailbox)
├── pom.xml                           version is the release source of truth
├── src/main/...                      Java app
├── install/                          canonical install scripts
│   ├── install.ps1
│   └── install.sh
├── npm/                              npm wrapper package
│   ├── package.json
│   ├── bin/intelli-mailbox.js        smart launcher
│   ├── bin/postinstall.js            runs installer after `npm i -g`
│   └── scripts/                      copies of install.{ps1,sh} (synced by CI)
└── .github/workflows/release.yml     CI: builds JAR → GitHub Release → npm publish

intelliswarm.ai/website/              SEPARATE WEBSITE REPO (deployed independently)
└── src/
    ├── install.ps1                   ← copy from intelli-mailbox/install/install.ps1
    ├── install.sh                    ← copy from intelli-mailbox/install/install.sh
    └── app/pages/products/intelli-mailbox/
                                      private product page at /products/intelli-mailbox
```

The website's `install.ps1` / `install.sh` need to be re-synced from the canonical copies in `intelli-mailbox/install/` whenever those change. There's no automation for this yet — easiest path is `cp` before each website deploy:

```bash
cp ../intelli-mailbox/install/install.ps1 src/install.ps1
cp ../intelli-mailbox/install/install.sh  src/install.sh
ng build && ./deploy.sh
```

Or set up a tiny cron/CI job that mirrors them. Worth doing once the install scripts stop changing weekly.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Workflow fails on `softprops/action-gh-release` with `403` | The default `GITHUB_TOKEN` doesn't have release permission on this repo. Confirm the workflow's `permissions: contents: write` block is present. |
| `npm publish` fails with `EPUBLISHCONFLICT` / `403 Forbidden — You cannot publish over the previously published versions` | Bump `<version>` in `pom.xml` — npm doesn't allow re-publishing the same version. The workflow's `npm view ... ` guard normally catches this and skips, but a partial earlier publish can leave npm seeing the version when the workflow doesn't. |
| Workflow logs `NPM_TOKEN secret not set — skipping npm publish.` | Add the secret per "One-time setup" step 2 above. |
| `intelliswarm.ai/install.ps1` returns the Angular index page | `src/install.ps1` not registered in `angular.json` build assets, or not deployed. Confirm with `grep install.ps1 angular.json` — should appear in the `assets` array. |
| Install script downloads jar but checksum verify fails | The `.sha256` and `.jar` on the GitHub Release got out of sync — likely a partial upload. Re-run the workflow (`workflow_dispatch` trigger from the Actions tab). |
| Users on Windows hit `irm` failing with TLS errors | Pre-Win10 1809 PowerShell defaults to TLS 1.0/1.1 which github.com rejects. Workaround in their console: `[Net.ServicePointManager]::SecurityProtocol = 'Tls12'` then re-run. |

---

## What we deliberately didn't build (yet)

- **Auto-detecting single URL** (`intelliswarm.ai/install` returns the right script per User-Agent). Would need a Cloudflare Worker or nginx rewrite. Future option.
- **winget / brew formulas**. Each is ~1–2 days of work for the listing + maintenance script.
- **Native installers** (`.msi` / `.pkg` / `.deb`). Would bundle a JRE so Java prerequisite goes away.
- **License gating / payments**. Marketplace evolution path: when ready, gate the JAR URL in the install script behind a license token check. Everything else stays.
