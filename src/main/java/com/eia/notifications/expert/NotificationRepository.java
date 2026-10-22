package com.eia.notifications.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit SQL persistence for the notification aggregate. The expert writes
 * data; business decisions live in the domain service, but the expert still
 * enforces the storage-level invariants (SPEC 7.2, 11.8.7).
 */
@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Notification> MAPPER = (rs, rowNum) -> new Notification(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("notification_number"),
            rs.getString("notification_type"),
            rs.getString("channel"),
            rs.getString("status"),
            rs.getObject("recipient_party_id", UUID.class),
            rs.getString("subject"),
            rs.getString("body"),
            rs.getString("template_code"),
            rs.getObject("template_version", Integer.class),
            instant(rs, "scheduled_at"),
            instant(rs, "sent_at"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("source_type"),
            rs.getObject("source_id", UUID.class),
            rs.getString("source_reference")
    );

    public List<Notification> findAll(UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, notification_number, notification_type, channel, status,
                       recipient_party_id, subject, body, template_code, template_version,
                       scheduled_at, sent_at, version, created_at, updated_at,
                       source_type, source_id, source_reference
                  FROM notification
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id
                """, MAPPER, tenantId);
    }

    /**
     * Tenant-scoped list of the notifications linked to exactly one source.
     * The caller supplies a complete, normalized pair; the index
     * {@code (tenant_id, source_type, source_id, created_at DESC)} serves both
     * the filter and the newest-first order.
     */
    public List<Notification> findAllBySource(UUID tenantId, String sourceType, UUID sourceId) {
        return jdbc.query("""
                SELECT id, tenant_id, notification_number, notification_type, channel, status,
                       recipient_party_id, subject, body, template_code, template_version,
                       scheduled_at, sent_at, version, created_at, updated_at,
                       source_type, source_id, source_reference
                  FROM notification
                 WHERE tenant_id = ? AND source_type = ? AND source_id = ?
                 ORDER BY created_at DESC, id
                """, MAPPER, tenantId, sourceType, sourceId);
    }

    public Optional<Notification> findById(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, notification_number, notification_type, channel, status,
                       recipient_party_id, subject, body, template_code, template_version,
                       scheduled_at, sent_at, version, created_at, updated_at,
                       source_type, source_id, source_reference
                  FROM notification
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public Notification create(CreateNotificationRequest request, String templateCode, Integer templateVersion) {
        UUID id = Uuid7.generate();
        Long sequence = jdbc.queryForObject("SELECT nextval('notification_number_seq')", Long.class);
        long next = sequence == null ? 1L : sequence;
        String notificationNumber = "NTF-" + Year.now(ZoneOffset.UTC).getValue() + "-"
                + String.format(Locale.ROOT, "%09d", next);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO notification (
                    id, tenant_id, notification_number, notification_type, channel, status,
                    recipient_party_id, subject, body, template_code, template_version,
                    scheduled_at, sent_at, version, created_at, updated_at,
                    source_type, source_id, source_reference
                ) VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?, ?, NULL, 0, ?, ?, ?, ?, ?)
                """,
                id, request.tenantId(), notificationNumber, request.notificationType(), request.channel(),
                request.recipientPartyId(), request.subject(), request.body(), templateCode, templateVersion,
                timestamp(request.scheduledAt()), now, now,
                request.sourceType(), request.sourceId(), request.sourceReference());
        return findById(request.tenantId(), id).orElseThrow();
    }

    /**
     * Conditional update: the row is only sent when it is still in a sendable
     * state, at the expected version, and the send instant is not before the
     * scheduled instant. The DB CHECK constraint seals the same invariant.
     */
    public Optional<Notification> markSent(Notification notification, Instant sentAt) {
        int updated = jdbc.update("""
                UPDATE notification
                   SET status = 'SENT', sent_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                   AND status IN ('CREATED', 'QUEUED')
                   AND (scheduled_at IS NULL OR ? >= scheduled_at)
                """,
                timestamp(sentAt), timestamp(sentAt), notification.tenantId(), notification.id(),
                notification.version(), timestamp(sentAt));
        return updated == 0 ? Optional.empty() : findById(notification.tenantId(), notification.id());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
