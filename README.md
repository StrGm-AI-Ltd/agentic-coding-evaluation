# agentbench-trading UI (Vaadin)

A Vaadin 25 web UI for the **agentbench-trading** benchmark service
(`agentbench-trading-service/service` — the FastAPI run-queue / stats service).
It consumes the service's JSON API (`/api/runs`, `/api/groups`, `/api/jobs`, `/api/experiments`,
`/api/compare`, `/api/preflight`) and mirrors the original server-rendered Jinja2 UI.

## Stack

| piece | version |
|---|---|
| Vaadin (Flow, `com.vaadin` Gradle plugin) | 25.2.8 |
| Spring Boot | 4.1.1 |
| Java | 21 (toolchain-pinned) |
| Gradle wrapper | 8.14.3 |

Vaadin 25 targets Spring Boot 4 — mixing SB 3.5 artifacts with the Vaadin 25 starter breaks startup
with duplicate `conventionErrorViewResolver` beans, so keep them aligned.

## Run

1. Start the service it reads from (port **8765**):

   ```sh
   cd agentbench-trading-service/service
   uv run agentbench-service
   ```

2. Start this UI:

   ```sh
   cd agentbench-trading-UI-Vaadin
   ./gradlew bootRun
   ```

3. Open <http://127.0.0.1:8800>

Port layout (collisions matter on the benchmark machine): `8800` UI · `8765` service ·
`8080` benchmark oracle · `9191` oMLX. Configured in `src/main/resources/application.yml`:

- `agentbench.service.base-url` — where the FastAPI service lives
- `agentbench.service.connect-timeout` / `read-timeout` — bounded HTTP budgets (default 2 s / 15 s)
  so a stuck service can never hang the UI thread indefinitely

> `gradle.properties` pins `org.gradle.java.home` to the Homebrew JDK 21 on this machine
> (the default `java` is 11). Remove that line on other machines and let the Java 21
> toolchain resolve normally.

## Views

| Route | What | Backed by |
|---|---|---|
| `/runs` | run list with task/model/mode/valid/poolable filters, "Rescan results/" | `GET /api/runs`, `POST /api/import` |
| `/runs/:id` | scores, validity badges, per-check table, plan tasks, per-step scores, provenance, re-score for un-poolable runs, embedded file browser | `GET /api/runs/{id}`, `POST /api/runs/{id}/rescore`, `GET /runs/{id}/files/{path}` |
| `/groups` | leaderboard: ranked (k ≥ 5) and indicative cards, mean ± 90 % CI, pass-k chips | `GET /api/groups` |
| `/compare` | A/B group picker, metric + pooling options, stats.py output | `POST /api/compare` |
| `/jobs` | queue table (blocked jobs actionable: cancel/requeue, blocked reason tooltip), raise priority | `GET /api/jobs`, `POST /api/jobs/{id}/…`, `PATCH /api/jobs/{id}` |
| `/jobs/new` | new job form: every RunSpec flag (task, model picker, harness, mode, budgets, parallel, reviewers, docker flags) + priority | `POST /api/jobs` |
| `/jobs/:id` | job detail, live status/result via 2 s UI polling while non-terminal | `GET /api/jobs/{id}` |
| `/experiments`, `/experiments/:id` | experiments and their arm × repeat jobs | `GET /api/experiments[/{id}]` |
| `/experiments/new` | new experiment form: harness_effect / model_ab / agent_ab templates with per-template params, reviewer pickers | `POST /api/experiments` |
| `/preflight` | positive-control preflight state, run + auto-refresh | `GET/POST /api/preflight` |

The run page embeds a **file browser**: it lists the run's `results_dir` (top level + one
subdir level, excluding `workspace/`, mirroring the service's `run_files()`) and views text files
(`.md .log .json .jsonl .txt .yaml .yml`) inline through `GET /runs/{id}/files/{path}`;
binary files link out to the service.

The original SSE live page is approximated with Vaadin UI polling of the job endpoint
(same 2 s cadence). Task and model pickers suggest values seen in imported runs plus free
text (the service does not expose its rung ladder or oMLX model list over JSON; the server
re-validates everything). Remaining service-only surface: the SSE log tail stream.

## Build & test

```sh
./gradlew build      # compiles + Vaadin production frontend bundle
./gradlew test       # 64 deterministic, service-independent tests (unit + stub-server wire tests)
./gradlew testLive   # 3 live wire-mapping tests against the running service (skipped if it is down,
                     # override with -Dagentbench.service.base-url=…)
```

The default `test` task is fully offline: the wire tier runs against an in-process stub HTTP
server (com.sun.net.httpserver, no extra dependencies), covering every endpoint, both POST body
shapes (`/api/jobs`, `/api/experiments`), the 422/404 error paths and the read-timeout budget.
View-level logic (job spec/experiment param normalization, status→action mapping, file listing,
path guards, badge/format rules) is extracted into pure classes and unit-tested directly.
