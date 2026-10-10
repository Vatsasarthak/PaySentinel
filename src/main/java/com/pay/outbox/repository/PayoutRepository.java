package com.pay.outbox.repository;

import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every payout state transition is a conditional UPDATE so that concurrent request threads,
 * workers and recovery jobs (possibly on different instances) cannot overwrite each other.
 * Each method returns the number of rows changed; 0 means another actor got there first.
 */
@Repository
public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    List<Payout> findByFundsStatusIn(Collection<FundsStatus> fundsStatuses, Pageable page);

    @Query("select p.attempts from Payout p where p.id = :id")
    Integer findAttemptsById(UUID id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payout p
               set p.status = :processing, p.attempts = p.attempts + 1,
                   p.leaseUntil = :leaseUntil, p.updatedAt = :now
             where p.id = :id and p.fundsStatus = :held and p.attempts < :maxAttempts
               and (p.status = :pending or (p.status = :processing and p.leaseUntil < :now))""")
    int claim(UUID id, Instant leaseUntil, Instant now, int maxAttempts,
              PayoutStatus pending, PayoutStatus processing, FundsStatus held);

    /** Claims a payout for processing; succeeds only if it is PENDING or its previous lease expired. */
    default int claim(UUID id, Instant leaseUntil, Instant now, int maxAttempts) {
        return claim(id, leaseUntil, now, maxAttempts, PayoutStatus.PENDING, PayoutStatus.PROCESSING,
                FundsStatus.HELD);
    }

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payout p
               set p.status = :to, p.fundsStatus = :funds, p.failureReason = :reason,
                   p.providerReference = :providerReference, p.leaseUntil = null, p.updatedAt = :now
             where p.id = :id and p.fundsStatus = :held and p.attempts >= :maxAttempts
               and (p.status = :pending or (p.status = :processing and p.leaseUntil < :now))""")
    int resolveExhausted(UUID id, PayoutStatus to, FundsStatus funds, String reason, String providerReference,
                         Instant now, int maxAttempts, PayoutStatus pending, PayoutStatus processing,
                         FundsStatus held);

    /**
     * Settles the fate of a payout whose delivery budget is spent (and on which nobody holds a live
     * lease), based on what the provider reports. Must run in the same transaction as the matching
     * ledger settlement or release; MANUAL_REVIEW keeps the funds held.
     */
    default int resolveExhausted(UUID id, PayoutStatus to, FundsStatus funds, String reason,
                                 String providerReference, Instant now, int maxAttempts) {
        return resolveExhausted(id, to, funds, reason, providerReference, now, maxAttempts, PayoutStatus.PENDING,
                PayoutStatus.PROCESSING, FundsStatus.HELD);
    }

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payout p
               set p.status = :review, p.failureReason = :reason, p.leaseUntil = null, p.updatedAt = :now
             where p.id = :id and p.status = :processing and p.attempts = :attempt""")
    int markForReview(UUID id, int attempt, String reason, Instant now, PayoutStatus processing, PayoutStatus review);

    /** Parks a claimed payout in MANUAL_REVIEW (funds stay held), fenced by the claim's attempt number. */
    default int markForReview(UUID id, int attempt, String reason, Instant now) {
        return markForReview(id, attempt, reason, now, PayoutStatus.PROCESSING, PayoutStatus.MANUAL_REVIEW);
    }

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payout p
               set p.status = :to, p.fundsStatus = :funds, p.failureReason = :reason,
                   p.providerReference = :providerReference, p.updatedAt = :now
             where p.id = :id and p.status = :review""")
    int resolveReview(UUID id, PayoutStatus to, FundsStatus funds, String reason, String providerReference,
                      Instant now, PayoutStatus review);

    /** Leaves MANUAL_REVIEW exactly once; must run in the same transaction as the ledger settle or release. */
    default int resolveReview(UUID id, PayoutStatus to, FundsStatus funds, String reason, String providerReference,
                              Instant now) {
        return resolveReview(id, to, funds, reason, providerReference, now, PayoutStatus.MANUAL_REVIEW);
    }

    List<Payout> findByStatusOrderByUpdatedAt(PayoutStatus status, Pageable page);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payout p
               set p.status = :to, p.fundsStatus = :funds, p.failureReason = :reason,
                   p.providerReference = :providerReference, p.leaseUntil = null, p.updatedAt = :now
             where p.id = :id and p.status = :processing and p.attempts = :attempt""")
    int finishProcessing(UUID id, int attempt, PayoutStatus to, FundsStatus funds, String reason,
                         String providerReference, Instant now, PayoutStatus processing);

    /**
     * Records the gateway outcome. {@code attempt} is the fencing token returned by the claim: a worker
     * whose lease expired and was re-claimed by another worker can no longer write. Must run in the
     * same transaction as the matching ledger settlement or release.
     */
    default int finishProcessing(UUID id, int attempt, PayoutStatus to, FundsStatus funds, String reason,
                                 String providerReference, Instant now) {
        return finishProcessing(id, attempt, to, funds, reason, providerReference, now, PayoutStatus.PROCESSING);
    }

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payout p
               set p.fundsStatus = :to, p.updatedAt = :now
             where p.id = :id and p.fundsStatus = :from""")
    int updateFundsStatus(UUID id, FundsStatus from, FundsStatus to, Instant now);

    @Query("""
            select p from Payout p
             where p.fundsStatus = :held and p.updatedAt < :cutoff
               and (p.status = :pending or (p.status = :processing and p.leaseUntil < :now))
             order by p.updatedAt""")
    List<Payout> findStuck(Instant cutoff, Instant now, PayoutStatus pending, PayoutStatus processing,
                           FundsStatus held, Pageable page);

    /** Payouts that have waited longer than the cutoff without reaching a terminal state. */
    default List<Payout> findStuck(Instant cutoff, Instant now, Pageable page) {
        return findStuck(cutoff, now, PayoutStatus.PENDING, PayoutStatus.PROCESSING, FundsStatus.HELD, page);
    }

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payout p set p.updatedAt = :now where p.id = :id")
    int touch(UUID id, Instant now);
}
