package com.pay.outbox.exception;

public class PaymentHangException extends RuntimeException {
    public PaymentHangException(String message) {
        super(message);
    }
}