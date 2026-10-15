# Agentic Coding Evaluation (ACE)

A reproducible framework for evaluating AI coding agents, models, and agent harnesses under controlled conditions.

## Subprojects

- **`ace-service`** — the Java 21 / Spring Boot harness: scoring oracle, orchestrated/monolithic
  runner, recording proxy, and reference coding agent.
- **`ace-ui-vaadin`** — a Vaadin 25 web UI over the harness's run/job/experiment API.

Both are Gradle subprojects of this repo's root build (`./gradlew :ace-service:...`,
`./gradlew :ace-ui-vaadin:...`).

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design: what is measured, how scoring and the
runner work, and how this port compares to the original Python implementation it's based on.

## Requirements

Running an actual benchmark (the worker executing a queued job) currently only works on **macOS**:

- `Preflight`'s JDK auto-discovery falls back to `/usr/libexec/java_home -v 21`, a macOS-only tool.
  Set `ace.java-home` (or `$ACE_JAVA_HOME`) to sidestep this on any platform.

The Vaadin UI (`ace-ui-vaadin`) and an `ace-service` instance used only to browse, import, or compare
already-produced results have no such constraint.

## Getting started

Needs a JDK 21 on `PATH` (or `$ACE_JAVA_HOME` set). From the repo root:

```bash
./gradlew :ace-service:test :ace-ui-vaadin:test   # build + run the test suites
```

Then start both halves, each in its own terminal:

```bash
./gradlew :ace-service:bootRun     # the backend: REST API + job queue + worker, on :8765
./gradlew :ace-ui-vaadin:bootRun   # the web UI, on :8800 — talks to the backend above
```

Open `http://localhost:8800`. The queue starts empty — nothing is pre-imported (`results/` is
gitignored) — so there's nothing to browse until either a job runs or an existing `results/<run-id>`
directory is copied in and imported from the UI's Runs tab.

### Pointing ACE at a model

ACE needs a chat-completions endpoint to actually run a job against. Three ways to get one, set
via environment variables before starting `ace-service`:

1. **A local [oMLX](https://github.com/ml-explore/mlx) server** (the setup ACE was originally built
   for — a local-model tinkerer's own machine): `ACE_ENDPOINT`/`ACE_MODEL` already default to
   `http://127.0.0.1:9191/v1` / `Qwen3.6-27B-graft` — override `ACE_MODEL` to match whatever model
   your own server is actually serving.
2. **Any OpenAI-wire-compatible external provider** — OpenAI itself, Google Gemini (its own
   documented OpenAI-compatible endpoint), OpenRouter, or **Nebius Token Factory** (serving NVIDIA
   Nemotron models, also OpenAI-wire-compatible): set `ACE_ENDPOINT` to that provider's base URL,
   `ACE_MODEL` to the model id, and `OMLX_API_KEY` to that provider's own API key (the env var name
   is a holdover from the local-oMLX default — it's read as a plain bearer token regardless of
   provider). Pass `--no-context-probe` and an explicit `--context-window=<N>` on the job (see
   below) — the context-probe step is oMLX-specific and only no-ops gracefully on a machine with no
   local `omlx` install; it was never meant to run against a remote endpoint.
3. **Skip running a job entirely** and just import/browse/compare already-produced `results/`
   directories — no model endpoint needed for that at all.

A job is queued from the UI's **Jobs → New Job** form, not a CLI entry point (see
[`docs/DESIGN.md`](docs/DESIGN.md)'s own "How to run" section for what is and isn't a verified
command surface in this port).
