package com.pay.outbox.service.gateway;

public enum GatewayOutcome {
    SUCCESS,
    DECLINED,
    /** The provider never received or processed the request; the call is retried with backoff. */
    TIMEOUT,
    /** The provider executed the payment but the response was lost; a retry must not pay again. */
    TIMEOUT_AFTER_PROCESSING
}
