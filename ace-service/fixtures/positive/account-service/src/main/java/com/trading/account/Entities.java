package com.trading.account;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class Entities { private Entities() {} }

@Entity @Table(name = "accounts")
class AccountEntity {
    @Id @Column(name = "account_id") UUID accountId;
    @JdbcTypeCode(Types.CHAR) @Column(nullable = false, length = 3) String currency;   // CHAR(3) in the migration
    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4) BigDecimal availableBalance = BigDecimal.ZERO;
    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4) BigDecimal reservedBalance = BigDecimal.ZERO;
}

@Entity @Table(name = "orders")
class OrderEntity {
    @Id @Column(name = "order_id") UUID orderId;
    @Column(name = "account_id", nullable = false) UUID accountId;
    @Column(nullable = false) String symbol;
    @Column(nullable = false) String side;
    @Column(nullable = false, precision = 19, scale = 4) BigDecimal quantity;
    @Column(name = "limit_price", nullable = false, precision = 19, scale = 4) BigDecimal limitPrice;
    @Column(nullable = false) String status;
    @Column(name = "executed_at") Instant executedAt;
}

@Entity @Table(name = "ledger")
class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "entry_id") Long entryId;
    @Column(name = "account_id", nullable = false) UUID accountId;
    @Column(nullable = false) String symbol;
    @Column(nullable = false) String side;
    @Column(nullable = false, precision = 19, scale = 4) BigDecimal quantity;
    @Column(name = "executed_at", nullable = false) Instant executedAt;
}

@Entity @Table(name = "idempotency_keys")
class IdempotencyKey {
    @Id @Column(name = "idem_key") String key;
    @Column(name = "order_id", nullable = false) UUID orderId;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
}
