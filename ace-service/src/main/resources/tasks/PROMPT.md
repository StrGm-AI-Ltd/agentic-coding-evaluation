# Benchmark Task — Production Trading Platform

You are implementing a **production-ready trading platform** for stocks and currencies.
Work through the phases **in order**. Do not skip ahead.

## Hard constraints

| Area | Requirement |
|---|---|
| Backend | **Java 21**, **Spring Boot 3.x**, **Gradle** (Kotlin or Groovy DSL) |
| Database | **PostgreSQL** with versioned migrations (Flyway or Liquibase) |
| Frontend | **React** (TypeScript), built and served as a container |
| API | **OpenAPI 3.1** contract; the spec is the source of truth |
| Packaging | **Docker**; the whole system must start with `docker compose up` |
| Architecture | **Microservices** — at least 3 independently deployable services |
| Tests | Every subtask ships tests that run non-interactively |

## Phases

### Phase 0 — Task definition
Write `docs/TASK_DEFINITION.md`: scope, actors, domain model, service boundaries,
non-functional requirements (latency, consistency, auditability), and explicit
out-of-scope items. Money handling and order-state transitions must be specified
precisely. **No code in this phase.**

### Phase 1 — Decomposition and plan
Write `docs/IMPLEMENTATION_PLAN.md`: ordered subtasks, each with an id, a one-line
goal, affected services, dependencies, and a **verifiable acceptance criterion**.
Each subtask must be completable and testable on its own.

### Phase 2..N — Implement
Implement subtasks **one at a time**, in dependency order. For each:
1. implement it,
2. write tests that prove the acceptance criterion,
3. run the tests and make them pass,
4. record the result in `docs/PROGRESS.md` (subtask id, status, how it was verified).

## Correctness rules that will be checked independently

- **Money is never a float.** Use `BigDecimal` or integer minor units throughout.
- **Orders follow a documented state machine**; illegal transitions are rejected.
- **No negative balances or positions** unless short-selling is explicitly specified.
- **Buy/sell are symmetric**: a buy then an equal sell returns holdings to the prior state.
- **Point-in-time queries subtract sells** rather than adding them.
- The **OpenAPI spec matches the implementation** — paths, status codes, schemas.
- Services are independently buildable; no shared mutable database schema across
  service boundaries.

## Definition of done

`docker compose up` brings the system to a healthy state, the OpenAPI spec validates,
every subtask in `PROGRESS.md` is marked done with evidence, and the full test suite
passes from a clean checkout. **Packaging is graded from source:** the grader runs `docker compose build` on a clean checkout that contains NO build outputs (no `build/`, `target/`, `dist/`, no committed `.jar`/`.class` files). Every image must therefore build the application inside the image (e.g. a multi-stage Dockerfile with a Gradle build stage); a Dockerfile that `COPY`s a jar or bundle built on the host fails the runtime checks.

## Frozen API contract — verified by tests you do not control

Your system MUST expose this contract at **`http://localhost:8080`** (map it in compose). Decimal
values are JSON **strings** (e.g. `"100.00"`), never numbers. Timestamps are ISO-8601 UTC with
**millisecond precision** (`2026-01-31T12:00:00.123Z`); `executedAt` is stored and compared at that
precision. Orders execute **immediately** at `limitPrice` against a simulated exchange if
funds/holdings suffice; there is no external market. The price feed is a **fixed table**:
`AAPL = "10.00"`, `TSLA = "250.00"`, `EURUSD = "1.0850"`; any other symbol is unknown (404).

| Method & path | Request | Success | Failure |
|---|---|---|---|
| `POST /accounts` | `{"currency":"USD"}` | **201** `{accountId, currency, availableBalance:"0.00"}` | |
| `GET /accounts/{id}` | | **200** `{accountId, currency, availableBalance, reservedBalance}` | 404 |
| `POST /accounts/{id}/deposits` | `{"amount":"100.00"}` | **200** `{availableBalance}` — amount rounded **HALF_EVEN to 2 dp** | 400 if the raw amount is non-positive |
| `POST /orders` | `{accountId, symbol, side:"BUY"\|"SELL", quantity:"5", limitPrice:"10.00"}`; optional header `Idempotency-Key` | **201** `{orderId, status:"FILLED", executedAt}`; **200** + the same order on an idempotent repeat within 60 s | **422** insufficient funds/holdings; 400 invalid |
| `GET /orders/{id}` | | **200** `{orderId, status, side, symbol, quantity, limitPrice}` | 404 |
| `POST /orders/{id}/cancel` | | **200** `{orderId, status:"CANCELLED"}` | **409** if already FILLED/CANCELLED |
| `GET /accounts/{id}/holdings` | optional `?asOf=<ISO-8601 instant>` | **200** `{holdings:{"AAPL":"5"}, asOfApplied}` — replays history **strictly before** `asOf` (exclusive boundary); `asOfApplied` is **always present**: the applied instant (or "now" when `asOf` is absent) normalised to UTC milliseconds `...Z` | 404 |
| `GET /prices/{symbol}` | | **200** `{symbol, price}` from the fixed table above | 404 unknown |
| `GET /health` | | **200**, any body | |

**Order state machine:** `NEW → FILLED` or `NEW → CANCELLED`. `FILLED` and `CANCELLED` are terminal.
Cancelling a terminal order is an **illegal transition → 409**. Because orders fill immediately, a
freshly created order is already `FILLED`, so cancelling it must return 409.

**Test environment:** the grader runs `gradle test` in a clean JDK 21 container **with a fresh
PostgreSQL** reachable through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD` (also `AB_DB_HOST/PORT/NAME/USER/PASSWORD`). Tests must not require
Docker or the network; they may use that database or run purely in memory.

**Semantic rules the black-box suite enforces:** buy N then sell N of the same symbol at the same price
restores `availableBalance` and holdings exactly; `holdings?asOf=T` for T between a buy and a later sell
shows the position *before* the sell; a `0.10` deposit followed by `0.20` reads back as exactly `"0.30"`.
