package com.eia.notifications.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the notification template registry. Approval is data, not a
 * hard-coded allowlist: a template may be approved or retired after a
 * notification is drafted, and the send path re-checks it.
 *
 * <p>The unique key {@code (tenant_id, template_code, template_version, locale)}
 * makes template versions tenant-local and immutable by identity; lifecycle
 * changes only move the status column.</p>
 */
@Repository
public class NotificationTemplateRepository {

    private static final String COLUMNS = """
            id, tenant_id, template_code, template_version, channel, locale, body_template,
            status, approved_by, approved_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public NotificationTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<NotificationTemplate> MAPPER = (rs, rowNum) -> new NotificationTemplate(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("template_code"),
            rs.getInt("template_version"),
            rs.getString("channel"),
            rs.getString("locale"),
            rs.getString("body_template"),
            rs.getString("status"),
            rs.getObject("approved_by", UUID.class),
            instant(rs, "approved_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at")
    );

    /**
     * Resolves the template used to issue a notification, preferring the
     * channel-specific variant and then the channel-neutral row.
     */
    public Optional<NotificationTemplate> find(UUID tenantId, String templateCode, int templateVersion,
                                               String channel) {
        return jdbc.query("""
                SELECT %s
                  FROM notification_template
                 WHERE tenant_id = ? AND template_code = ? AND template_version = ?
                   AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
                   AND (effective_to IS NULL OR effective_to > CURRENT_DATE)
                 ORDER BY CASE WHEN channel = ? THEN 0 WHEN channel IS NULL THEN 1 ELSE 2 END, locale
                 LIMIT 1
                """.formatted(COLUMNS), MAPPER, tenantId, templateCode, templateVersion, channel)
                .stream().findFirst();
    }

    /**
     * Tenant-scoped template registry ordered newest first. {@code status} and
     * {@code code} are optional exact-match filters.
     */
    public List<NotificationTemplate> findAll(UUID tenantId, String status, String code) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM notification_template WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (code != null) {
            sql.append(" AND template_code = ?");
            args.add(code);
        }
        sql.append(" ORDER BY created_at DESC, template_code, template_version DESC, id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<NotificationTemplate> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_template WHERE tenant_id = ? AND id = ?",
                MAPPER, tenantId, id).stream().findFirst();
    }

    public boolean exists(UUID tenantId, String templateCode, int templateVersion, String locale) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM notification_template
                 WHERE tenant_id = ? AND template_code = ? AND template_version = ? AND locale = ?
                """, Integer.class, tenantId, templateCode, templateVersion, locale);
        return count != null && count > 0;
    }

    public NotificationTemplate create(UUID tenantId, String templateCode, int templateVersion,
                                       String channel, String locale, String bodyTemplate) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO notification_template (
                    id, tenant_id, template_code, template_version, channel, locale, body_template,
                    status, approved_by, approved_at, effective_from, effective_to, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', NULL, NULL, NULL, NULL, ?, ?)
                """,
                id, tenantId, templateCode, templateVersion, channel, locale, bodyTemplate, now, now);
        return findById(tenantId, id).orElseThrow();
    }

    /**
     * Conditional approval: only a DRAFT row moves to ACTIVE, and the update
     * records the approver and approval instant atomically.
     */
    public Optional<NotificationTemplate> markApproved(UUID tenantId, UUID id, UUID approvedBy, Instant approvedAt) {
        int updated = jdbc.update("""
                UPDATE notification_template
                   SET status = 'ACTIVE', approved_by = ?, approved_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'
                """,
                approvedBy, Timestamp.from(approvedAt), Timestamp.from(approvedAt), tenantId, id);
        return updated == 0 ? Optional.empty() : findById(tenantId, id);
    }

    /**
     * Conditional retirement: DRAFT and ACTIVE templates can be retired; an
     * already RETIRED row is left untouched.
     */
    public Optional<NotificationTemplate> markRetired(UUID tenantId, UUID id, Instant retiredAt) {
        int updated = jdbc.update("""
                UPDATE notification_template
                   SET status = 'RETIRED', updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND status IN ('DRAFT', 'ACTIVE')
                """,
                Timestamp.from(retiredAt), tenantId, id);
        return updated == 0 ? Optional.empty() : findById(tenantId, id);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
