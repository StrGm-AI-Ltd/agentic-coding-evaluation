# Agentic Coding Evaluation (ACE) — Design

This is the design reference for `ace-service`, the Java 21 / Spring Boot port of the original
Python `agentbench-trading` CLI harness, plus `ace-ui-vaadin`, its Vaadin web front end. It describes
what the harness measures, how the scoring and runner mechanics actually work in this codebase, and
what has and has not been carried over from the original design. Claims below were checked against
the real source in `ace-service/src/main/java/com/strgmai/ace/service/` — where something could not
be verified against the code, or is known to differ from the original design, that is called out
explicitly rather than asserted.

## 1. Purpose and the two questions it answers

Rank **local (or hosted) open-source LLMs** at **agentic coding** — driving a coding agent to build a
small trading platform: Java 21 / Spring Boot 3 / Gradle / PostgreSQL + Flyway / React / OpenAPI /
Docker Compose microservices. Two questions the harness is built to answer:

1. **Model A/B**: same harness, same task — which model scores higher, at what cost?
2. **Harness effect**: same model — does the way the agent's context is managed change the outcome?

In the original Python project this second question turned out to be the decisive one (an
orchestrated, task-by-task harness beat a single growing session for a weak local model). That
finding is *why* this harness is shaped the way it is; ace-service ports the same two-mode design
(§9) rather than re-deriving it.

## 2. Repository layout

```
ace-service/
  src/main/java/com/strgmai/ace/service/
    AceServiceApplication.java       Spring Boot entry point (Vaadin-less REST + scheduled worker)
    agent/       ReferenceAgent (the harness's own coding agent, on LangChain4j), AgentTools, AgentSession
    config/      BenchProperties (@ConfigurationProperties(prefix="ace")), PGobjectJsonSerializer
    docker/      DockerService — start/stop Docker Desktop, docker-calls log, port/CLI-busy probes
    metrics/     Collect, Trajectory, StatsService — cost, trajectory analysis, k-run statistics
    oracle/      CheckId (the 30-check registry), CheckStatus, CheckResult, Scorer, RunOracle
    oracle/checks/  StructureChecks, MoneySafetyChecks, BuildChecks, ComposeChecks, BlackboxScenarios
    pack/        Packs — stable pack, task pack, review/trajectory-review packs, parallel-plan pack, fix pack
    plan/        PlanParser, PlanTask, PlanError — plan-text parsing into topologically ordered tasks
    proxy/       RecordingProxy (journals every request/response), SseAssembler
    reference/   TradingService (+ 9 seeded bugs), TradingController, ReferenceServer (standalone twin)
    runner/      RunBench (orchestrator), RunBenchSupport, ContextProbe, Validity, VerifyTask,
                 JournalFacts, Reviews, RecordingProxyFactory
    service/     JobQueue, WorkerService, ExperimentsService, ImporterService, BenchController,
                 Preflight, RunSpec, Tailer, TreatmentPin, ApiExceptionHandler
  src/main/resources/
    application.yml, schema.sql, docker_shim.sh
    task/PROMPT.md, tasks/PROMPT.md, tasks/ladder.json, tasks/L1..L7_*/PROMPT.md   the frozen task specs
    contract/openapi.yaml            machine-readable twin of the frozen HTTP contract
  review.txt, review_fixes.txt       this port's own adversarial code review (66 findings, all fixed)

ace-ui-vaadin/
  src/main/java/com/strgmai/ace/ui/  Vaadin 25 views over ace-service's (or the original Python
                                      service's) JSON API — runs/jobs/experiments/groups/compare/preflight
```

There is no `fixtures/positive/` or `fixtures/adversarial/cheat*` directory in this port — see §6.

## 3. Scoring

`oracle/CheckId.java` is the single source of truth, and it is a faithful, checked port: **30 checks**
across 6 categories, same ids and same weights as the original registry (structure S1–S9 weight 1
each, planning P1–P3 weight 2 each, lint M1–M4 weight 1 each, build B1–B3 weight 3 each, runtime C1
weight 3 / C2 weight 2, functional F1–F5 weight 5 / F6 weight 4, bespoke F7–F9 weight 5 each — total
weight 77, matching the original L7 denominator exactly).

