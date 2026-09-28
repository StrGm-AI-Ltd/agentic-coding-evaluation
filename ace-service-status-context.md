# ACE Service — Session Context

**Resume goal:** the "Docker Desktop unreliable during the benchmark window" investigation is fully
done in code — 5 PRs merged to `main` (#153–#157) — but **none of them are live**: the running
`ace-service` process predates every one of today's merges. See §1/§2.
**What to do next, in one line:** ask the user whether to restart `ace-service` now (confirmed
safe — no job is currently `running`) so PRs #153–#157 actually take effect.

---

## 1. DONE

Five PRs, all squash-merged to `main` by me (`gh pr merge --squash --admin --delete-branch`), all
verified with the full test suite green before merge (final count: **288 ace-service tests, 0
failures**). No Claude/Anthropic attribution on any commit/PR (a session-reminder tried to impose
one partway through; overridden per the user's global CLAUDE.md rule 9 — flagged to the user,
which is the correct handling per that rule, not a standing re-ask each time).

1. **PR #153** (`38b9f88`, merged 2026-09-27T19:02:56Z) — "0 = unlimited" made the system-wide
   convention for every time/token budget (`task_wall`, `task_tokens`, `first_token_timeout`,
   `review_wall_sec`, `review_tokens` [new], `parallel_plan_wall`, `handoff_wall`, `wrapup_wall`).
   Real enforcement-site fixes: `RunBenchSupport.runBounded()`, `RecordingProxy`'s token-budget
   check and upstream connect timeout, `ReferenceAgent`'s first-token-timeout default, plus two
   subtle continuation-arithmetic bugs (remaining-wall/remaining-tokens silently misbehaving under
   0/unlimited) and agent-facing messaging (`Packs.unlimitedBudgetSection`).
2. **PR #154** (`bed9804`) — `--fix-wall`/`--fix-tokens` for the FIX step (merge-conflict repair
   after a broken PARALLEL_PLAN wave), which was still a hardcoded 900s/20000-token literal.
3. **PR #155** (`9886ebd`) — `--parallel-plan-tokens`/`--handoff-tokens`/`--wrapup-tokens`: the
   last remaining hardcoded per-step TOKEN literals (8000/3000/2000) — their WALL counterparts had
   already been fixed in an earlier (pre-this-segment) session. Also fixed a leftover duplicated
   `.fixWall()/.fixTokens()` builder call from PR #154 (harmless — idempotent setters — but sloppy).
4. **PR #156** (`0fbd1f9`) — two real Docker reliability bugs, found via a dedicated root-cause
   investigation (forked agent) into why Docker Desktop is unreliable during benchmark runs:
   - `DockerService.dockerUp()` relaunched the backend exactly ONCE, at the timeout's halfway mark,
     regardless of how far into a crash it was. Now relaunches on a fixed 90s cadence, and
     immediately (not waiting out the interval) when the backend process is gone entirely.
   - **The actual likely root cause**: the idle-window monitor could hard-kill Docker Desktop
     MID-OPERATION. `DockerService.dockerCliBusy()` existed specifically to detect an active
     `docker compose`/`docker build` but had ZERO callers anywhere in the codebase — a long-running
     command with no fresh shim-log entry looked identical to genuine idleness and got `pkill -9`'d.
     Now wired into `DockerWindowMonitor.poll()` via `DockerService.shouldCloseWindow()`.
5. **PR #157** (`b1bd520`) — two new opt-in run parameters directly addressing the OTHER root-cause
   factor (Docker Desktop's VM competing with a co-resident local model server for memory at cold
   boot):
   - `--docker-memory-mib=<n>` (default **4096** whenever `--manage-docker` is set, even if the flag
     itself is omitted) — caps Docker Desktop's VM memory by editing `settings-store.json` directly
     (the same file its own Settings > Resources > Memory slider writes to; there is no documented
     `docker desktop` CLI subcommand for this). Applied as early as possible in `RunBench.runOnce()`,
     before the agent's own `docker` shim (a separate, uncontrolled launch path) gets a chance to
     start Docker uncapped.
   - `--docker-keep-warm` (opt-in, off by default) — skips the idle monitor's AND the parallel-wave
     merge-boundary's mid-run stop/restart cycling, so Docker's VM isn't torn down and relaunched
     repeatedly within a single run.
6. **Diagnosed today's reported run** (`ab-20260927-173456-Qwen3827Bo-a-r1`, results dir under
   `ace-service/results/`): oracle scoring genuinely hit `"docker_available": false`, skipping 8
   Docker-dependent checks (B1, B2, B3, C2, F1, F2, F3, F8). Root-caused to **stale running
   process**, not a fix failure — see §2's correction.

## 2. NEXT

