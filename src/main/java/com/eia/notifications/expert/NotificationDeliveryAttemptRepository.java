package com.eia.notifications.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Explicit SQL persistence for delivery attempts. Every query is tenant-scoped;
 * the caller has already verified that the notification belongs to the tenant.
 */
@Repository
public class NotificationDeliveryAttemptRepository {

    private final JdbcTemplate jdbc;

    public NotificationDeliveryAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<NotificationDeliveryAttempt> MAPPER = (rs, rowNum) ->
            new NotificationDeliveryAttempt(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("notification_id", UUID.class),
                    rs.getInt("attempt_number"),
                    rs.getString("channel"),
                    rs.getString("provider"),
                    rs.getString("provider_reference"),
                    rs.getString("status"),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    instant(rs, "attempted_at")
            );

    public List<NotificationDeliveryAttempt> findAll(UUID tenantId, UUID notificationId) {
        return jdbc.query("""
                SELECT id, tenant_id, notification_id, attempt_number, channel, provider,
                       provider_reference, status, error_code, error_message, attempted_at
                  FROM notification_delivery_attempt
                 WHERE tenant_id = ? AND notification_id = ?
                 ORDER BY attempt_number
                """, MAPPER, tenantId, notificationId);
    }

    public NotificationDeliveryAttempt insert(NotificationDeliveryAttempt attempt) {
        jdbc.update("""
                INSERT INTO notification_delivery_attempt (
                    id, tenant_id, notification_id, attempt_number, channel, provider,
                    provider_reference, status, error_code, error_message, attempted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attempt.id(), attempt.tenantId(), attempt.notificationId(), attempt.attemptNumber(),
                attempt.channel(), attempt.provider(), attempt.providerReference(), attempt.status(),
                attempt.errorCode(), attempt.errorMessage(), timestamp(attempt.attemptedAt()));
        return attempt;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
