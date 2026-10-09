package com.pay.outbox.exception;

public class PayoutNotFoundException extends RuntimeException {
    public PayoutNotFoundException(String message) {
        super(message);
    }
}