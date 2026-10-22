package com.eia.notifications.expert;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID NOTIFICATION_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");
    private static final UUID SOURCE_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000003");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationService service;

    @Test
    void listReturnsNotifications() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of(notification("CREATED", null, null)));

        mvc.perform(get("/internal/v1/notifications").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].notificationNumber").value("NTF-2026-000000001"))
                .andExpect(jsonPath("$[0].status").value("CREATED"));
    }

    @Test
    void listFiltersBySourceTypeAndSourceId() throws Exception {
        when(service.list(TENANT, "DIRECT_CLAIM", SOURCE_ID))
                .thenReturn(List.of(notification("CREATED", null, null,
                        "DIRECT_CLAIM", SOURCE_ID, "CLM-2026-0000001")));

        mvc.perform(get("/internal/v1/notifications")
                        .param("tenantId", TENANT.toString())
                        .param("sourceType", "DIRECT_CLAIM")
                        .param("sourceId", SOURCE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceType").value("DIRECT_CLAIM"))
                .andExpect(jsonPath("$[0].sourceId").value(SOURCE_ID.toString()));

        verify(service).list(TENANT, "DIRECT_CLAIM", SOURCE_ID);
    }

    @Test
    void listRejectsSourceTypeWithoutSourceIdWithStructured400() throws Exception {
        when(service.list(TENANT, "POLICY", null))
                .thenThrow(new ApiException(ErrorCode.INVALID_SOURCE_FILTER,
                        "sourceId is required when sourceType is supplied."));

        mvc.perform(get("/internal/v1/notifications")
                        .param("tenantId", TENANT.toString())
                        .param("sourceType", "POLICY"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9412"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void listRejectsSourceIdWithoutSourceTypeWithStructured400() throws Exception {
        when(service.list(TENANT, null, SOURCE_ID))
                .thenThrow(new ApiException(ErrorCode.INVALID_SOURCE_FILTER,
                        "sourceType is required when sourceId is supplied."));

        mvc.perform(get("/internal/v1/notifications")
                        .param("tenantId", TENANT.toString())
                        .param("sourceId", SOURCE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9412"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void listRejectsUnknownSourceTypeWithStructured400() throws Exception {
        when(service.list(TENANT, "BRIBE", SOURCE_ID))
                .thenThrow(new ApiException(ErrorCode.INVALID_SOURCE_TYPE,
                        "Source type 'BRIBE' is not one of: DIRECT_CLAIM, ASSUMED_CLAIM, POLICY, "
                                + "CONTRACT, PAYMENT, CESSION, RETROCESSION, DOCUMENT, PARTY, OTHER."));

        mvc.perform(get("/internal/v1/notifications")
                        .param("tenantId", TENANT.toString())
                        .param("sourceType", "BRIBE")
                        .param("sourceId", SOURCE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9409"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void listRejectsMalformedSourceIdWithStructured400() throws Exception {
        mvc.perform(get("/internal/v1/notifications")
                        .param("tenantId", TENANT.toString())
                        .param("sourceType", "DIRECT_CLAIM")
                        .param("sourceId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9502"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void getUnknownNotificationReturnsProblemJson404() throws Exception {
        when(service.get(TENANT, NOTIFICATION_ID)).thenThrow(new ApiException(ErrorCode.NOT_FOUND,
                "Notification '" + NOTIFICATION_ID + "' was not found."));

        mvc.perform(get("/internal/v1/notifications/{id}", NOTIFICATION_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9001"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void createReturns201AndNotification() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(TENANT, "NEW_CLAIM_NOTICE",
                "EMAIL", PARTY_ID, "Claim requires attention", "Claim CLM-2026-000000001 requires attention.",
                "CLAIM_NOTICE_STANDARD", 1, null, null, null, null);
        when(service.create(any(CreateNotificationRequest.class)))
                .thenReturn(notification("CREATED", "CLAIM_NOTICE_STANDARD", 1));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "NEW_CLAIM_NOTICE",
                                  "channel": "EMAIL",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "Claim requires attention",
                                  "body": "Claim CLM-2026-000000001 requires attention.",
                                  "templateCode": "CLAIM_NOTICE_STANDARD",
                                  "templateVersion": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void createAcceptsDunningNoticeType() throws Exception {
        when(service.create(any(CreateNotificationRequest.class)))
                .thenReturn(notification("CREATED", null, null));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "DUNNING_NOTICE",
                                  "channel": "EMAIL",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "Payment is overdue",
                                  "body": "Invoice INV-2026-000000001 is overdue."
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateNotificationRequest> request = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(service).create(request.capture());
        assertThat(request.getValue().notificationType()).isEqualTo("DUNNING_NOTICE");
    }

    @Test
    void createRejectsInvalidChannelWithStructured400() throws Exception {
        when(service.create(any(CreateNotificationRequest.class)))
                .thenThrow(new ApiException(ErrorCode.INVALID_CHANNEL, "Channel 'FAX' is not allowed."));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "NEW_CLAIM_NOTICE",
                                  "channel": "FAX",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "Claim requires attention",
                                  "body": "Claim CLM-2026-000000001 requires attention."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9401"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void createValidationFailureListsFieldViolations() throws Exception {
        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "NEW_CLAIM_NOTICE",
                                  "channel": "EMAIL",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "",
                                  "body": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9404"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").exists());
    }

    @Test
    void createAcceptsAndReturnsSourceLinkage() throws Exception {
        when(service.create(any(CreateNotificationRequest.class)))
                .thenReturn(notification("CREATED", null, null,
                        "DIRECT_CLAIM", SOURCE_ID, "CLM-2026-0000001"));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "NEW_CLAIM_NOTICE",
                                  "channel": "EMAIL",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "Claim requires attention",
                                  "body": "Claim CLM-2026-000000001 requires attention.",
                                  "sourceType": "DIRECT_CLAIM",
                                  "sourceId": "0192f3a4-0000-7000-8000-000000000003",
                                  "sourceReference": "CLM-2026-0000001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("DIRECT_CLAIM"))
                .andExpect(jsonPath("$.sourceId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.sourceReference").value("CLM-2026-0000001"));

        ArgumentCaptor<CreateNotificationRequest> request = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(service).create(request.capture());
        assertThat(request.getValue().sourceType()).isEqualTo("DIRECT_CLAIM");
        assertThat(request.getValue().sourceId()).isEqualTo(SOURCE_ID);
        assertThat(request.getValue().sourceReference()).isEqualTo("CLM-2026-0000001");
    }

    @Test
    void createWithoutSourceLinkageReturnsNullSourceFields() throws Exception {
        when(service.create(any(CreateNotificationRequest.class)))
                .thenReturn(notification("CREATED", null, null));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "MENTION",
                                  "channel": "IN_APP",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "You were mentioned",
                                  "body": "You were mentioned in a claim note."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value(nullValue()))
                .andExpect(jsonPath("$.sourceId").value(nullValue()))
                .andExpect(jsonPath("$.sourceReference").value(nullValue()));
    }

    @Test
    void createRejectsUnknownSourceTypeWithStructured400() throws Exception {
        when(service.create(any(CreateNotificationRequest.class)))
                .thenThrow(new ApiException(ErrorCode.INVALID_SOURCE_TYPE,
                        "Source type 'BRIBE' is not allowed."));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "NEW_CLAIM_NOTICE",
                                  "channel": "EMAIL",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "Claim requires attention",
                                  "body": "Claim CLM-2026-000000001 requires attention.",
                                  "sourceType": "BRIBE",
                                  "sourceId": "0192f3a4-0000-7000-8000-000000000003"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9409"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void createRejectsHalfSourcePairWithStructured400() throws Exception {
        when(service.create(any(CreateNotificationRequest.class)))
                .thenThrow(new ApiException(ErrorCode.INVALID_SOURCE_LINKAGE,
                        "sourceId is required when sourceType is supplied."));

        mvc.perform(post("/internal/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "notificationType": "NEW_CLAIM_NOTICE",
                                  "channel": "EMAIL",
                                  "recipientPartyId": "0192f3a4-0000-7000-8000-000000000002",
                                  "subject": "Claim requires attention",
                                  "body": "Claim CLM-2026-000000001 requires attention.",
                                  "sourceType": "POLICY"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9410"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void sendRejectsUnapprovedTemplateWithStructured400() throws Exception {
        when(service.send(eq(TENANT), eq(NOTIFICATION_ID)))
                .thenThrow(new ApiException(ErrorCode.TEMPLATE_NOT_APPROVED,
                        "Template is DRAFT; templates must be approved before activation."));

        mvc.perform(post("/internal/v1/notifications/{id}/send", NOTIFICATION_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9202"))
                .andExpect(jsonPath("$.category").value("BUSINESS_RULE"));
    }

    @Test
    void missingTenantParameterReturnsStructured400() throws Exception {
        mvc.perform(get("/internal/v1/notifications"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9501"));
    }

    private Notification notification(String status, String templateCode, Integer templateVersion) {
        return notification(status, templateCode, templateVersion, null, null, null);
    }

    private Notification notification(String status, String templateCode, Integer templateVersion,
                                      String sourceType, UUID sourceId, String sourceReference) {
        return new Notification(NOTIFICATION_ID, TENANT, "NTF-2026-000000001", "NEW_CLAIM_NOTICE",
                "EMAIL", status, PARTY_ID, "Claim requires attention",
                "Claim CLM-2026-000000001 requires attention.", templateCode, templateVersion,
                null, null, 0, Instant.now(), Instant.now(),
                sourceType, sourceId, sourceReference);
    }
}
