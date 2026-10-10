package com.pay.outbox.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemTypesTest {

    @Test
    void buildsAnAbsoluteUrnFromTheSlug() {
        URI type = ProblemTypes.of("idempotency-key-reused");

        assertThat(type).hasToString("urn:paysentinel:errors:idempotency-key-reused");
        assertThat(type.isAbsolute()).isTrue();
        assertThat(type.getScheme()).isEqualTo("urn");
        assertThat(type.getSchemeSpecificPart()).isEqualTo("paysentinel:errors:idempotency-key-reused");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Rate-Limited", "rate limited", "rate--limited", "-rate", "rate-", "errors/rate", "rate:x"})
    void rejectsSlugsThatWouldProduceAnInconsistentType(String slug) {
        assertThatThrownBy(() -> ProblemTypes.of(slug)).isInstanceOf(IllegalArgumentException.class);
    }
}
