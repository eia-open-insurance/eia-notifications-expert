package com.eia.notifications.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID NOTIFICATION_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");

    @Mock
    private NotificationRepository notifications;

    @Mock
    private NotificationTemplateRepository templates;

    @Mock
    private OutboxRepository outbox;

    @InjectMocks
    private NotificationService service;

    @Test
    void createRejectsChannelOutsideSpec() {
        CreateNotificationRequest request = request("FAX", "NEW_CLAIM_NOTICE", null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CHANNEL));

        verify(notifications, never()).create(any(), any(), any());
    }

    @Test
    void createRejectsTypeOutsideSpec() {
        CreateNotificationRequest request = request("EMAIL", "NOT_A_TYPE", null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_NOTIFICATION_TYPE));
    }

    @Test
    void createRejectsUnknownTemplate() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", "MISSING", null);
        when(templates.find(TENANT, "MISSING", 1, "EMAIL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    void createRejectsDraftTemplate() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", "CLAIM_NOTICE_DRAFT", 1);
        when(templates.find(TENANT, "CLAIM_NOTICE_DRAFT", 1, "EMAIL"))
                .thenReturn(Optional.of(template("CLAIM_NOTICE_DRAFT", 1, "DRAFT", null)));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_NOT_APPROVED));
    }

    @Test
    void createPersistsApprovedTemplateAndDefaultsVersionToOne() {
        CreateNotificationRequest request = request("EMAIL", "PAYMENT_AUTHORITY_REQUEST",
                "CLAIM_NOTICE_STANDARD", null);
        when(templates.find(TENANT, "CLAIM_NOTICE_STANDARD", 1, "EMAIL"))
                .thenReturn(Optional.of(template("CLAIM_NOTICE_STANDARD", 1, "ACTIVE", UUID.randomUUID())));
        Notification persisted = notification("CREATED", "CLAIM_NOTICE_STANDARD", 1, Instant.now(), null, 0);
        when(notifications.create(request, "CLAIM_NOTICE_STANDARD", 1)).thenReturn(persisted);

        assertThat(service.create(request)).isEqualTo(persisted);
        verify(notifications).create(request, "CLAIM_NOTICE_STANDARD", 1);
    }

    @Test
    void createAllowsDraftWithoutTemplate() {
        CreateNotificationRequest request = request("IN_APP", "MENTION", null, null);
        Notification persisted = notification("CREATED", null, null, null, null, 0);
        when(notifications.create(request, null, null)).thenReturn(persisted);

        assertThat(service.create(request)).isEqualTo(persisted);
    }

    @Test
    void createRejectsTemplateVersionWithoutTemplateCode() {
        CreateNotificationRequest request = request("EMAIL", "MENTION", null, 2);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_REQUIRED));
    }

    @Test
    void sendRejectsMissingTemplate() {
        when(notifications.findById(TENANT, NOTIFICATION_ID))
                .thenReturn(Optional.of(notification("CREATED", null, null, null, null, 0)));

        assertThatThrownBy(() -> service.send(TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_REQUIRED));
    }

    @Test
    void sendRejectsFutureScheduledInstant() {
        when(notifications.findById(TENANT, NOTIFICATION_ID))
                .thenReturn(Optional.of(notification("CREATED", "CLAIM_NOTICE_STANDARD", 1,
                        Instant.now().plusSeconds(3600), null, 0)));

        assertThatThrownBy(() -> service.send(TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SEND_BEFORE_SCHEDULED));

        verify(notifications, never()).markSent(any(), any());
    }

    @Test
    void sendRejectsUnapprovedTemplate() {
        when(notifications.findById(TENANT, NOTIFICATION_ID))
                .thenReturn(Optional.of(notification("CREATED", "CLAIM_NOTICE_DRAFT", 1, null, null, 0)));
        when(templates.find(TENANT, "CLAIM_NOTICE_DRAFT", 1, "EMAIL"))
                .thenReturn(Optional.of(template("CLAIM_NOTICE_DRAFT", 1, "DRAFT", null)));

        assertThatThrownBy(() -> service.send(TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_NOT_APPROVED));
    }

    @Test
    void sendRejectsTerminalStatus() {
        when(notifications.findById(TENANT, NOTIFICATION_ID))
                .thenReturn(Optional.of(notification("SENT", "CLAIM_NOTICE_STANDARD", 1, null, Instant.now(), 1)));

        assertThatThrownBy(() -> service.send(TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    @Test
    void sendMarksNotificationSentWhenInvariantsHold() {
        Notification draft = notification("CREATED", "CLAIM_NOTICE_STANDARD", 1,
                Instant.now().minusSeconds(60), null, 0);
        Notification sent = notification("SENT", "CLAIM_NOTICE_STANDARD", 1,
                draft.scheduledAt(), Instant.now(), 1);
        when(notifications.findById(TENANT, NOTIFICATION_ID)).thenReturn(Optional.of(draft));
        when(templates.find(TENANT, "CLAIM_NOTICE_STANDARD", 1, "EMAIL"))
                .thenReturn(Optional.of(template("CLAIM_NOTICE_STANDARD", 1, "ACTIVE", UUID.randomUUID())));
        when(notifications.markSent(eq(draft), any())).thenReturn(Optional.of(sent));

        assertThat(service.send(TENANT, NOTIFICATION_ID)).isEqualTo(sent);
        verify(notifications).markSent(eq(draft), any());
    }

    @Test
    void getReturnsNotFound() {
        when(notifications.findById(TENANT, NOTIFICATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.errorCode().status().value()).isEqualTo(404);
                });
    }

    @Test
    void listDelegatesToRepository() {
        when(notifications.findAll(TENANT)).thenReturn(List.of());

        assertThat(service.list(TENANT)).isEmpty();
    }

    private CreateNotificationRequest request(String channel, String type, String templateCode,
                                               Integer templateVersion) {
        return new CreateNotificationRequest(TENANT, type, channel, PARTY_ID,
                "Claim requires attention", "Claim CLM-2026-000000001 requires attention.",
                templateCode, templateVersion, null);
    }

    private NotificationTemplate template(String code, int version, String status, UUID approvedBy) {
        return new NotificationTemplate(UUID.randomUUID(), TENANT, code, version, null, "en-US",
                status, approvedBy, approvedBy == null ? null : Instant.now());
    }

    private Notification notification(String status, String templateCode, Integer templateVersion,
                                      Instant scheduledAt, Instant sentAt, int version) {
        return new Notification(NOTIFICATION_ID, TENANT, "NTF-2026-000000001", "NEW_CLAIM_NOTICE",
                "EMAIL", status, PARTY_ID, "Claim requires attention",
                "Claim CLM-2026-000000001 requires attention.", templateCode, templateVersion,
                scheduledAt, sentAt, version, Instant.now(), Instant.now());
    }
}
