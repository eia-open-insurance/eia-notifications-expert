package com.eia.notifications.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the approved template registry. Approval is data, not a hard-coded
 * allowlist: a template may be approved or retired after a notification is
 * drafted, and the send path re-checks it.
 */
@Repository
public class NotificationTemplateRepository {

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
            rs.getString("status"),
            rs.getObject("approved_by", UUID.class),
            rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant()
    );

    public Optional<NotificationTemplate> find(UUID tenantId, String templateCode, int templateVersion,
                                               String channel) {
        return jdbc.query("""
                SELECT id, tenant_id, template_code, template_version, channel, locale, status,
                       approved_by, approved_at
                  FROM notification_template
                 WHERE tenant_id = ? AND template_code = ? AND template_version = ?
                   AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
                   AND (effective_to IS NULL OR effective_to > CURRENT_DATE)
                 ORDER BY CASE WHEN channel = ? THEN 0 WHEN channel IS NULL THEN 1 ELSE 2 END, locale
                 LIMIT 1
                """, MAPPER, tenantId, templateCode, templateVersion, channel).stream().findFirst();
    }
}
