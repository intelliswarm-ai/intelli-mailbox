#!/usr/bin/env node
/* eslint-disable no-console */
/**
 * intelli-mailbox — CLI entrypoint for the npm wrapper.
 *
 * Smart launcher:
 *   - If the jar is already installed (~/.intelliswarm/intelli-mailbox/intelli-mailbox.jar),
 *     just `java -jar ...` it. Fast path: no network, no LLM model fetch, no checksum probe.
 *   - Otherwise, dispatch to the platform-appropriate install script (which downloads,
 *     verifies SHA-256, sets up the launcher, and ends by starting the app).
 *
 * Subcommands:
 *   intelli-mailbox            launch (install on first run)
 *   intelli-mailbox install    force reinstall / upgrade
 *   intelli-mailbox uninstall  remove the installed jar + launcher
 *   intelli-mailbox status     show install location + version + jar checksum
 *   intelli-mailbox version    print the npm wrapper version
 */
'use strict';

const fs   = require('fs');
const os   = require('os');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const crypto = require('crypto');

const PKG       = require('../package.json');
const IS_WIN    = process.platform === 'win32';
const HOME      = os.homedir();
const INSTALL_DIR = IS_WIN
    ? path.join(process.env.LOCALAPPDATA || path.join(HOME, 'AppData', 'Local'), 'IntelliSwarm', 'IntelliMailbox')
    : path.join(HOME, '.intelliswarm', 'intelli-mailbox');
const JAR_PATH  = path.join(INSTALL_DIR, 'intelli-mailbox.jar');
const SCRIPTS_DIR = path.join(__dirname, '..', 'scripts');

function run(cmd, args, opts = {}) {
    const child = spawn(cmd, args, { stdio: 'inherit', ...opts });
    child.on('exit', (code) => process.exit(code === null ? 1 : code));
    child.on('error', (err) => { console.error(`✗ ${err.message}`); process.exit(1); });
}

/** Run the platform-specific install script. Inherits stdio so progress bars work. */
function runInstaller() {
    if (IS_WIN) {
        const ps1 = path.join(SCRIPTS_DIR, 'install.ps1');
        run('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ps1]);
    } else {
        const sh  = path.join(SCRIPTS_DIR, 'install.sh');
        run('bash', [sh]);
    }
}

/** Launch the jar. Background-detached on Unix; on Windows defer to javaw via the cmd launcher. */
function launchJar() {
    if (!fs.existsSync(JAR_PATH)) {
        console.error(`✗ ${JAR_PATH} not found — running installer first.`);
        runInstaller();
        return; // installer ends by launching itself, no need to also launch here
    }
    if (IS_WIN) {
        // Use javaw so PowerShell doesn't block on the JVM. The .cmd launcher
        // dropped by install.ps1 does the same; we shortcut to javaw directly
        // for the case where someone calls us before /5/ ran.
        const child = spawn('javaw', ['-jar', JAR_PATH], { detached: true, stdio: 'ignore' });
        child.unref();
    } else {
        const child = spawn('java',  ['-jar', JAR_PATH], { detached: true, stdio: 'ignore' });
        child.unref();
    }
    console.log('▸ IntelliMailbox launched. Open http://localhost:8090/');
}

function uninstall() {
    if (!fs.existsSync(INSTALL_DIR)) {
        console.log('Already uninstalled.');
        return;
    }
    fs.rmSync(INSTALL_DIR, { recursive: true, force: true });
    console.log(`✓ Removed ${INSTALL_DIR}`);
    console.log('  (the qwen2.5:3b Ollama model is left in place — `ollama rm qwen2.5:3b` to free ~2 GB)');
}

function status() {
    console.log(`intelli-mailbox npm wrapper:  v${PKG.version}`);
    console.log(`install dir:                  ${INSTALL_DIR}`);
    if (!fs.existsSync(JAR_PATH)) {
        console.log('jar:                          not installed (run `intelli-mailbox install`)');
        return;
    }
    const stat = fs.statSync(JAR_PATH);
    console.log(`jar:                          ${JAR_PATH}`);
    console.log(`size:                         ${(stat.size / (1024 * 1024)).toFixed(1)} MB`);
    const hash = crypto.createHash('sha256').update(fs.readFileSync(JAR_PATH)).digest('hex');
    console.log(`sha256:                       ${hash}`);
}

// ---------- dispatch -------------------------------------------------------
const sub = process.argv[2];
switch (sub) {
    case undefined:
    case 'launch':
    case 'start':
        launchJar();
        break;
    case 'install':
    case 'upgrade':
    case 'update':
        runInstaller();
        break;
    case 'uninstall':
    case 'remove':
        uninstall();
        break;
    case 'status':
    case 'info':
        status();
        break;
    case 'version':
    case '--version':
    case '-v':
        console.log(PKG.version);
        break;
    case 'help':
    case '--help':
    case '-h':
        console.log(`intelli-mailbox v${PKG.version}\n`);
        console.log('Usage:');
        console.log('  intelli-mailbox             launch (install on first run)');
        console.log('  intelli-mailbox install     force reinstall / upgrade');
        console.log('  intelli-mailbox uninstall   remove jar + launcher');
        console.log('  intelli-mailbox status      show install location + jar checksum');
        console.log('  intelli-mailbox version     print this wrapper\'s version');
        break;
    default:
        console.error(`Unknown command: ${sub}`);
        console.error("Run 'intelli-mailbox help' for usage.");
        process.exit(1);
}
