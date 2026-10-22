package com.eia.notifications.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class NotificationOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID APPROVER = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Autowired
    private NotificationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_template");
        jdbc.update("""
                INSERT INTO notification_template (
                    id, tenant_id, template_code, template_version, channel, locale, body_template,
                    status, approved_by, approved_at, effective_from, effective_to, created_at, updated_at
                ) VALUES (?, ?, 'CLAIM_NOTICE_STANDARD', 1, NULL, 'en-US', ?, 'ACTIVE', ?,
                          ?, NULL, NULL, ?, ?)
                """, TEMPLATE_ID, TENANT, "Claim {{claimNumber}} requires attention.", APPROVER,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
    }

    @Test
    void createWritesTheNotificationAndCreatedEventTogether() {
        Notification notification = service.create(request());

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, status, tenant_id,
                       payload, headers, partition_key, occurred_at, recorded_at, next_attempt_at
                  FROM outbox_event
                 WHERE aggregate_id = ?
                """, notification.id());

        assertThat(event.get("event_type")).isEqualTo("notifications.notification.created");
        assertThat(event.get("aggregate_type")).isEqualTo("Notification");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(notification.id().toString());
        assertThat(event.get("occurred_at")).isNotNull();
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(event.get("next_attempt_at")).isNotNull();

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"notificationId\":\"" + notification.id() + "\"")
                .contains("\"notificationNumber\":\"" + notification.notificationNumber() + "\"")
                .contains("\"notificationType\":\"NEW_CLAIM_NOTICE\"")
                .contains("\"channel\":\"EMAIL\"")
                .contains("\"status\":\"CREATED\"")
                .contains("\"recipientPartyId\":\"" + PARTY_ID + "\"")
                .contains("\"templateCode\":\"CLAIM_NOTICE_STANDARD\"")
                .contains("\"templateVersion\":1");
        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"notifications\"")
                .contains("\"businessKey\":\"" + notification.notificationNumber() + "\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createPersistsDunningNoticeType() {
        Notification notification = service.create(new CreateNotificationRequest(TENANT, "DUNNING_NOTICE",
                "EMAIL", PARTY_ID, "Payment is overdue", "Invoice INV-2026-000000001 is overdue.",
                null, null, null));

        assertThat(notification.notificationType()).isEqualTo("DUNNING_NOTICE");
        assertThat(jdbc.queryForObject(
                "SELECT notification_type FROM notification WHERE id = ?", String.class, notification.id()))
                .isEqualTo("DUNNING_NOTICE");
    }

    @Test
    void createRollbackLeavesNeitherTheNotificationNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(request());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("notification")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void sendWritesTheSentEventInTheSameTransaction() {
        Notification notification = service.create(request());

        Notification sent = service.send(TENANT, notification.id());

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.version()).isEqualTo(1);
        assertThat(sent.sentAt()).isNotNull();

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, notification.id());

        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("event_type")).isEqualTo("notifications.notification.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("notifications.notification.sent");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);

        String payload = (String) events.get(1).get("payload");
        assertThat(payload)
                .contains("\"notificationNumber\":\"" + notification.notificationNumber() + "\"")
                .contains("\"status\":\"SENT\"")
                .contains("\"from\":\"CREATED\"")
                .contains("\"to\":\"SENT\"")
                .contains("\"sentAt\":");
    }

    @Test
    void sendRollbackKeepsTheDraftAndWritesNoSentEvent() {
        Notification notification = service.create(request());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.send(TENANT, notification.id());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id = ?", String.class, notification.id()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM notification WHERE id = ?", Integer.class, notification.id()))
                .isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'notifications.notification.sent'
                """, Integer.class, notification.id())).isZero();
    }

    private CreateNotificationRequest request() {
        return new CreateNotificationRequest(TENANT, "NEW_CLAIM_NOTICE", "EMAIL", PARTY_ID,
                "Claim requires attention", "Claim CLM-2026-000000001 requires attention.",
                "CLAIM_NOTICE_STANDARD", 1, null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