| category | ids | weight each | what |
|---|---|---|---|
| structure | S1–S9 | 1 | task docs, compose file, ≥3 real Gradle services, real wrapper, parseable OpenAPI, React in dependencies, DB migrations |
| planning | P1–P3 | 2 | definition sections; subtasks with id/goal/deps/criterion; no source written during phase 0/1 |
| lint | M1–M4 | 1 | money never float/double; BigDecimal imported where money is handled; no `equals()` on money; point-in-time replay subtracts sells |
| build | B1–B3 | 3 | compiles in the pinned JDK container; >0 tests executed and passing (JUnit-XML verified); the agent's own suite fails on a seeded mutation |
| runtime | C1, C2 | 3, 2 | ≥3 non-db services report healthy; declared readiness endpoint returns 200 |
| functional | F1–F6 | 5,5,5,5,5,4 | buy/sell symmetry; point-in-time query; insufficient-balance 422; illegal-transition 409; exact decimal round-trip; OpenAPI conformance |
| bespoke | F7–F9 | 5 | HALF_EVEN deposit rounding; exclusive `asOf` boundary with echo; `Idempotency-Key` repeat → same order, 200, no re-execution |

`CheckStatus` (`oracle/CheckStatus.java`) keeps the same five-value model as the original: `PASS`,
`FAIL`, `NOT_ATTEMPTED` (feature simply absent — still counts as FAIL), `SKIPPED` (infra unavailable
before the check ran), `INFRA` (infra failed mid-check). `SKIPPED`/`INFRA` are excluded from the
denominator but still block a full-score headline, exactly as in the original design; both `parse()`
methods fail closed (an unparsable or null status is `FAIL`, never silently dropped).

Per-rung denominators are ported into `tasks/ladder.json` and verified to match: e.g.
`L3p_point_in_time` denominator 36 over `{M1,M2,M4,P2,B1,B2,B3,C2,F1,F2,F3,F8}`, `L7_full_platform`
"all" checks for the full 77. `functional_score_pct` (the F-ids, denominator 44) remains the primary
number; `weighted_score_pct` the composite — this two-score convention is unchanged.

## 4. The frozen contract and the black-box suite

