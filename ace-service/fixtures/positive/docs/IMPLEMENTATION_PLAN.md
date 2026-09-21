# Implementation plan

Ordered subtasks; each is independently completable and verifiable.

## T1 — Schema and entities (account-service)
- Goal: Flyway `V1` creates accounts, orders, ledger, idempotency_keys with non-negative CHECKs; JPA entities validate against it.
- Services: account-service. Dependencies: none.
- Acceptance criterion: `gradle :account-service:test` boots the context against PostgreSQL with `ddl-auto: validate`; a negative balance is rejected by the DB.
- Oracle: S9, B1, M1, M2.

## T2 — Accounts and deposits
- Goal: implement `POST /accounts`, `GET /accounts/{id}`, `POST /accounts/{id}/deposits` with HALF_EVEN rounding and string decimals.
- Services: account-service. Dependencies: T1.
- Acceptance criterion: test `depositsRoundHalfEvenAndReadBackAsStrings` passes (0.005→0.00, 0.015→0.02, 0.10+0.20→"0.30", 0→400).
- Oracle: F5, F7.

## T3 — Orders, ledger, holdings
- Goal: implement immediate-fill `POST /orders` (201/422/400), `GET /orders/{id}`, cancel with the state machine (409 on terminal), and `GET /accounts/{id}/holdings?asOf` replaying the ledger strictly before `asOf` and subtracting sells.
- Services: account-service. Dependencies: T2.
- Acceptance criterion: tests `buyThenEqualSellRestoresBalanceAndHoldings`, `partialSellSubtractsFromHoldings`, `insufficientFundsAndHoldingsAre422`, `cancellingAFilledOrderIs409`, `asOfBoundaryIsExclusiveAndEchoed` pass.
- Oracle: F1, F2, F3, F4, F8, M3, M4, B3.

## T4 — Idempotency
- Goal: `Idempotency-Key` on `POST /orders` returns the same order with 200 within 60 s and does not execute twice.
- Services: account-service. Dependencies: T3.
- Acceptance criterion: test `idempotencyKeyReplaysWithout2ndExecution` passes (balance unchanged after the repeat).
- Oracle: F9.

## T5 — market-service
- Goal: `GET /prices/{symbol}` from the fixed table, 404 otherwise; `/health`.
- Services: market-service. Dependencies: none.
- Acceptance criterion: `PricesControllerTest` passes (AAPL→"10.00", unknown→404).
- Oracle: S5.

## T6 — gateway and compose
- Goal: gateway routes and passes status codes through, aggregates `/health`; compose brings db, three services and web to `healthy` with `depends_on: service_healthy`; every image builds its application inside the image (multi-stage Dockerfiles) — `docker compose build` must succeed from a clean checkout with no build outputs, which is how it is graded.
- Services: gateway, web. Dependencies: T3, T5.
- Acceptance criterion: `ProxyControllerTest` passes; `docker compose up` reports every non-db service healthy and `GET localhost:8080/health` returns 200.
- Oracle: S4, S6, S7, S8, C1, C2, F6.
