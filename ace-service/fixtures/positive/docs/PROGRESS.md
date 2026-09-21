# Progress

| Subtask | Status | Verified by |
|---|---|---|
| T1 — Schema and entities | done | `gradle :account-service:test` boots against PostgreSQL with `ddl-auto: validate`; Flyway `V1` applies; CHECK constraints present |
| T2 — Accounts and deposits | done | `AccountApiTest.depositsRoundHalfEvenAndReadBackAsStrings` green |
| T3 — Orders, ledger, holdings | done | `AccountApiTest` symmetry / partial sell / 422 / 409 / asOf-exclusive tests green |
| T4 — Idempotency | done | `AccountApiTest.idempotencyKeyReplaysWithout2ndExecution` green |
| T5 — market-service | done | `PricesControllerTest` green |
| T6 — gateway and compose | done | `ProxyControllerTest` green; `docker compose up` → 4 non-db services `healthy`, `GET /health` 200 |

Full suite: `gradle test` from the repository root (needs `SPRING_DATASOURCE_URL` pointing at a PostgreSQL for the account-service tests).
