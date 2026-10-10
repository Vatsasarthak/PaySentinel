package com.pay.outbox.exception;

/** A treasury resource (adjustment or reviewable payout) does not exist or is not visible to the caller. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
