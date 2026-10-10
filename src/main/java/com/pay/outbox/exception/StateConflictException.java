package com.pay.outbox.exception;

/** The requested transition is not allowed in the resource's current state (HTTP 409). */
public class StateConflictException extends RuntimeException {
    public StateConflictException(String message) {
        super(message);
    }
}
