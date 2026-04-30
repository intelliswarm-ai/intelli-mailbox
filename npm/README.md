# intelli-mailbox

> AI-preprocessed inbox on top of your real Gmail. Local LLM by default — emails never leave your laptop.

A thin npm wrapper around the [IntelliMailbox](https://intelliswarm.ai/products/intelli-mailbox) Java app. Handles platform detection, prerequisite checks (Java 21+, Ollama), JAR download with **SHA-256 verification**, and auto-launch.

## Install

```bash
npm install -g intelli-mailbox
```

That's it. The post-install hook:

1. Verifies you have Java 21+ and Ollama installed (and tells you exactly how to install them if not).
2. Pulls the `qwen2.5:3b` LLM model into Ollama (~2 GB, one-time, fully local).
3. Downloads the IntelliMailbox JAR from GitHub Releases and verifies its SHA-256.
4. Drops a launcher and adds it to PATH.
5. Starts the app and opens `http://localhost:8090/` in your default browser.

Skip step 1–5 with `INTELLI_MAILBOX_SKIP_POSTINSTALL=1` if you'd rather run it manually later via `intelli-mailbox install`.

## Usage

```bash
intelli-mailbox             # launch (install on first run)
intelli-mailbox install     # force reinstall / upgrade to latest jar
intelli-mailbox uninstall   # remove jar + launcher (Ollama model untouched)
intelli-mailbox status      # show install location + jar SHA-256
intelli-mailbox version     # print this wrapper's version
intelli-mailbox help
```

## What you get

* **Multi-category badges** — `MEETING`, `RISK`, `EXTERNAL`, `AUTOMATED`, `VIP`, `FOLLOW_UP`, `NEWSLETTER`, `FINANCE` instead of a single urgency dial. Click to filter.
* **Structured action items** — type / priority / optional ISO due date / verbatim source quote so you can audit the LLM.
* **Pre-drafted replies** — formal / friendly / brief, only generated for emails that warrant a response.
* **Local-by-default** — Ollama runs `qwen2.5:3b` on your machine. Cloud LLM is opt-in.
* **Two themes** — Intelliswarm dark (default) and a clean light palette.

## Privacy

Nothing leaves your laptop unless you explicitly switch the active Spring profile to `openai-mini` (and set `OPENAI_API_KEY`). The default profile uses Ollama on `localhost:11434`.

## Requirements

* Node 16+ (for this wrapper)
* Java 21+ on PATH
* [Ollama](https://ollama.com)
* Google Chrome (or any Chromium / Edge / Brave) for the CDP-attached Gmail bridge
* ~3 GB free disk

## Links

* [Product page](https://intelliswarm.ai/products/intelli-mailbox)
* [Source / issues](https://github.com/intelliswarm/intelli-mailbox)
* [License — Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)