`src/main/resources/task/PROMPT.md` is the frozen task specification handed to the agent, carried
over close to verbatim: the same phased structure (Phase 0 definition, Phase 1 plan, Phase 2..N
implementation), the same hard constraints table (Java 21 / Spring Boot 3.x / Gradle, PostgreSQL +
Flyway, React frontend, OpenAPI 3.1, Docker Compose, ≥3 microservices), and the same frozen HTTP
contract (`POST /accounts`, deposits with HALF_EVEN rounding, orders with idempotent-repeat and
422/409 semantics, `asOf`-scoped holdings, fixed price table, health). One thing worth flagging
precisely because it is easy to miss: the test-environment env-var names in this prompt are **not**
the harness's own `ACE_*` names — the prompt tells the agent to expect
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD` **and** `AB_DB_HOST/PORT/NAME/USER/PASSWORD`, and those
`AB_DB_*` names are deliberately left unrenamed in `oracle/checks/BuildChecks.java` (which sets them
on the agent's own build container) — this is a frozen, agent-facing contract, and renaming it would
silently break every already-graded solution's expectations. The prompt itself says "Do not edit
anything under `task/`."

`oracle/checks/BlackboxScenarios.java` is the port of the black-box HTTP suite that drives that
contract against the agent's running stack. `reference/ReferenceServer.java` (a plain
`com.sun.net.httpserver.HttpServer`, wrapping the same `TradingService` the real Spring
`TradingController` serves) is the calibration twin, and it carries the same **nine seeded bugs** as
the original — verified directly in `TradingService.KNOWN_BUGS`: `float`, `selladd`, `no409`,
`no422`, `asym`, `halfup`, `inclusive`, `noidem`, `hollow`. A dedicated
`BlackboxCalibrationTest.java` exercises this (see §6 for what calibration coverage does and does not
exist beyond that unit test).

## 5. The other oracle checks

`oracle/checks/` carries `StructureChecks`, `MoneySafetyChecks`, `BuildChecks`, `ComposeChecks` and
`BlackboxScenarios` — a 1:1 mapping onto the original's `01..06_*.py` files, minus the standalone
`_common.py` (its helpers — root-first compose discovery, the INFRA classifier, YAML parsing — are
folded into the check classes themselves rather than kept as a separate shared module; this wasn't
checked line-by-line against the original for behavioural parity, only confirmed to exist in some
form). `BuildChecks` in particular carries the mutation-testing logic (B3: mutate the arithmetic
nearest a `SELL` token, require the agent's own suite to catch it) and the Gradle-cache mount at
`~/.cache/ace-service/gradle`.

## 6. Gaming resistance — what exists in this port

This is the section where this port's coverage is **narrower** than the original design, and it is
worth being direct about that rather than papering over it. The original Python harness's gaming
resistance rested on three legs: (1) a fixed-denominator scoring design (ported, §3), (2) a
**positive control** fixture (a known-good three-service solution that must score ~100%, used to find
the oracle's own bugs), and (3) a set of **five adversarial "cheat" fixtures** with a two-sided
selftest asserting they score below a ceiling. Searching this repository turned up **no
`fixtures/positive/` or `fixtures/adversarial/` directory, and no ported `--selftest` command** —
only `BlackboxCalibrationTest.java`, which calibrates the black-box suite against the seeded-bug
reference server (§4), not a full positive-control-plus-cheats gate. If gaming resistance at that
level matters for this port, building an equivalent positive control and cheat suite is open work,
not something already carried over.

What this port *does* have, and has exercised heavily, is its own **adversarial code review**:
`review.txt` and `review_fixes.txt` in `ace-service/` record 66 numbered findings (bugs, resource
leaks, race conditions, NPEs, dead code) against this Java implementation, each with a fix logged and
`./gradlew test` re-verified green — visible in the git history as a long run of `fix:`/`chore:`
commits each citing a review number. That is real, substantial hardening of the harness's own
correctness; it is a different thing from the original's gaming-resistance fixtures, and both are
worth having.

## 7. The runner: isolation, provenance, budgets, validity

`runner/RunBench.java` is the port of `run_bench.py`, and the core mechanics check out against the
code:

- **Both harness modes exist**: `monolithicPhases()` (one growing session, structural compaction at a
  configured trigger) and `orchestratedPhase()` (stable pack + per-task packs), selected by the
  `mode` config value.
- **Docker lifecycle**: `docker/DockerService.java` provides `dockerUp`/`dockerDown`/`dockerTools`/
  `dockerCliBusy`/`dockerCalls`, and `RunBench` calls `dockerUp`/`dockerDown` around the oracle phase
  when `manage_docker` is set — matching the "Docker down during agent phases" design, though the
  exact timing constants (e.g. the original's "30s hard-kill after a busy quit") were not re-verified
  against this Java implementation line-by-line.
- **A fixed integration task** exists at the end of orchestrated runs (`Packs.INTEGRATION_INSTRUCTION`
  + a dedicated `INTEGRATION` task pack), matching the original's design.
- **Validity** (`runner/Validity.java`) is a close, verified port: a run is invalid (recorded, never
  ranked) on a non-budget agent crash, a `length` finish with no artifact, a dead proxy, an error rate
  above `maxErrorRate`, zero LLM requests recorded, a request referencing another run's workspace, an
  unparseable plan, or (in orchestrated mode) the integration task never running. A budget exhaustion
  is explicitly treated as a **result**, not an invalidation — this is the same "censor both arms the
  same way" rule from the original's design rules (§13).
- **Provenance and snapshots**: `RunBenchSupport.java` provides the scrubbed environment (`ACE_RUN_ID`
  set into the agent's process env — see the note below), git snapshotting after each phase/task, and
  the single-run lock. One deliberately-unrenamed detail: the run lock lives at
  `~/.cache/agentbench/run.lock`, the *same path* the sibling Python service uses, so the two
  implementations serialize against each other rather than racing for the same model server.
- **The reference agent replaces Pi entirely.** The original project's later design added its own
  non-Pi reference agent (`runner/agent_loop.py`, `ref-1.0`) specifically to control reasoning effort,
  compaction behaviour, and loop version — see §7c. This Java port does not carry Pi forward as an
  option at all: `agent/ReferenceAgent.java` (`AGENT_VERSION = "jls-ref-1.0"`) is the only agent
  implementation here.

## 7c. The agent — ReferenceAgent

`agent/ReferenceAgent.java` is a direct, verified port of `runner/agent_loop.py`, built on
LangChain4j against an OpenAI-compatible endpoint:

- **Same four tools**: `read` (line ranges), `write`, `edit` (exact-match replace, with
  `replace_all`), `bash` (zsh, timeout parameter, default/max caps) — defined via LangChain4j
  `ToolSpecification`s in `toolSpecs()`.
- **Same loop shape**: a turn with no tool call ends the session; the proxy's 429 (budget exhausted)
  ends the session with `rc=3` via a dedicated `BudgetExhausted` exception, matched against a literal
  string (`RecordingProxy`'s own error body) rather than by status code alone, since a real upstream
  429 and the proxy's budget refusal are otherwise indistinguishable over HTTP; transient 5xx / typed
  LangChain4j exceptions are retried up to 4 times with exponential backoff; a hard 400-turn cap
  exists as a backstop (exiting `rc=1`, not a false success, if the model never stops on its own).
- **Structural compaction, not an LLM summary**: `AgentSession.compact()` is invoked once the last
  prompt exceeds `props.compactionTrigger()`, matching the original design's "stub the oldest tool
  outputs, keep the newest turns, never touch the system prompt" approach.
- **One thing to flag as unverified/likely incomplete**: the original design records `reasoning_effort`
  as a *treatment* sent on every request (`chat_template_kwargs.reasoning_effort`) and read back from
  the journal. In this port, `JournalFacts.java` **does** read `chat_template_kwargs.reasoning_effort`
  back out of recorded requests, and `AgentSession.header()` accepts a `reasoningEffort` parameter —
  but `ReferenceAgent.run()` calls `session.header(..., null)`, and no code path in `ReferenceAgent`
  was found that actually sets `chat_template_kwargs.reasoning_effort` on the outgoing
  `ChatRequest`. `DEFAULT_REASONING` exists as a `Map<String,String>` with a comment saying it is
  "kept for the record" — reading that literally, the per-session-kind reasoning-effort *treatment*
  from the original design may not be wired through to the model in this port. This needs a closer
  look (or a fix) rather than being asserted either way in a leaderboard context.

## 8. Recording and metrics

`proxy/RecordingProxy.java` is the port of `record_proxy.py`: it sits between the agent and the model
server and journals requests/responses; `SseAssembler.java` handles the streamed-response
reassembly. `metrics/Collect.java`, `metrics/Trajectory.java` and `metrics/StatsService.java` port
`collect.py`/`trajectory.py`/`stats.py` respectively. `StatsService` was read directly and confirms
the core statistical machinery: `bootCi()` (bootstrap confidence intervals), `permutationTest()`, and
`requireMatchedBudgets()` — the same "harness-effect comparisons refuse unless budgets match" rule
from the original. `MODEL_AB_EXEMPT` (the setup-dependent fields exempted from a model A/B's
comparability key — window, derived knobs, budgets, system-prompt SHA) is also present and correctly
named (this is an "A/B" as in *model* A/B testing, unrelated to this project's own "AB → ACE" rename;
it was deliberately left alone during that rename for exactly that reason).

## 9. Harness modes

Both modes described in the original design are present in `RunBench.java`:

- **Monolithic**: one growing session per phase, with structural compaction triggered by a token
  threshold as context accretes.
- **Orchestrated**: task-by-task, each with a composed context — a stable pack in the system prompt
  (`Packs.stablePack()`), a deterministic per-task pack (`Packs.taskPack()`, capturing the task entry,
  dependency interfaces, repo tree, previous task's diff, harness-run test verification, and progress
  notes), a wrap-up stop protocol, and a fixed integration task at the end. The class-level Javadoc on
  `RunBench` additionally claims **parallel waves** are supported (`Packs.parallelPlanPack()` exists,
  and `RunBench` has merge/conflict-handling code for parallel task waves feeding into the integration
  task) — this parallel-orchestration capability was part of the original project's later work and
  appears to have been carried into this port, though its correctness under load was not
  independently re-verified here.

This design document does not restate the original's specific historical benchmark numbers (see §10)
since those were produced by the Python implementation on specific models and are not this port's own
results.

## 10. Results so far

Unlike the original Python harness, **this port has not yet produced a recorded benchmark run**: there
is no `results/<run>/` directory anywhere in `ace-service`, which is where `RunBench` would write a
run's manifest/oracle/metrics/trajectory output. What exists instead, and is real, verifiable evidence
of this port's own correctness work:

- **66 code-review findings, all fixed** (`review.txt`, `review_fixes.txt`), spanning bugs, resource
  leaks, race conditions, and dead code across the agent/runner/oracle/service packages, each closed
  with a cited commit.
- **139+ unit and integration tests passing** (`./gradlew test`), covering the oracle checks, the
  scorer, plan parsing, the recording proxy, journal-fact extraction, and the service layer (job
  queue, worker, experiments, importer) against a stub server and Testcontainers Postgres.
  `./gradlew dockerTest` additionally builds this service's own Dockerfile into a real image and
  drives it end-to-end (git preflight, JDK pin, a real HTTP round trip) — verified passing against a
  live Docker daemon during this port's development.
- **No functional/composite scores against a live model yet.** Running the harness against an actual
  local or hosted model — the thing the original's §10 table reports — has not happened in this
  repository as far as its own artifacts show.

## 11. Known limitations

Carried over from the original design, and still true of this port as far as could be verified:

- The agent is not sandboxed: it runs as the operator's OS user with network access on the host.
- Compose runs on the host Docker daemon (no rootless/DinD).
- The gaming-resistance fixture suite (positive control + adversarial cheats + selftest) described in
  the original design is **not present** in this port (§6) — this is the most consequential gap to
  close before trusting a leaderboard number from it in an adversarial setting.
- The `reasoning_effort` treatment may not be actually transmitted to the model despite being
  journaled and configured (§7c) — worth resolving before comparing models/configs on that axis.
- Per-step scoring, isolated-step measurement, and the review-calibration tooling described in the
  original design's later sections were not exhaustively re-verified against this port's code beyond
  confirming that `Packs.java` carries `reviewPack`/`trajectoryReviewPack`/`fixPack` methods; treat
  their exact behaviour as unconfirmed until read directly.

## 12. How to run

```bash
# from the repo root (agentic-coding-evaluation/) — ace-service is a Gradle subproject
./gradlew :ace-service:test                 # unit + stub-server + Testcontainers-Postgres integration tests
./gradlew :ace-service:dockerTest           # builds this service's own Dockerfile into a real image and
                                             # drives it end-to-end (needs a running Docker daemon)
