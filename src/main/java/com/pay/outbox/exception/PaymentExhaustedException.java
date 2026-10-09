package com.pay.outbox.exception;

public class PaymentExhaustedException extends RuntimeException {
    public PaymentExhaustedException(String message) {
        super(message);
    }
}
