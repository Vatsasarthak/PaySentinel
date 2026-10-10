package com.pay.outbox.exception;

/** Dual control: the principal that requested an adjustment may not approve it (HTTP 403). */
public class FourEyesViolationException extends RuntimeException {
    public FourEyesViolationException(String message) {
        super(message);
    }
}
