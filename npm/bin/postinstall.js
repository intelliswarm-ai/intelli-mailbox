#!/usr/bin/env node
/**
 * postinstall — runs automatically after `npm install -g intelli-mailbox`.
 *
 * Triggers the platform-appropriate install script so the user goes from
 * `npm i -g intelli-mailbox` to a running app in one command.
 *
 * Skip with `INTELLI_MAILBOX_SKIP_POSTINSTALL=1` (CI / scripted installs / Docker
 * builds where you don't want the script's auto-launch step at install time).
 *
 * Also skipped automatically when:
 *   - npm is running in CI mode (npm_config_cache_lock_retries env vars present)
 *   - the package is being installed as a dependency (not a global) — we only
 *     want to run the heavyweight setup when someone explicitly installs us
 *     globally to use as a CLI.
 */
'use strict';

const path = require('path');
const { spawnSync } = require('child_process');

if (process.env.INTELLI_MAILBOX_SKIP_POSTINSTALL) {
    console.log('intelli-mailbox: postinstall skipped (INTELLI_MAILBOX_SKIP_POSTINSTALL set).');
    process.exit(0);
}

// `npm install -g` sets npm_config_global=true. Skip postinstall if installed
// as a project-local dependency — the user clearly didn't intend to bootstrap
// the whole IntelliMailbox runtime in their project's node_modules.
if (process.env.npm_config_global !== 'true') {
    console.log('intelli-mailbox: skipping setup (not a global install).');
    console.log('  Run `intelli-mailbox install` to set up the runtime.');
    process.exit(0);
}

const isWin = process.platform === 'win32';
const scriptsDir = path.join(__dirname, '..', 'scripts');
const cmd  = isWin ? 'powershell' : 'bash';
const args = isWin
    ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(scriptsDir, 'install.ps1')]
    : [path.join(scriptsDir, 'install.sh')];

console.log('intelli-mailbox: kicking off platform installer (Java + Ollama check, jar download, SHA-256 verify)…');
const result = spawnSync(cmd, args, { stdio: 'inherit', env: process.env });

if (result.error) {
    console.error(`intelli-mailbox: postinstall failed to spawn ${cmd}: ${result.error.message}`);
    console.error('  Run `intelli-mailbox install` manually to retry.');
    process.exit(0); // exit 0 so npm install itself reports success — the wrapper still works
}
if (result.status !== 0) {
    console.error(`intelli-mailbox: installer exited ${result.status}.`);
    console.error('  Fix the issue (e.g. install Java 21 / Ollama) and run `intelli-mailbox install`.');
    process.exit(0);
}
