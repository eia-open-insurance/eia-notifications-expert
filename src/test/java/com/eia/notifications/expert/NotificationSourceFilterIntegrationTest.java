package com.eia.notifications.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the source-filter SQL against the real (H2) notification schema:
 * exact pair matching, the tenant wall, newest-first ordering, and the
 * unchanged unfiltered list.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationSourceFilterIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID RECIPIENT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SOURCE_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID OTHER_SOURCE_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final Instant BASE = Instant.parse("2026-10-22T12:00:00Z");

    @Autowired
    private NotificationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM notification");
    }

    @Test
    void filtersByBothSourceFieldsNewestFirst() {
        UUID oldest = insert(TENANT, "NTF-2026-0000001", "DIRECT_CLAIM", SOURCE_ID, BASE);
        insert(TENANT, "NTF-2026-0000002", "DIRECT_CLAIM", OTHER_SOURCE_ID, BASE.plusSeconds(10));
        insert(TENANT, "NTF-2026-0000003", "POLICY", SOURCE_ID, BASE.plusSeconds(20));
        UUID newest = insert(TENANT, "NTF-2026-0000004", "DIRECT_CLAIM", SOURCE_ID, BASE.plusSeconds(30));

        List<Notification> notifications = service.list(TENANT, "direct_claim", SOURCE_ID);

        assertThat(notifications).extracting(Notification::id).containsExactly(newest, oldest);
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.sourceType()).isEqualTo("DIRECT_CLAIM");
            assertThat(notification.sourceId()).isEqualTo(SOURCE_ID);
        });
    }

    @Test
    void keepsTheTenantWallForSourceFilters() {
        UUID mine = insert(TENANT, "NTF-2026-0000001", "DIRECT_CLAIM", SOURCE_ID, BASE);
        UUID foreign = insert(OTHER_TENANT, "NTF-2026-0000002", "DIRECT_CLAIM", SOURCE_ID,
                BASE.plusSeconds(10));

        assertThat(service.list(TENANT, "DIRECT_CLAIM", SOURCE_ID))
                .extracting(Notification::id).containsExactly(mine);
        assertThat(service.list(OTHER_TENANT, "DIRECT_CLAIM", SOURCE_ID))
                .extracting(Notification::id).containsExactly(foreign);
    }

    @Test
    void keepsTheUnfilteredListWhenNoSourceFilterIsSupplied() {
        UUID unlinked = insert(TENANT, "NTF-2026-0000001", null, null, BASE);
        UUID linked = insert(TENANT, "NTF-2026-0000002", "DIRECT_CLAIM", SOURCE_ID,
                BASE.plusSeconds(10));
        insert(OTHER_TENANT, "NTF-2026-0000003", "DIRECT_CLAIM", SOURCE_ID, BASE.plusSeconds(20));

        assertThat(service.list(TENANT, null, null))
                .extracting(Notification::id).containsExactly(linked, unlinked);
        assertThat(service.list(TENANT))
                .extracting(Notification::id).containsExactly(linked, unlinked);
    }

    @Test
    void rejectsInvalidTypeAndPartialPairs() {
        ApiException invalidType = assertThrows(ApiException.class,
                () -> service.list(TENANT, "BRIBE", SOURCE_ID));
        assertEquals(ErrorCode.INVALID_SOURCE_TYPE, invalidType.errorCode());
        assertEquals(400, invalidType.errorCode().status().value());

        ApiException missingSourceId = assertThrows(ApiException.class,
                () -> service.list(TENANT, "DIRECT_CLAIM", null));
        assertEquals(ErrorCode.INVALID_SOURCE_FILTER, missingSourceId.errorCode());
        assertEquals(400, missingSourceId.errorCode().status().value());

        ApiException missingSourceType = assertThrows(ApiException.class,
                () -> service.list(TENANT, null, SOURCE_ID));
        assertEquals(ErrorCode.INVALID_SOURCE_FILTER, missingSourceType.errorCode());
        assertEquals(400, missingSourceType.errorCode().status().value());
    }

    private UUID insert(UUID tenantId, String notificationNumber, String sourceType, UUID sourceId,
                        Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification (id, tenant_id, notification_number, notification_type,
                                          channel, status, recipient_party_id, subject, body,
                                          template_code, template_version, scheduled_at, sent_at,
                                          version, created_at, updated_at, source_type, source_id,
                                          source_reference)
                VALUES (?, ?, ?, 'MENTION', 'IN_APP', 'CREATED', ?, 'Notification subject',
                        'Notification body', NULL, NULL, NULL, NULL, 0, ?, ?, ?, ?, ?)
                """, id, tenantId, notificationNumber, RECIPIENT,
                Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(1)),
                sourceType, sourceId, sourceId == null ? null : "SRC-" + notificationNumber);
        return id;
    }
}
