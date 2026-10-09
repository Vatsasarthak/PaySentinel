package com.pay.outbox.exception;

/** The idempotency key belongs to a different request, or its original request is still in progress. */
public class IdempotencyConflictException extends RuntimeException {

    private final boolean inProgress;

    public IdempotencyConflictException(String message, boolean inProgress) {
        super(message);
        this.inProgress = inProgress;
    }

    public boolean isInProgress() {
        return inProgress;
    }
}
