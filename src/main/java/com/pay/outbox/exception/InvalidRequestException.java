package com.pay.outbox.exception;

/** A request field failed a business validation rule that bean validation cannot express. */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
