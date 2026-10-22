package com.eia.notifications.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID TEMPLATE_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000010");
    private static final UUID APPROVER = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Mock
    private NotificationTemplateRepository templates;

    @Mock
    private OutboxRepository outbox;

    @InjectMocks
    private NotificationTemplateService service;

    @Test
    void listValidatesStatusAndDelegatesFilters() {
        when(templates.findAll(TENANT, "ACTIVE", "CLAIM_NOTICE"))
                .thenReturn(List.of(template("ACTIVE", APPROVER)));

        List<NotificationTemplateDto> result = service.list(TENANT, "ACTIVE", " CLAIM_NOTICE ");

        assertThat(result).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.code()).isEqualTo("CLAIM_NOTICE");
                    assertThat(dto.status()).isEqualTo("ACTIVE");
                });
        verify(templates).findAll(TENANT, "ACTIVE", "CLAIM_NOTICE");
    }

    @Test
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list(TENANT, "PUBLISHED", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_TEMPLATE_STATUS));
        verify(templates, never()).findAll(any(), any(), any());
    }

    @Test
    void listRequiresTenant() {
        assertThatThrownBy(() -> service.list(null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TENANT_REQUIRED));
    }

    @Test
    void createPersistsDraftAndCreatedEvent() {
        CreateNotificationTemplateRequest request = request(" EMAIL ", "en-US", 2);
        when(templates.exists(TENANT, "CLAIM_NOTICE", 2, "en-US")).thenReturn(false);
        when(templates.create(TENANT, "CLAIM_NOTICE", 2, "EMAIL", "en-US", "Claim {{claimNumber}} requires attention."))
                .thenReturn(template("DRAFT", null));

        NotificationTemplateDto created = service.create(TENANT, request);

        assertThat(created.status()).isEqualTo("DRAFT");
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("notifications.template.created");
        assertThat(event.getValue().payload())
                .contains("\"templateId\":\"" + TEMPLATE_ID + "\"")
                .contains("\"code\":\"CLAIM_NOTICE\"")
                .contains("\"version\":2")
                .contains("\"locale\":\"en-US\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"approvedBy\":null");
    }

    @Test
    void createAcceptsMissingChannelAsNull() {
        CreateNotificationTemplateRequest request =
                new CreateNotificationTemplateRequest(null, "CLAIM_NOTICE", 1, "  ", "en-US", "body");
        when(templates.exists(TENANT, "CLAIM_NOTICE", 1, "en-US")).thenReturn(false);
        when(templates.create(TENANT, "CLAIM_NOTICE", 1, null, "en-US", "body"))
                .thenReturn(template("DRAFT", null));

        service.create(TENANT, request);

        verify(templates).create(TENANT, "CLAIM_NOTICE", 1, null, "en-US", "body");
    }

    @Test
    void createRejectsChannelOutsideSpec() {
        CreateNotificationTemplateRequest request = request("FAX", "en-US", 2);

        assertThatThrownBy(() -> service.create(TENANT, request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CHANNEL));
        verify(templates, never()).create(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void createRejectsNonPositiveVersion() {
        CreateNotificationTemplateRequest request =
                new CreateNotificationTemplateRequest(null, "CLAIM_NOTICE", 0, "EMAIL", "en-US", "body");

        assertThatThrownBy(() -> service.create(TENANT, request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_TEMPLATE_VERSION));
    }

    @Test
    void createRejectsBlankBody() {
        CreateNotificationTemplateRequest request =
                new CreateNotificationTemplateRequest(null, "CLAIM_NOTICE", 1, "EMAIL", "en-US", "   ");

        assertThatThrownBy(() -> service.create(TENANT, request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.errors()).singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("bodyTemplate"));
                });
    }

    @Test
    void createRejectsDuplicateBeforeWriting() {
        CreateNotificationTemplateRequest request = request("EMAIL", "en-US", 2);
        when(templates.exists(TENANT, "CLAIM_NOTICE", 2, "en-US")).thenReturn(true);

        assertThatThrownBy(() -> service.create(TENANT, request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_ALREADY_EXISTS);
                    assertThat(ex.errorCode().status().value()).isEqualTo(409);
                });
        verify(templates, never()).create(any(), any(), anyInt(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void createMapsUniqueViolationRaceToConflict() {
        CreateNotificationTemplateRequest request = request("EMAIL", "en-US", 2);
        when(templates.exists(TENANT, "CLAIM_NOTICE", 2, "en-US")).thenReturn(false);
        when(templates.create(TENANT, "CLAIM_NOTICE", 2, "EMAIL", "en-US",
                "Claim {{claimNumber}} requires attention."))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create(TENANT, request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_ALREADY_EXISTS));
        verify(outbox, never()).insert(any());
    }

    @Test
    void approveDraftWritesApprovalEvidenceAndEvent() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.of(template("DRAFT", null)));
        when(templates.markApproved(eq(TENANT), eq(TEMPLATE_ID), eq(APPROVER), any()))
                .thenReturn(Optional.of(template("ACTIVE", APPROVER)));

        NotificationTemplateDto approved = service.approve(TENANT, TEMPLATE_ID, APPROVER);

        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.approvedBy()).isEqualTo(APPROVER);
        assertThat(approved.approvedAt()).isNotNull();
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("notifications.template.approved");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload())
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"approvedBy\":\"" + APPROVER + "\"");
    }

    @Test
    void approveAlreadyActiveReturnsConflict() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.of(template("ACTIVE", APPROVER)));

        assertThatThrownBy(() -> service.approve(TENANT, TEMPLATE_ID, APPROVER))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_ALREADY_ACTIVE);
                    assertThat(ex.errorCode().status().value()).isEqualTo(409);
                });
        verify(templates, never()).markApproved(any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void approveRetiredReturnsConflict() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.of(template("RETIRED", APPROVER)));

        assertThatThrownBy(() -> service.approve(TENANT, TEMPLATE_ID, APPROVER))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_STATUS_CONFLICT));
    }

    @Test
    void approveMissingTemplateReturns404() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(TENANT, TEMPLATE_ID, APPROVER))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.errorCode().status().value()).isEqualTo(404);
                });
    }

    @Test
    void approveRequiresApprover() {
        assertThatThrownBy(() -> service.approve(TENANT, TEMPLATE_ID, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void retireActiveWritesRetiredEvent() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.of(template("ACTIVE", APPROVER)));
        when(templates.markRetired(eq(TENANT), eq(TEMPLATE_ID), any()))
                .thenReturn(Optional.of(template("RETIRED", APPROVER)));

        NotificationTemplateDto retired = service.retire(TENANT, TEMPLATE_ID);

        assertThat(retired.status()).isEqualTo("RETIRED");
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("notifications.template.retired");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(3L);
        assertThat(event.getValue().payload()).contains("\"status\":\"RETIRED\"");
    }

    @Test
    void retireDraftIsAllowed() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.of(template("DRAFT", null)));
        when(templates.markRetired(eq(TENANT), eq(TEMPLATE_ID), any()))
                .thenReturn(Optional.of(template("RETIRED", null)));

        assertThat(service.retire(TENANT, TEMPLATE_ID).status()).isEqualTo("RETIRED");
    }

    @Test
    void retireAlreadyRetiredReturnsConflict() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.of(template("RETIRED", APPROVER)));

        assertThatThrownBy(() -> service.retire(TENANT, TEMPLATE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_STATUS_CONFLICT));
        verify(templates, never()).markRetired(any(), any(), any());
    }

    @Test
    void retireMissingTemplateReturns404() {
        when(templates.findById(TENANT, TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retire(TENANT, TEMPLATE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private CreateNotificationTemplateRequest request(String channel, String locale, int version) {
        return new CreateNotificationTemplateRequest(null, "CLAIM_NOTICE", version, channel, locale,
                "Claim {{claimNumber}} requires attention.");
    }

    private NotificationTemplate template(String status, UUID approvedBy) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new NotificationTemplate(TEMPLATE_ID, TENANT, "CLAIM_NOTICE", 2, "EMAIL", "en-US",
                "Claim {{claimNumber}} requires attention.", status, approvedBy,
                approvedBy == null ? null : now, now, now);
    }
}
