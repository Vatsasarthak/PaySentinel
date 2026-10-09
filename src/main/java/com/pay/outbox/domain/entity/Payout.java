package com.pay.outbox.domain.entity;

import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payouts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayoutStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "funds_status", nullable = false)
    private FundsStatus fundsStatus;

    /** API client that created the payout; idempotency keys are unique per client. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Payment reference returned by the payment provider for a completed payout. */
    @Column(name = "provider_reference")
    private String providerReference;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = createdAt;
        if (status == null) status = PayoutStatus.RESERVING;
        if (fundsStatus == null) fundsStatus = FundsStatus.NONE;
        if (attempts == null) attempts = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
