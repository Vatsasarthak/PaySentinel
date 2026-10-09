package com.pay.outbox.dto;

public final class ValidationPatterns {

    /** Account and recipient identifiers: letters, digits, underscore and hyphen. */
    public static final String ACCOUNT_ID = "^[A-Za-z0-9_-]{1,64}$";
    public static final String ACCOUNT_ID_MESSAGE = "must be 1-64 characters: letters, digits, '_' or '-'";

    /** ISO 4217 alphabetic code format. Which codes are accepted is configured separately. */
    public static final String CURRENCY = "^[A-Z]{3}$";
    public static final String CURRENCY_MESSAGE = "must be a 3-letter uppercase ISO 4217 code";

    public static final String IDEMPOTENCY_KEY = "^[A-Za-z0-9._:-]{8,128}$";
    public static final String IDEMPOTENCY_KEY_MESSAGE = "must be 8-128 characters: letters, digits, '.', '_', ':' or '-'";

    /** Upper bound on a single payout or seeded balance, in integer currency units. */
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private ValidationPatterns() {
    }
}
