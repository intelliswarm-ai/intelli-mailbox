# SignPath.io Foundation — Windows MSI signing

This project's Windows MSI is signed via [SignPath.io Foundation], the free
Authenticode signing tier for open-source projects. The signing flow is wired
into [`.github/workflows/native-installers.yml`](../.github/workflows/native-installers.yml)
and is **disabled by default** — it activates the moment the SignPath secrets
land in this repo.

[SignPath.io Foundation]: https://signpath.io/foundation

## Why we need this

Without an Authenticode signature, every Windows user who downloads our MSI
sees a Defender SmartScreen "Windows protected your PC" dialog and has to
click "More info → Run anyway". That kills adoption with non-developers.

Website TLS certificates **cannot** sign installers — Authenticode requires a
code-signing X.509 cert (different EKU, different CA validation). SignPath
Foundation provides one for OSS at no cost.

## One-time setup

### 1. Apply to SignPath Foundation

Apply at https://signpath.io/foundation. Approval takes a few business days.
You'll need:
- The repo URL (`https://github.com/intelliswarm-ai/intelli-mailbox`)
- A short description of the project
- Confirmation that the project is open-source and freely available

### 2. Configure the SignPath project

Once approved, in the SignPath portal create:

| Item | Recommended value |
|------|-------------------|
| Project slug | `intelli-mailbox` |
| Signing policy slug | `release-signing` |
| Artifact configuration | "MSI installer" — match `Intelli-mailbox-*.msi` |
| Trusted Build System | GitHub Actions, repo `intelliswarm-ai/intelli-mailbox`, workflow `Native installers`, branch `main` (or `refs/tags/v*`) |
| Signing user | Create a CI user, give it permission to submit signing requests for this policy |

### 3. Add three repository secrets

In **Settings → Secrets and variables → Actions → Secrets**:

| Name | Value |
|------|-------|
| `SIGNPATH_API_TOKEN` | The CI user's API token from SignPath |
| `SIGNPATH_ORGANIZATION_ID` | Your SignPath organization UUID |

### 4. (Optional) Override the slugs via repository variables

In **Settings → Secrets and variables → Actions → Variables**:

| Name | Default | When to set |
|------|---------|-------------|
| `SIGNPATH_PROJECT_SLUG` | `intelli-mailbox` | If you used a different slug above |
| `SIGNPATH_POLICY_SLUG`  | `release-signing` | If you used a different slug above |

That's it. The next push that triggers `native-installers.yml` will sign the MSI.

## How the workflow detects "signing enabled"

The first SignPath step (`Detect SignPath configuration`) checks whether
`SIGNPATH_API_TOKEN` is non-empty. If it is, all subsequent SignPath steps
run; otherwise they're skipped and the unsigned MSI ships as before with a
warning in the job summary. This lets you land the workflow changes safely
before the SignPath approval comes through.

## What ships in the GitHub Release

After signing, the release contains:
- `Intelli-mailbox-<version>.msi` — Authenticode-signed
- `Intelli-mailbox-<version>.msi.sha256` — SHA-256 of the signed bytes (recomputed after signing)

Verifying locally:

```powershell
# PowerShell
Get-AuthenticodeSignature .\Intelli-mailbox-0.1.0.msi
```

```bash
# osslsigncode (Linux/macOS)
osslsigncode verify Intelli-mailbox-0.1.0.msi
```

## Quirks of the Foundation tier

- Each signing request may queue behind a manual review. The workflow waits
  up to 30 minutes (`wait-for-completion-timeout-in-seconds: 1800`) before
  failing. Bump that if your maintainer-review SLA is longer.
- Foundation does not sign macOS DMGs or Linux DEBs. Those still need
  Apple Developer ID + notarization (DMG) or GPG signing (DEB).
- The SignPath service-availability timeout is 10 minutes by default;
  the workflow already passes 600 seconds explicitly.