./gradlew :ace-service:bootRun              # run the service directly
```

The original's Python CLI entry points (`runner/run_bench.py --task ... --mode orchestrated ...`,
`runner/harness-effect.sh`, `runner/model-ab.sh`, `oracle/run_oracle.py --selftest`) do not have a
verified 1:1 Gradle-task equivalent in this port as of this document — `RunSpec.java` builds a
`--harness=` style argument list suggesting an analogous CLI/queue-driven run path exists via the
service's job queue (`service/JobQueue.java`, `service/WorkerService.java`) and the Vaadin UI's job
pages, but the exact command surface was not enumerated here.

## 13. Design rules distilled

These are the original project's design rules. They are methodology, not code, and remain the
standard this port is held to regardless of language:

1. Fix the denominator; absence is failure; a crash can never raise a score; partial runs are never quoted.
2. The only correctness evidence is an oracle-owned test against the *running* system; self-authored green is not evidence; mutation-test the suite; freeze the contract or there is nothing to test against.
3. Build a positive control before trusting a negative gate: it finds the oracle's own bugs. (Not yet built in this port — §6, §10.)
4. Separate infrastructure failure from agent failure, and never charge the machine to the model.
5. Pin what you claim: sampler parameters via the proxy, packages by version, prompts by hash; record what was *sent*, not what was configured.
6. Kill process trees, mark run processes in the environment, never read a child's stdout through a pipe its grandchildren can inherit.
7. Compose the agent's context; do not let it accrete. Stable prefix first, per-task pack next, reachable stop conditions, a harness-owned status trail.
8. One run is one draw: k ≥ 5, CIs, pass^k, and never pool runs whose harness, budgets, model, oracle or contract differ.
9. Every state-changing scenario must assert the intermediate state, or a hollow server passes; a mutation test needs a green suite, or a red suite "catches" everything.
10. Match total budgets before claiming a harness effect; a session that died on a stop-policy artefact is not an observation.
11. Trust harness-verified completion, never the model's own "done".
12. A self-review is a self-report: score it by calibration against an oracle or an objective index, never add it to the correctness score; an independent reviewer's judgment may count directly, with its identity on the record.
13. Build the runtime under test from a clean checkout — anything the agent left in its working tree is not the code — and keep build outputs out of the snapshots too.
14. Pin the toolchain the agent and the harness share (JDK, Gradle) and prove it with a real smoke test before the clock starts; a verification that could not run is inconclusive, never the agent's RED.
15. Censor both arms of a comparison the same way; the runner is part of the treatment, so its hash is part of the comparability key; a matched budget is checked numerically, not assumed.
16. Score every step, not just the end state — but attribute honestly and measure a step in isolation from a fixed prior state before choosing a model for it.
17. Measure the context window the setup actually offers before anything else, and derive every context-dependent knob from that measurement, never from a number typed into a config.
18. When the grader's contract changes, the specification the agent reads changes in the same commit — and the positive control must be able to fail the new rule, or add a fixture that does.
