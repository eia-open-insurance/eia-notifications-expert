package com.eia.notifications.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID NOTIFICATION_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");
    private static final UUID SOURCE_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000003");
    private static final String APPROVER = "a-adjuster";

    @Mock
    private NotificationRepository notifications;

    @Mock
    private NotificationTemplateRepository templates;

    @Mock
    private NotificationDeliveryAttemptRepository deliveryAttempts;

    @Mock
    private DeliveryProvider deliveryProvider;

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
                .thenReturn(Optional.of(template("CLAIM_NOTICE_STANDARD", 1, "ACTIVE", APPROVER)));
        Notification persisted = notification("CREATED", "CLAIM_NOTICE_STANDARD", 1, Instant.now(), null, 0);
        when(notifications.create(request, "CLAIM_NOTICE_STANDARD", 1)).thenReturn(persisted);

        assertThat(service.create(request)).isEqualTo(persisted);
        verify(notifications).create(request, "CLAIM_NOTICE_STANDARD", 1);
    }

    @Test
    void createAcceptsDunningNoticeType() {
        CreateNotificationRequest request = request("IN_APP", "DUNNING_NOTICE", null, null);
        Notification persisted = notification("CREATED", null, null, null, null, 0);
        when(notifications.create(request, null, null)).thenReturn(persisted);

        assertThat(service.create(request)).isEqualTo(persisted);
        verify(notifications).create(request, null, null);
        verify(outbox).insert(any());
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

        verify(notifications, never()).markSent(any(), any(), any(), any());
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
        String providerReference = "SIM-" + NOTIFICATION_ID;
        Instant deliveredAt = Instant.now();
        Notification sent = notification("SENT", "CLAIM_NOTICE_STANDARD", 1,
                draft.scheduledAt(), deliveredAt, 1, providerReference, deliveredAt);
        when(notifications.findById(TENANT, NOTIFICATION_ID)).thenReturn(Optional.of(draft));
        when(templates.find(TENANT, "CLAIM_NOTICE_STANDARD", 1, "EMAIL"))
                .thenReturn(Optional.of(template("CLAIM_NOTICE_STANDARD", 1, "ACTIVE", APPROVER)));
        when(deliveryProvider.provider()).thenReturn(SimulatedDeliveryProvider.PROVIDER);
        when(deliveryProvider.deliver(any()))
                .thenReturn(DeliveryResult.delivered(providerReference));
        when(notifications.markSent(eq(draft), any(), any(), eq(providerReference)))
                .thenReturn(Optional.of(sent));

        assertThat(service.send(TENANT, NOTIFICATION_ID)).isEqualTo(sent);

        verify(deliveryProvider).deliver(any());
        verify(notifications).markSent(eq(draft), any(), any(), eq(providerReference));

        ArgumentCaptor<NotificationDeliveryAttempt> attempt =
                ArgumentCaptor.forClass(NotificationDeliveryAttempt.class);
        verify(deliveryAttempts).insert(attempt.capture());
        assertThat(attempt.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(attempt.getValue().notificationId()).isEqualTo(NOTIFICATION_ID);
        assertThat(attempt.getValue().attemptNumber()).isEqualTo(1);
        assertThat(attempt.getValue().channel()).isEqualTo("EMAIL");
        assertThat(attempt.getValue().provider()).isEqualTo(SimulatedDeliveryProvider.PROVIDER);
        assertThat(attempt.getValue().providerReference()).isEqualTo(providerReference);
        assertThat(attempt.getValue().status()).isEqualTo(DeliveryResult.DELIVERED);
        assertThat(attempt.getValue().errorCode()).isNull();
        assertThat(attempt.getValue().errorMessage()).isNull();
        assertThat(attempt.getValue().attemptedAt()).isEqualTo(sent.sentAt());
        verify(outbox).insert(any());
    }

    @Test
    void sendRejectsProviderFailureWithoutMarkingTheNotificationSent() {
        Notification draft = notification("CREATED", "CLAIM_NOTICE_STANDARD", 1,
                Instant.now().minusSeconds(60), null, 0);
        when(notifications.findById(TENANT, NOTIFICATION_ID)).thenReturn(Optional.of(draft));
        when(templates.find(TENANT, "CLAIM_NOTICE_STANDARD", 1, "EMAIL"))
                .thenReturn(Optional.of(template("CLAIM_NOTICE_STANDARD", 1, "ACTIVE", APPROVER)));
        when(deliveryProvider.provider()).thenReturn("BROKEN");
        when(deliveryProvider.deliver(any()))
                .thenReturn(DeliveryResult.failed("PROVIDER_DOWN", "relay unavailable"));

        assertThatThrownBy(() -> service.send(TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.DELIVERY_FAILED);
                    assertThat(ex.errorCode().status().value()).isEqualTo(502);
                });

        verify(notifications, never()).markSent(any(), any(), any(), any());
        verify(deliveryAttempts, never()).insert(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void attemptsAreScopedToTheTenantThatOwnsTheNotification() {
        Notification notification = notification("CREATED", null, null, null, null, 0);
        when(notifications.findById(TENANT, NOTIFICATION_ID)).thenReturn(Optional.of(notification));
        when(deliveryAttempts.findAll(TENANT, NOTIFICATION_ID)).thenReturn(List.of());

        assertThat(service.attempts(TENANT, NOTIFICATION_ID)).isEmpty();
        verify(deliveryAttempts).findAll(TENANT, NOTIFICATION_ID);

        when(notifications.findById(OTHER_TENANT, NOTIFICATION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attempts(OTHER_TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(deliveryAttempts, never()).findAll(OTHER_TENANT, NOTIFICATION_ID);
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

    @Test
    void listFiltersByNormalizedSourcePair() {
        Notification linked = notification("CREATED", null, null, null, null, 0,
                "DIRECT_CLAIM", SOURCE_ID, "CLM-2026-0000001");
        when(notifications.findAllBySource(TENANT, "DIRECT_CLAIM", SOURCE_ID))
                .thenReturn(List.of(linked));

        assertThat(service.list(TENANT, " direct_claim ", SOURCE_ID)).containsExactly(linked);

        verify(notifications).findAllBySource(TENANT, "DIRECT_CLAIM", SOURCE_ID);
        verify(notifications, never()).findAll(any());
    }

    @Test
    void listWithoutSourceFilterUsesUnfilteredQuery() {
        when(notifications.findAll(TENANT)).thenReturn(List.of());

        assertThat(service.list(TENANT, null, null)).isEmpty();

        verify(notifications).findAll(TENANT);
        verify(notifications, never()).findAllBySource(any(), any(), any());
    }

    @Test
    void listRejectsPartialSourceFilter() {
        assertThatThrownBy(() -> service.list(TENANT, "POLICY", null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_FILTER);
                    assertThat(ex.errorCode().status().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("sourceId is required");
                });

        assertThatThrownBy(() -> service.list(TENANT, null, SOURCE_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_FILTER);
                    assertThat(ex.errorCode().status().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("sourceType is required");
                });

        assertThatThrownBy(() -> service.list(TENANT, "  ", SOURCE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_FILTER));

        verify(notifications, never()).findAll(any());
        verify(notifications, never()).findAllBySource(any(), any(), any());
    }

    @Test
    void listRejectsUnknownSourceTypeFilter() {
        assertThatThrownBy(() -> service.list(TENANT, "BRIBE", SOURCE_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_TYPE);
                    assertThat(ex.errorCode().status().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("not one of",
                            "CESSION", "RETROCESSION", "DOCUMENT", "PARTY", "OTHER");
                });

        verify(notifications, never()).findAll(any());
        verify(notifications, never()).findAllBySource(any(), any(), any());
    }

    @Test
    void createNormalizesAndPersistsSourceLinkage() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", null, null,
                " direct_claim ", SOURCE_ID, "  CLM-2026-0000001  ");
        Notification persisted = notification("CREATED", null, null, null, null, 0,
                "DIRECT_CLAIM", SOURCE_ID, "CLM-2026-0000001");
        when(notifications.create(any(), any(), any())).thenReturn(persisted);

        assertThat(service.create(request)).isEqualTo(persisted);

        ArgumentCaptor<CreateNotificationRequest> captor =
                ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notifications).create(captor.capture(), any(), any());
        assertThat(captor.getValue().sourceType()).isEqualTo("DIRECT_CLAIM");
        assertThat(captor.getValue().sourceId()).isEqualTo(SOURCE_ID);
        assertThat(captor.getValue().sourceReference()).isEqualTo("CLM-2026-0000001");
        verify(outbox).insert(any());
    }

    @Test
    void createWithoutSourceLinkagePersistsNoSource() {
        CreateNotificationRequest request = request("IN_APP", "MENTION", null, null);
        when(notifications.create(any(), any(), any()))
                .thenReturn(notification("CREATED", null, null, null, null, 0));

        service.create(request);

        ArgumentCaptor<CreateNotificationRequest> captor =
                ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notifications).create(captor.capture(), any(), any());
        assertThat(captor.getValue().sourceType()).isNull();
        assertThat(captor.getValue().sourceId()).isNull();
        assertThat(captor.getValue().sourceReference()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECT_CLAIM", "ASSUMED_CLAIM", "POLICY", "CONTRACT", "PAYMENT",
            "CESSION", "RETROCESSION", "DOCUMENT", "PARTY", "OTHER"})
    void createAcceptsEverySupportedSourceTypeWithoutReference(String sourceType) {
        CreateNotificationRequest request = request("IN_APP", "MENTION", null, null,
                sourceType, SOURCE_ID, null);
        when(notifications.create(any(), any(), any()))
                .thenReturn(notification("CREATED", null, null, null, null, 0,
                        sourceType, SOURCE_ID, null));

        service.create(request);

        ArgumentCaptor<CreateNotificationRequest> captor =
                ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notifications).create(captor.capture(), any(), any());
        assertThat(captor.getValue().sourceType()).isEqualTo(sourceType);
        assertThat(captor.getValue().sourceId()).isEqualTo(SOURCE_ID);
        assertThat(captor.getValue().sourceReference()).isNull();
    }

    @Test
    void createRejectsSourceTypeWithoutSourceId() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", null, null,
                "POLICY", null, "POL-2026-0000001");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_LINKAGE);
                    assertThat(ex.getMessage()).contains("sourceId is required");
                });

        verify(notifications, never()).create(any(), any(), any());
    }

    @Test
    void createRejectsSourceIdWithoutSourceType() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", null, null,
                null, SOURCE_ID, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_LINKAGE);
                    assertThat(ex.getMessage()).contains("sourceType is required");
                });

        verify(notifications, never()).create(any(), any(), any());
    }

    @Test
    void createRejectsUnknownSourceType() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", null, null,
                "BRIBE", SOURCE_ID, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_TYPE));

        verify(notifications, never()).create(any(), any(), any());
    }

    @Test
    void createRejectsOverlongSourceReference() {
        CreateNotificationRequest request = request("EMAIL", "NEW_CLAIM_NOTICE", null, null,
                "DIRECT_CLAIM", SOURCE_ID, "x".repeat(65));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_SOURCE_REFERENCE));

        verify(notifications, never()).create(any(), any(), any());
    }

    @Test
    void getScopesNotificationToTheRequestedTenant() {
        when(notifications.findById(OTHER_TENANT, NOTIFICATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OTHER_TENANT, NOTIFICATION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(notifications, never()).findById(TENANT, NOTIFICATION_ID);
    }

    private CreateNotificationRequest request(String channel, String type, String templateCode,
                                               Integer templateVersion) {
        return request(channel, type, templateCode, templateVersion, null, null, null);
    }

    private CreateNotificationRequest request(String channel, String type, String templateCode,
                                               Integer templateVersion, String sourceType, UUID sourceId,
                                               String sourceReference) {
        return new CreateNotificationRequest(TENANT, type, channel, PARTY_ID,
                "Claim requires attention", "Claim CLM-2026-000000001 requires attention.",
                templateCode, templateVersion, null, sourceType, sourceId, sourceReference);
    }

    private NotificationTemplate template(String code, int version, String status, String approvedBy) {
        return new NotificationTemplate(UUID.randomUUID(), TENANT, code, version, null, "en-US",
                status, approvedBy, approvedBy == null ? null : Instant.now());
    }

    private Notification notification(String status, String templateCode, Integer templateVersion,
                                      Instant scheduledAt, Instant sentAt, int version) {
        return notification(status, templateCode, templateVersion, scheduledAt, sentAt, version,
                null, null, null);
    }

    private Notification notification(String status, String templateCode, Integer templateVersion,
                                      Instant scheduledAt, Instant sentAt, int version,
                                      String providerReference, Instant deliveredAt) {
        return notification(status, templateCode, templateVersion, scheduledAt, sentAt, version,
                null, null, null, providerReference, deliveredAt);
    }

    private Notification notification(String status, String templateCode, Integer templateVersion,
                                      Instant scheduledAt, Instant sentAt, int version,
                                      String sourceType, UUID sourceId, String sourceReference) {
        return notification(status, templateCode, templateVersion, scheduledAt, sentAt, version,
                sourceType, sourceId, sourceReference, null, null);
    }

    private Notification notification(String status, String templateCode, Integer templateVersion,
                                      Instant scheduledAt, Instant sentAt, int version,
                                      String sourceType, UUID sourceId, String sourceReference,
                                      String providerReference, Instant deliveredAt) {
        return new Notification(NOTIFICATION_ID, TENANT, "NTF-2026-000000001", "NEW_CLAIM_NOTICE",
                "EMAIL", status, PARTY_ID, "Claim requires attention",
                "Claim CLM-2026-000000001 requires attention.", templateCode, templateVersion,
                scheduledAt, sentAt, deliveredAt, providerReference, version, Instant.now(), Instant.now(),
                sourceType, sourceId, sourceReference);
    }
}
