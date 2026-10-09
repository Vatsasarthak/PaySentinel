package com.pay.outbox.repository;

import com.pay.outbox.domain.entity.OutboxEvent;
import com.pay.outbox.domain.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByStatus(OutboxStatus status);

    Optional<OutboxEvent> findFirstByPayoutId(UUID payoutId);

    /**
     * Locks the oldest pending events. SKIP LOCKED lets several relay instances work in parallel
     * without publishing the same event twice, even if the ShedLock lease were to expire.
     * Must be called inside a transaction.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE status = 'PENDING'
             ORDER BY created_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEvent> lockNextPendingBatch(int limit);

    /** Marks a relayed event for re-publication (used when its queue message was lost). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxEvent e set e.status = :pending
             where e.payoutId = :payoutId and e.status = :relayed""")
    int rearm(UUID payoutId, OutboxStatus relayed, OutboxStatus pending);

    default int rearm(UUID payoutId) {
        return rearm(payoutId, OutboxStatus.RELAYED, OutboxStatus.PENDING);
    }
}
