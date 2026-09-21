# Task definition — trading platform (positive-control reference)

## 1. Scope and overview
A small trading platform for stocks and currencies: accounts hold cash, orders fill immediately at their
limit price against a simulated exchange, and holdings can be queried as of any instant. Three
independently deployable Spring Boot services plus a PostgreSQL database and a static React shell,
started with `docker compose up`, exposing the frozen contract on `localhost:8080`.

## 2. Actors
- **Trader** — opens accounts, deposits cash, places and cancels orders, reads holdings.
- **Operator** — runs the stack, watches `/health`.
- **Grader** — the black-box suite driving the contract.

## 3. Domain model and entities
- `Account(accountId, currency, availableBalance, reservedBalance)` — balances are `NUMERIC(19,4)`, never negative (DB CHECK + service guard).
- `Order(orderId, accountId, symbol, side, quantity, limitPrice, status, executedAt)`.
- `LedgerEntry(accountId, symbol, side, quantity, executedAt)` — the append-only position history replayed for holdings.
- `IdempotencyKey(key, orderId, expiresAt)`.

## 4. Service boundaries (microservices)
- **account-service** (8081): accounts, deposits, orders, ledger, holdings — owns the `trading` schema.
- **market-service** (8082): the fixed price table; stateless.
- **gateway** (8080): the public edge; routes `/prices` to market-service and everything else to account-service; aggregates `/health`.
- **web**: static React shell served by nginx, proxying `/api/` to the gateway.
Services talk only over HTTP; no schema is shared across a boundary.

## 5. Money handling
All money is `BigDecimal` with explicit scale; amounts are rounded **HALF_EVEN to 2 dp** on deposit and on
order cost; comparisons use `compareTo`; every decimal is serialised as a JSON **string**. Positivity of a
deposit is judged on the raw amount before rounding.

## 6. Order states
State machine `NEW → FILLED | CANCELLED`; `FILLED` and `CANCELLED` are terminal and cancelling them is an
illegal transition answered with **409**. Because fills are immediate, a fresh order is already `FILLED`.

## 7. Non-functional requirements
- **Latency**: any contract call answers within 500 ms on the reference hardware.
- **Consistency**: a buy then an equal sell restores balance and holdings exactly; point-in-time replay is
  strictly-before (`asOf` exclusive) and subtracts sells.
- **Auditability**: the ledger is append-only; every order carries `executedAt` at millisecond precision.
- **Idempotency**: `Idempotency-Key` on `POST /orders` replays the same order for 60 s with 200.

## 8. Out of scope
Short selling, margin, partial fills, real market data, authentication, multi-currency conversion.
