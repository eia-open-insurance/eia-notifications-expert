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
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String APPROVER = "a-adjuster";

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
        jdbc.update("DELETE FROM notification_delivery_attempt");
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
                null, null, null, null, null, null));

        assertThat(notification.notificationType()).isEqualTo("DUNNING_NOTICE");
        assertThat(jdbc.queryForObject(
                "SELECT notification_type FROM notification WHERE id = ?", String.class, notification.id()))
                .isEqualTo("DUNNING_NOTICE");
    }

    @Test
    void createPersistsSourceLinkageAndEmitsItInTheCreatedEvent() {
        UUID sourceId = UUID.fromString("0192f3a4-0000-7000-8000-000000000003");

        Notification notification = service.create(request("DIRECT_CLAIM", sourceId, "CLM-2026-0000001"));

        assertThat(notification.sourceType()).isEqualTo("DIRECT_CLAIM");
        assertThat(notification.sourceId()).isEqualTo(sourceId);
        assertThat(notification.sourceReference()).isEqualTo("CLM-2026-0000001");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source_type, source_id, source_reference
                  FROM notification
                 WHERE id = ?
                """, notification.id());
        assertThat(row.get("source_type")).isEqualTo("DIRECT_CLAIM");
        assertThat(row.get("source_id")).isEqualTo(sourceId);
        assertThat(row.get("source_reference")).isEqualTo("CLM-2026-0000001");

        assertThat(payload(notification.id(), NotificationEvents.CREATED))
                .contains("\"sourceType\":\"DIRECT_CLAIM\"")
                .contains("\"sourceId\":\"" + sourceId + "\"")
                .contains("\"sourceReference\":\"CLM-2026-0000001\"");
    }

    @Test
    void createWithoutSourceLinkageLeavesColumnsNullAndOmitsEventFields() {
        Notification notification = service.create(request());

        assertThat(notification.sourceType()).isNull();
        assertThat(notification.sourceId()).isNull();
        assertThat(notification.sourceReference()).isNull();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source_type, source_id, source_reference
                  FROM notification
                 WHERE id = ?
                """, notification.id());
        assertThat(row.get("source_type")).isNull();
        assertThat(row.get("source_id")).isNull();
        assertThat(row.get("source_reference")).isNull();

        assertThat(payload(notification.id(), NotificationEvents.CREATED))
                .doesNotContain("\"sourceType\"")
                .doesNotContain("\"sourceId\"")
                .doesNotContain("\"sourceReference\"");
    }

    @Test
    void createRejectsUnknownSourceTypeWithoutWriting() {
        assertThatThrownBy(() -> service.create(request("BRIBE", UUID.randomUUID(), "CLM-2026-0000001")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_TYPE));

        assertThat(count("notification")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void sentEventCarriesSourceLinkage() {
        UUID sourceId = UUID.fromString("0192f3a4-0000-7000-8000-000000000004");
        Notification notification = service.create(request("PAYMENT", sourceId, "PAY-2026-0000001"));

        service.send(TENANT, notification.id());

        assertThat(payload(notification.id(), NotificationEvents.SENT))
                .contains("\"sourceType\":\"PAYMENT\"")
                .contains("\"sourceId\":\"" + sourceId + "\"")
                .contains("\"sourceReference\":\"PAY-2026-0000001\"");
    }

    @Test
    void notificationsAreScopedToTheirTenant() {
        UUID sourceId = UUID.fromString("0192f3a4-0000-7000-8000-000000000005");
        Notification notification = service.create(request("POLICY", sourceId, "POL-2026-0000001"));

        assertThat(service.list(OTHER_TENANT)).isEmpty();
        assertThatThrownBy(() -> service.get(OTHER_TENANT, notification.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(service.get(TENANT, notification.id()).sourceId()).isEqualTo(sourceId);
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
        assertThat(count("notification_delivery_attempt")).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'notifications.notification.sent'
                """, Integer.class, notification.id())).isZero();
    }

    private CreateNotificationRequest request() {
        return request(null, null, null);
    }

    private CreateNotificationRequest request(String sourceType, UUID sourceId, String sourceReference) {
        return new CreateNotificationRequest(TENANT, "NEW_CLAIM_NOTICE", "EMAIL", PARTY_ID,
                "Claim requires attention", "Claim CLM-2026-000000001 requires attention.",
                "CLAIM_NOTICE_STANDARD", 1, null,
                sourceType, sourceId, sourceReference);
    }

    private String payload(UUID notificationId, String eventType) {
        return jdbc.queryForObject("""
                SELECT payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, String.class, notificationId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
