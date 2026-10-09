package com.pay.outbox.exception;

/** The API key is not authorized to use the requested ledger account. */
public class AccountAccessDeniedException extends RuntimeException {
    public AccountAccessDeniedException(String message) {
        super(message);
    }
}
