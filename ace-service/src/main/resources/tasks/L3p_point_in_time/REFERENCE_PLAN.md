# Implementation plan (reference, for `plan_source: reference`)

Ordered subtasks; each is independently completable and verifiable. Identical for every model, so
per-task results are comparable. `Oracle:` names the oracle checks each task is responsible for (per-step
scoring; scored at the final state - a later task can still break or repair them). The dependency graph is
T1 → {T2, T4} → T3: in parallel-orchestrated mode T2 and T4 run concurrently (wave 2).

**Not every scored check is attributed to a task above.** `tasks/ladder.json`'s check set for this
rung is `M1, M2, M4, P2, B1, B2, B3, C2, F1, F2, F3, F8` (denominator 36); `P2` ("plan subtasks have
id/goal/deps/criterion") and `B2` (">0 tests executed and passing, XML-verified") are whole-run
checks against the plan and the complete test suite respectively, not any one subtask's own
acceptance criterion, so they intentionally appear in no `Oracle:` line above.

## T1 — Project skeleton, schema and accounts
- Goal: a Gradle Spring Boot `account-service` with Flyway `V1` (accounts, orders, ledger tables with non-negative CHECKs), `POST /accounts` (201), `GET /accounts/{id}` (200/404), `POST /accounts/{id}/deposits` (200, HALF_EVEN to 2 dp, 400 on non-positive), `GET /health` (200); decimals as JSON strings.
- Services: account-service.
- Dependencies: none.
- Acceptance criterion: `gradle test` against the provided PostgreSQL: create → deposit `"0.005"` reads back `"0.00"`, `"0.015"` reads back `"0.02"`, `"0.10"`+`"0.20"` reads back `"0.30"`, non-positive → 400, unknown id → 404.
- Oracle: B1, M1, M2.

## T2 — Orders with immediate fill
- Goal: `POST /orders` `{accountId, symbol, side, quantity, limitPrice}` → 201 `{orderId, status:"FILLED", executedAt}` (UTC millis), debiting/crediting quantity × limitPrice; 422 on insufficient funds (BUY) or holdings (SELL); 400 on invalid input; every fill appends a ledger entry (symbol, side, quantity, executedAt). Symbols AAPL, TSLA, EURUSD.
- Services: account-service.
- Dependencies: T1.
- Acceptance criterion: tests: buy 5 then sell 5 at the same price restores the balance exactly; BUY of 1,000,000 → 422 with the balance unchanged; SELL without holdings → 422.
- Oracle: F3.

## T3 — Point-in-time holdings
- Goal: `GET /accounts/{id}/holdings?asOf=<ISO-8601>` → 200 `{holdings:{SYM:"qty"}, asOfApplied}` replaying the ledger STRICTLY BEFORE `asOf` (exclusive) and SUBTRACTING sells; `asOfApplied` always present, UTC millis with trailing `Z` (now when `asOf` is absent); 400 on an unparseable `asOf`.
- Services: account-service.
- Dependencies: T2.
- Acceptance criterion: tests: buy 5, sell 2 → holdings 3 (an adding replay gives 7); `asOf` = the buy's `executedAt` → the buy is excluded (0) and `asOfApplied` equals it; `asOf` between a buy and a later sell shows the pre-sell position.
- Oracle: F1, F2, F8, M4, B3.

## T4 — Container and compose
- Goal: packaging needs only the service skeleton and its datasource configuration from the first task, not the order or holdings endpoints, so it can be built alongside them: a multi-stage Dockerfile for account-service that builds the jar INSIDE the image (a Gradle build stage, then a JRE runtime stage — never `COPY` a jar built on the host) and a `docker-compose.yml` with PostgreSQL (healthcheck) and the service on `localhost:8080` with a healthcheck on `/health` and `depends_on: condition: service_healthy`; the service retries the database on startup instead of crash-looping.
- Services: account-service, infrastructure.
- Dependencies: T1.
- Acceptance criterion: `docker compose build` succeeds from a clean checkout that contains no `build/` directory (that is how it is graded), the compose file passes the datasource env vars, and `gradle test` is still green from the repository root.
- Oracle: C2.
