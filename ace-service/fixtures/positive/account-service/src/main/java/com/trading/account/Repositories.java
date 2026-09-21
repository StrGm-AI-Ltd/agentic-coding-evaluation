package com.trading.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AccountRepository extends JpaRepository<AccountEntity, UUID> {}
interface OrderRepository extends JpaRepository<OrderEntity, UUID> {}
interface IdempotencyRepository extends JpaRepository<IdempotencyKey, String> {}
interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByAccountIdAndExecutedAtLessThanOrderByExecutedAtAscEntryIdAsc(UUID accountId, Instant before);
}
