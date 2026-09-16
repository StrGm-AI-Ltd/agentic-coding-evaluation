# agentbench-jls

The Java/Spring/LangChain4j port of [agentbench-trading-service](../agentbench-trading-service) — a
benchmark harness that measures an LLM agent building a Spring-based trading service against a
frozen contract, with an exploit-hardened oracle and a run-queue service.

## Build & test

```bash
./gradlew build          # compile + all tests + jar
./gradlew test           # 69 tests; the JobQueue IT runs when a local Postgres is reachable
                       #   (DB: agentbench_jls_test — auto-created once, wiped per test) and skips otherwise
AB_JLS_DSN=jdbc:postgresql://localhost/agentbench ./gradlew bootRun   # the service on :8765
```

JDK 21 (pinned via `org.gradle.java.home`), Spring Boot 3.5, LangChain4j 1.1, Postgres. Model
server: any OpenAI-compatible endpoint (`AB_ENDPOINT`, default oMLX on :9191).

## What is a faithful port

- **The agent** (`agent/`): runner/agent_loop.py on LangChain4j — the same four tools
  (read/write/edit/bash with exact-match edits, ranged reads, head+tail output caps), structural
  compaction (not an LLM summary), retries with backoff, the proxy 429 ends the session (rc=3),
  stop rule = answer without a tool call, session JSONL in the same record shape.
- **The recording proxy** (`proxy/`): pins the sampler params on every chat request, enforces the
  phase token budget with a 429 (`budget_exceeded` in the journal), journals every interaction as
  JSONL (the run's ground truth), reassembles streamed SSE, estimates usage of abandoned streams.
- **The oracle** (`oracle/`): the check registry (S/P/M/B/C/F ids with weights), the scorer with
  every invariant (missing record = FAIL — a crashed checker can never shrink the denominator;
  NOT_ATTEMPTED = FAIL; SKIPPED/INFRA excluded but headline-blocking; functional as the primary
  score), structure checks S1-S9, money-safety lint M1-M4 (comment stripping, Kotlin, SQL,
  per-method point-in-time analysis), and the black-box functional suite F1-F9 minus F6.
- **The reference trading server** (`reference/`): correct by default, BigDecimal with explicit
  scale 2 and `RoundingMode.HALF_EVEN` (deposits round 0.005→0.00, 0.015→0.02), exclusive asOf
  boundary with `asOfApplied` echoed, idempotency keys, 422/409 semantics — plus the BUGS
  injection modes (`float`, `halfup`, `no409`, `no422`, `asym`, `noidem`, `inclusive`, `selladd`,
  `hollow`) used by `BlackboxCalibrationTest` to prove each scenario catches exactly the bug it
  claims. Runnable standalone (`ReferenceServer`) exactly like the Python twin.
- **The runner** (`runner/`): the single-run lock, scrubbed environment, git snapshots per phase
  (SHAs recorded outside the workspace), kill-tree via `ProcessHandle`, journal facts WITH the
  parse-once-per-version cache and byte-offset windows, validity() (budget exhaustion is a
  result, never an invalidation), both modes (monolithic + orchestrated).
- **The stats** (`metrics/`): bootstrap 90% CIs, the unpaired one-sided permutation test, the
  comparability key (refusal names the differing component), matched-budget enforcement for
  harness-effect comparisons, model-AB exemptions.
- **The service** (`service/`): the DB queue (claim via `FOR UPDATE SKIP LOCKED`, priority DESC,
  duplicate-run-id refusal with the same message), the worker (one run at a time, startup
  reconciliation, cancel flag, post-run import), the importer (upsert; poolable = current
  schema), the experiment templates (harness_effect / model_ab / agent_ab — with the arm-suffix
  run-id fix from the Python review), the JSON API (runs/jobs/experiments/compare/SSE progress)
  and the Tailer with the byte-exact UTF-8 offset fix ported.

## Scope (everything below is now at full parity with the Python original)

- **The context probe** (step 0): the window this setup actually offers is MEASURED (growing
  prefix-extension prompts, binary refinement, decode-rate curve, memory-guard facts) and every
  context-dependent knob is derived from it. The probe OWNS the oMLX server cap: it raises
  `max_context_window` to the model's positional limit before measuring and sets it to the
  measured window after; the parallel-capacity probe (work-per-wall speedup) sets
  `max_parallel`. Results are cached per setup; a window set by a transient failure is never
  cached.
- **Parallel waves/worktrees**: the agent's parallelisation plan is a scored step; valid
  schedules run as waves — each task in its own git worktree + HOME copy + tagged journal and
  its own recording proxy — merged in plan order with conflicts kept and surfaced, ownership
  violations and overlapping edits recorded, a wave verification, and a dedicated FIX session
  when the merge broke something.
- **Reviews**: end-of-run self-review and trajectory review by the run's own model or any
  provider/model, blind variants, the trajectory renderer (per-turn trace, thrash/ping-pong
  analysis, the harness-computed objective index the reviewer is calibrated against), code
  freeze and restore of whatever the reviewer touched.
- **Docker lifecycle** (R7): Docker Desktop is DOWN while the agent works, comes up through the
  shim installed as `docker` on the agent's PATH (every call logged), is stopped after idle and
  at session end; the compose runtime checks sweep stale stacks, build from a SOURCE-ONLY copy
  (host-built artefacts and absolute compose paths are caught), separate build/health budgets,
  C1/C2 semantics with db rows never "bad", and the black-box F suite against the live stack.
- **Container-pinned builds**: B1/B2 run `gradle assemble/test` in the pinned
  `gradle:8.14-jdk21` image (digest in the oracle provenance) with a real PostgreSQL sidecar on
  a private network, recreated per run; B3 seeds a mutation of the sell-side arithmetic (nearest
  anchor, masked source, offset-preserving) and the agent's suite MUST fail; INFRA never
  charged to the agent; the whole flow runs on a scratch copy.

Still not ported (documented, not silent):
- `--step`/`--score-only` modes and the Python HTML UI (skipped by request: every page has a
  JSON twin under `/api/...`).
- F6 (OpenAPI conformance) needs the full contract file; it is SKIPPED with a named reason.
- The worker runs benchmarks **in-process** on a single-threaded executor (the Python original
  spawns run_bench.py); the one-run-at-a-time invariant, cancel and post-run import carry over.
- `chat_template_kwargs.reasoning_effort` (oMLX-specific request shape) is recorded in config for
  provenance but is not sent — generic OpenAI-compatible endpoints take it via other fields.

## Layout

```
com.agentbench
├── agent/     ReferenceAgent (LangChain4j), AgentTools, AgentSession (+ compaction)
├── proxy/     RecordingProxy (JDK HttpServer), SseAssembler
├── runner/    RunBench (probe, waves, docker windows, reviews), ContextProbe, Reviews,
│              VerifyTask (three-valued), JournalFacts (cached), Validity, RunBenchSupport
├── docker/    DockerService (up/down/shim/tools/windows) + the docker_shim.sh resource
├── pack/      Packs (stable/task/review/trajectory/fix packs, HYGIENE, tolerant parsers)
├── oracle/    CheckId registry, Scorer, RunOracle + checks (structure, money, blackbox)
├── reference/ TradingService + TradingController + ReferenceServer (BigDecimal, HALF_EVEN)
├── metrics/   StatsService (bootstrap CI, permutation test, comparability key)
├── plan/      PlanParser (topological, waves, parallel-plan evaluation)
└── service/   RunSpec, JobQueue, WorkerService, ImporterService, ExperimentsService,
               Tailer, BenchController (JSON API + SSE)
```