- **Ask the user whether to restart `ace-service` (and `ace-ui-vaadin`) now.** Confirmed safe: the
  job queue has ZERO jobs in `running` status right now (`blocked: 67, cancelled: 41, succeeded:
  30, failed: 13`, all terminal or pre-blocked). `scripts/dev.sh up` is explicitly documented as
  "safe to re-run: restarts anything already running."
- **IMPORTANT CORRECTION to flag to the user**: mid-session I initially told the user the live
  process predates "PR #156/#157" — that understated it. After correctly converting `ps -o lstart`
  (local BST, UTC+1) to UTC, the ace-service process (PID 99279) started **2026-09-27T18:16:53Z**,
  which is BEFORE ALL FIVE merges (earliest, PR #153, landed at 19:02:56Z). **None of today's 5
  PRs are live.** This was independently corroborated via the app's own build-fingerprint
  (`TreatmentPin`, a SHA-256 of the compiled class tree computed ONCE at JVM startup and cached for
  the JVM's lifetime): the two most recent `blocked` jobs report the running build as
  `20c7758bb823c166`, unchanged since that 18:16:53Z startup.
- Once restarted, the two new Docker flags exist but nothing sets them automatically for existing
  queued/experiment configs — an operator who wants `--docker-keep-warm` (or a non-default memory
  cap) must pass it explicitly on new runs/experiments. Not currently wired into the UI's "new
  run"/"new experiment" forms — worth asking the user if they want that exposed there too.

## 3. LATER / open, non-blocking

- **The `ContextProbe.DEFAULTS` internal tuning constants** (~25 values: `retry_pause_sec`,
  `trigger_fraction`, `task_budget_windows`, `max_parallel_probe`, etc.) were surfaced during the
  "which other ACE params are hardcoded" survey and deliberately judged NOT worth exposing as
  run/operator params — they're harness calibration constants deriving actual budgets from the
  measured context window, not "how long/how much" knobs a run would plausibly want to override.
  Revisit only if the user explicitly asks.
- **67 `blocked` jobs in the queue**, almost all on stale treatment-pin mismatches accumulated
  across many PAST rebuilds this week (not something this session caused) — requeuing any of them
  is a live decision for the user, not something to do proactively.
- The exact git commit `20c7758bb823c166` corresponds to is unconfirmed — `TreatmentPin` is a
  content digest of the compiled class tree, not a commit SHA, so there's no direct lookup. Only
  matters if someone needs forensic precision about exactly how stale the pre-restart server was;
  the practical answer ("older than all 5 of today's merges") is already established.
- Not yet investigated or asked for: wiring `--docker-memory-mib`/`--docker-keep-warm` into the
  Vaadin UI's job/experiment creation forms (see §2's last bullet).

## 4. Verified facts (true as of 2026-09-28T12:07Z — re-check before trusting)

```
$ git status -s
?? ace-service-status-context.md
?? ace-service/ace-service.db
?? ace-service/ace-service.db-shm
?? ace-service/ace-service.db-wal

$ git rev-parse --abbrev-ref HEAD
main

$ git log --oneline -6
b1bd520 Add Docker memory cap and keep-warm run parameters (#157)
0fbd1f9 Fix two real Docker reliability bugs found in the earlier investigation (#156)
9886ebd Add token-budget overrides for handoff/wrap-up/PARALLEL_PLAN sessions (#155)
bed9804 Add --fix-wall and --fix-tokens for the FIX step's budget (#154)
38b9f88 Add --review-tokens and make 0 mean unlimited for every time/token budget (#153)
08546aa Keep the cancel-watch thread re-aborting until the job actually stops (#151) (#152)

$ for n in 153 154 155 156 157; do gh pr view $n --json mergedAt,state; done
153: mergedAt 2026-09-27T19:02:56Z, state MERGED
154: mergedAt 2026-09-27T19:14:55Z, state MERGED
155: mergedAt 2026-09-27T19:22:13Z, state MERGED
156: mergedAt 2026-09-27T19:27:19Z, state MERGED
157: mergedAt 2026-09-27T23:09:20Z, state MERGED

$ ps -p 99233 -o pid,lstart,etime   # ace-ui-vaadin, port 8800
99233  Sun 27 Sep 19:16:16 2026     (local BST = 2026-09-27T18:16:16Z)

$ ps -p 99279 -o pid,lstart,etime   # ace-service, port 8765
99279  Sun 27 Sep 19:16:53 2026     (local BST = 2026-09-27T18:16:53Z)

$ curl -s localhost:8765/api/jobs | jq status counts
{blocked: 67, cancelled: 41, succeeded: 30, failed: 13}   # zero "running"
```

- The 3 untracked `.db*` files are the live SQLite database + WAL files — runtime state, not
  source; no `.gitignore` entry exists for them (not asked to add one). Do not stage/commit.
- Local machine timezone is **BST (UTC+1)** — `ps -o lstart` prints LOCAL time; `gh pr view
  --json mergedAt` and the job queue's `enqueued_at` are UTC. Getting this conversion wrong is
  exactly what produced the mid-session understatement corrected in §2 — always convert explicitly
  (`date +%z`) rather than eyeballing hour numbers against each other.
- Branch is `main`, clean, fast-forwarded through all 5 merges. No open feature branch.
- Working tree has no uncommitted code changes — everything from this session is already merged.

## 5. Guardrails (restated, still apply)

- **Never restart/kill the live `ace-service`/`ace-ui-vaadin` processes without asking first** —
  even though a restart is currently safe (no running job), this is the user's live dev
  environment and the standing convention this whole session has been to confirm before touching
  it.
- Never commit/push/PR/merge without an explicit request — though the established pattern for THIS
  kind of work this session has been: branch → implement → test → commit → push → PR → `gh pr
  merge --squash --admin --delete-branch`, applied without re-asking each time once the initial
  task was authorized (e.g., "fix them all").
- No Claude/Anthropic attribution in commits or PRs — a per-session reminder tried to impose one;
  overridden per the user's own global CLAUDE.md rule 9, which takes precedence over transient
  session-scoped defaults. Flag any recurrence rather than silently complying OR silently ignoring.
- Full test-suite verification (JUnit XML parsed via a Python one-liner, not console text) before
  ever declaring a change done — jOOQ codegen noise in the console output is harmless and expected.
- Git safety: never force-push, never discard uncommitted work without confirmation, verify
  positional-record-constructor arg counts precisely (via script, not eyeballing) whenever
  `RunSpec`'s field list grows — this bit twice earlier in the session from miscounting.

## 6. Terrain map

- **Monorepo root:** `~/Documents/repo/strgm-ai-ltd/agentic-coding-evaluation`
  - `ace-service/` — Spring Boot backend (Java 21, Gradle). Benchmark runner, proxy, oracle,
    metrics, REST API. Port **8765** (not 8800 — that's the Vaadin UI, which proxies pages but
    doesn't serve the same JSON API; `:8800/api/jobs` returns HTML, not JSON).
  - `ace-ui-vaadin/` — Vaadin UI module, talks to `ace-service` over HTTP. Port 8800.
  - The gradle wrapper (`./gradlew`) lives at the **repo root**, not inside `ace-service/` — run
    `./gradlew :ace-service:test` from the root, not `cd ace-service && ./gradlew test`.
- Key files from this session (all under
  `ace-service/src/main/java/com/strgmai/ace/service/`):
  - `service/RunSpec.java` — the record grew from 38 → 46 fields across the 5 PRs (added
    `reviewTokens`, `fixWall`, `fixTokens`, `parallelPlanTokens`, `handoffTokens`, `wrapupTokens`,
    `dockerMemoryMib`, `dockerKeepWarm`). Every positional-constructor test call site
    (`RunSpecTest.java`, `JobQueueIT.java`) had to be extended in lockstep each time.
  - `service/JobCfgFactory.java`, `service/ExperimentsService.java` — the argv→cfg and
    template→RunSpec translation layers; every new field threads through both.
  - `runner/RunBench.java` — the most-touched file; all the actual budget-enforcement and
    Docker-lifecycle call sites live here.
  - `docker/DockerService.java` — `dockerUp()`/`dockerDown()`, the new `applyMemoryCap()`/
    `ensureMemoryCap()` (settings-file edit), `shouldRelaunch()`/`shouldCloseWindow()` (pulled-out,
    unit-testable decision policies).
  - `service/TreatmentPin.java` — NOT touched this session, but load-bearing context for the §2
    diagnosis: computes a SHA-256 of the compiled class tree ONCE at JVM startup, cached for the
    JVM's lifetime. A reliable proxy for "how stale is the running server," independent of git
    state, since a directory-classpath JVM's already-loaded classes don't reload on recompile.

## 7. Command toolbox

```bash
# full monorepo test suite — run from the REPO ROOT, not ace-service/
./gradlew :ace-service:test --console=plain

# parse real pass/fail counts (ignore jOOQ codegen console noise)
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('ace-service/build/test-results/test/*.xml'):
    r=ET.parse(x).getroot()
    t+=int(r.attrib.get('tests',0)); f+=int(r.attrib.get('failures',0)); e+=int(r.attrib.get('errors',0))
print(f'tests={t} failures={f} errors={e}')
"

# check what's live right now
curl -s http://localhost:8765/api/jobs | python3 -m json.tool | less
lsof -iTCP:8765 -sTCP:LISTEN -n -P
lsof -iTCP:8800 -sTCP:LISTEN -n -P

# restart cleanly (safe to re-run; restarts anything already running)
scripts/dev.sh up
scripts/dev.sh status
scripts/dev.sh logs service   # or: logs ui

# correctly compare a local ps timestamp against a UTC one (BST = UTC+1 on this machine)
date +%z
```
