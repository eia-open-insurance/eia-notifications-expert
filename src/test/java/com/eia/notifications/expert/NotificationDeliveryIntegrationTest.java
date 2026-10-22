package com.eia.notifications.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the delivery-provider handoff against the real (H2) schema: the
 * attempt written by send, the tenant-scoped attempt list and endpoint, the sent
 * event payload, provider configuration, and rows that predate the feature.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationDeliveryIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String APPROVER = "a-adjuster";

    @Autowired
    private NotificationService service;

    @Autowired
    private NotificationDeliveryAttemptRepository deliveryAttempts;

    @Autowired
    private DeliveryProvider deliveryProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void clean() {
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
    void simulatorIsTheDefaultConfiguredProvider() {
        assertThat(deliveryProvider).isInstanceOf(SimulatedDeliveryProvider.class);
        assertThat(deliveryProvider.provider()).isEqualTo("SIMULATOR");
    }

    @Test
    void sendRecordsTheFirstAttemptAndDeliveryEvidence() {
        Notification draft = service.create(request());

        Notification sent = service.send(TENANT, draft.id());

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.version()).isEqualTo(1);
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.deliveredAt()).isNotNull();
        assertThat(sent.providerReference()).isEqualTo("SIM-" + draft.id());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, sent_at, delivered_at, provider_reference
                  FROM notification
                 WHERE id = ?
                """, draft.id());
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("delivered_at")).isNotNull();
        assertThat(row.get("provider_reference")).isEqualTo("SIM-" + draft.id());

        List<NotificationDeliveryAttempt> attempts = service.attempts(TENANT, draft.id());
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.id()).isNotNull();
            assertThat(attempt.tenantId()).isEqualTo(TENANT);
            assertThat(attempt.notificationId()).isEqualTo(draft.id());
            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.channel()).isEqualTo("EMAIL");
            assertThat(attempt.provider()).isEqualTo("SIMULATOR");
            assertThat(attempt.providerReference()).isEqualTo("SIM-" + draft.id());
            assertThat(attempt.status()).isEqualTo("DELIVERED");
            assertThat(attempt.errorCode()).isNull();
            assertThat(attempt.errorMessage()).isNull();
            assertThat(attempt.attemptedAt()).isNotNull();
            assertThat(attempt.attemptedAt().toEpochMilli()).isEqualTo(sent.sentAt().toEpochMilli());
        });
    }

    @Test
    void sentEventCarriesProviderReferenceAndDeliveredAt() {
        Notification draft = service.create(request());

        Notification sent = service.send(TENANT, draft.id());

        assertThat(payload(draft.id(), NotificationEvents.SENT))
                .contains("\"providerReference\":\"SIM-" + draft.id() + "\"")
                .contains("\"deliveredAt\":\"" + sent.deliveredAt() + "\"");
    }

    @Test
    void attemptsEndpointListsOnlyTheRequestedTenantsAttempts() throws Exception {
        Notification draft = service.create(request());
        service.send(TENANT, draft.id());

        mvc.perform(get("/internal/v1/notifications/{id}/attempts", draft.id())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].notificationId").value(draft.id().toString()))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$[0].provider").value("SIMULATOR"))
                .andExpect(jsonPath("$[0].providerReference").value("SIM-" + draft.id()))
                .andExpect(jsonPath("$[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$[0].errorCode").doesNotExist())
                .andExpect(jsonPath("$[0].errorMessage").doesNotExist());

        mvc.perform(get("/internal/v1/notifications/{id}/attempts", draft.id())
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9001"));

        assertThat(deliveryAttempts.findAll(OTHER_TENANT, draft.id())).isEmpty();
    }

    @Test
    void attemptsForAnUnknownNotificationAreNotFound() {
        assertThatThrownBy(() -> service.attempts(TENANT, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void legacyNotificationWithoutDeliveryEvidenceReadsAndSends() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-15T10:00:00Z");
        jdbc.update("""
                INSERT INTO notification (id, tenant_id, notification_number, notification_type,
                                          channel, status, recipient_party_id, subject, body,
                                          template_code, template_version, scheduled_at, sent_at,
                                          version, created_at, updated_at)
                VALUES (?, ?, 'NTF-2026-000000001', 'NEW_CLAIM_NOTICE', 'EMAIL', 'CREATED', ?,
                        'Claim requires attention', 'Claim CLM-2026-000000001 requires attention.',
                        'CLAIM_NOTICE_STANDARD', 1, NULL, NULL, 0, ?, ?)
                """, id, TENANT, PARTY_ID, Timestamp.from(createdAt), Timestamp.from(createdAt));

        Notification legacy = service.get(TENANT, id);
        assertThat(legacy.status()).isEqualTo("CREATED");
        assertThat(legacy.sentAt()).isNull();
        assertThat(legacy.deliveredAt()).isNull();
        assertThat(legacy.providerReference()).isNull();

        Notification sent = service.send(TENANT, id);

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.deliveredAt()).isNotNull();
        assertThat(sent.providerReference()).isEqualTo("SIM-" + id);
        assertThat(service.attempts(TENANT, id)).singleElement()
                .satisfies(attempt -> assertThat(attempt.attemptNumber()).isEqualTo(1));
    }

    @Test
    void attemptNumberIsUniquePerNotification() {
        Notification notification = service.create(request());
        Instant now = Instant.now();
        deliveryAttempts.insert(new NotificationDeliveryAttempt(Uuid7.generate(), TENANT, notification.id(),
                1, "EMAIL", "SIMULATOR", "SIM-" + notification.id(), "DELIVERED", null, null, now));

        assertThatThrownBy(() -> deliveryAttempts.insert(new NotificationDeliveryAttempt(
                Uuid7.generate(), TENANT, notification.id(), 1, "EMAIL", "SIMULATOR",
                "SIM-" + notification.id(), "DELIVERED", null, null, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreateNotificationRequest request() {
        return new CreateNotificationRequest(TENANT, "NEW_CLAIM_NOTICE", "EMAIL", PARTY_ID,
                "Claim requires attention", "Claim CLM-2026-000000001 requires attention.",
                "CLAIM_NOTICE_STANDARD", 1, null, null, null, null);
    }

    private String payload(UUID notificationId, String eventType) {
        return jdbc.queryForObject("""
                SELECT payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, String.class, notificationId, eventType);
    }
}
