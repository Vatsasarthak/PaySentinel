package com.pay.outbox.exception;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Builds the {@code type} URI of every problem-details error response, e.g.
 * {@code urn:paysentinel:errors:validation-failed}. A URN identifies the error kind without depending on
 * a web domain; clients should match on the full value or its last segment.
 */
public final class ProblemTypes {

    public static final String BASE = "urn:paysentinel:errors:";

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private ProblemTypes() {
    }

    /** Returns the type URI for a lower-case, hyphen-separated error slug such as {@code rate-limited}. */
    public static URI of(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Invalid problem type slug: " + slug);
        }
        return URI.create(BASE + slug);
    }
}
