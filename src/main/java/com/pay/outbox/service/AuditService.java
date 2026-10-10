package com.pay.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit log for manual and automated decisions that move money (funding adjustments,
 * manual-review resolutions, development seeding). Rows are written in the same transaction as the
 * change they describe, and the database refuses updates and deletes.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    public record AuditEvent(long id, Instant occurredAt, String actor, String action, String targetType,
                             String targetId, String details) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String targetType, String targetId, Map<String, ?> details) {
        jdbc.update("""
                INSERT INTO audit_events (occurred_at, actor, action, target_type, target_id, details)
                VALUES (?, ?, ?, ?, ?, ?)""",
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), actor, action, targetType, targetId,
                toJson(details));
    }

    public List<AuditEvent> eventsFor(String targetType, String targetId) {
        return jdbc.query("""
                SELECT id, occurred_at, actor, action, target_type, target_id, details
                  FROM audit_events WHERE target_type = ? AND target_id = ? ORDER BY id""",
                (rs, i) -> new AuditEvent(rs.getLong(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                targetType, targetId);
    }

    /** Builds an ordered details map that, unlike {@link Map#of}, accepts null values. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            details.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return details;
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise audit details", e);
        }
    }
}
