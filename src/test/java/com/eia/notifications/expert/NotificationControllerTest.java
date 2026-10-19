package com.eia.notifications.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationService service;

    @Test
    void listReturnsNotifications() throws Exception {
        when(service.list(TENANT)).thenReturn(List.of(notification("CREATED", null, null)));

        mvc.perform(get("/internal/v1/notifications").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].notificationNumber").value("NTF-2026-000000001"))
                .andExpect(jsonPath("$[0].status").value("CREATED"));
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
                "CLAIM_NOTICE_STANDARD", 1, null);
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
        return new Notification(NOTIFICATION_ID, TENANT, "NTF-2026-000000001", "NEW_CLAIM_NOTICE",
                "EMAIL", status, PARTY_ID, "Claim requires attention",
                "Claim CLM-2026-000000001 requires attention.", templateCode, templateVersion,
                null, null, 0, Instant.now(), Instant.now());
    }
}
