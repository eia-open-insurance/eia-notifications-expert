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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationTemplateController.class)
class NotificationTemplateControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID TEMPLATE_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000010");
    private static final String APPROVER = "a-adjuster";
    private static final String BASE = "/api/v1/notifications/templates";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationTemplateService service;

    @Test
    void listReturnsTemplatesUsingTenantHeader() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of(template("DRAFT", null, null)));

        mvc.perform(get(BASE).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(TEMPLATE_ID.toString()))
                .andExpect(jsonPath("$[0].code").value("CLAIM_NOTICE"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].bodyTemplate").value("Claim {{claimNumber}} requires attention."))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void listPassesStatusAndCodeFiltersAndAcceptsTenantQueryParameter() throws Exception {
        when(service.list(TENANT, "ACTIVE", "CLAIM_NOTICE")).thenReturn(List.of());

        mvc.perform(get(BASE)
                        .param("tenantId", TENANT.toString())
                        .param("status", "ACTIVE")
                        .param("code", "CLAIM_NOTICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listWithoutTenantReturnsStructured400() throws Exception {
        mvc.perform(get(BASE))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9501"));
    }

    @Test
    void createReturns201AndTemplate() throws Exception {
        when(service.create(eq(TENANT), any(CreateNotificationTemplateRequest.class)))
                .thenReturn(template("DRAFT", null, null));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "templateCode": "CLAIM_NOTICE",
                                  "templateVersion": 2,
                                  "channel": "EMAIL",
                                  "locale": "en-US",
                                  "bodyTemplate": "Claim {{claimNumber}} requires attention."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(TEMPLATE_ID.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void createAcceptsTenantInBody() throws Exception {
        when(service.create(eq(TENANT), any(CreateNotificationTemplateRequest.class)))
                .thenReturn(template("DRAFT", null, null));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "11111111-1111-7111-8111-111111111111",
                                  "templateCode": "CLAIM_NOTICE",
                                  "templateVersion": 2,
                                  "locale": "en-US",
                                  "bodyTemplate": "Claim {{claimNumber}} requires attention."
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateNotificationTemplateRequest> request =
                ArgumentCaptor.forClass(CreateNotificationTemplateRequest.class);
        verify(service).create(eq(TENANT), request.capture());
        assertThat(request.getValue().templateCode()).isEqualTo("CLAIM_NOTICE");
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        when(service.create(eq(TENANT), any(CreateNotificationTemplateRequest.class)))
                .thenThrow(new ApiException(ErrorCode.TEMPLATE_ALREADY_EXISTS,
                        "Template 'CLAIM_NOTICE' version 2 locale 'en-US' already exists."));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "templateCode": "CLAIM_NOTICE",
                                  "templateVersion": 2,
                                  "locale": "en-US",
                                  "bodyTemplate": "Claim {{claimNumber}} requires attention."
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9205"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void createValidationFailureListsFieldViolations() throws Exception {
        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "templateCode": "",
                                  "templateVersion": 0,
                                  "locale": "en-US",
                                  "bodyTemplate": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-NTF-9404"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").exists());
    }

    @Test
    void approveReturnsActiveTemplate() throws Exception {
        when(service.approve(TENANT, TEMPLATE_ID, APPROVER))
                .thenReturn(template("ACTIVE", APPROVER, Instant.parse("2026-01-01T00:00:00Z")));

        mvc.perform(post(BASE + "/{id}/approve", TEMPLATE_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"" + APPROVER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.approvedBy").value(APPROVER))
                .andExpect(jsonPath("$.approvedAt").exists());
    }

    @Test
    void approveAlreadyActiveReturns409() throws Exception {
        when(service.approve(TENANT, TEMPLATE_ID, APPROVER))
                .thenThrow(new ApiException(ErrorCode.TEMPLATE_ALREADY_ACTIVE,
                        "Template is already ACTIVE and cannot be approved again."));

        mvc.perform(post(BASE + "/{id}/approve", TEMPLATE_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"" + APPROVER + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-NTF-9206"));
    }

    @Test
    void retireReturnsRetiredTemplate() throws Exception {
        when(service.retire(TENANT, TEMPLATE_ID))
                .thenReturn(template("RETIRED", APPROVER, Instant.parse("2026-01-01T00:00:00Z")));

        mvc.perform(post(BASE + "/{id}/retire", TEMPLATE_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void retireAcceptsTenantInBody() throws Exception {
        when(service.retire(TENANT, TEMPLATE_ID))
                .thenReturn(template("RETIRED", APPROVER, Instant.parse("2026-01-01T00:00:00Z")));

        mvc.perform(post(BASE + "/{id}/retire", TEMPLATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void retireWithoutTenantReturnsStructured400() throws Exception {
        mvc.perform(post(BASE + "/{id}/retire", TEMPLATE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-NTF-9501"));
    }

    private NotificationTemplateDto template(String status, String approvedBy, Instant approvedAt) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        return new NotificationTemplateDto(TEMPLATE_ID, "CLAIM_NOTICE", 2, null, "en-US",
                "Claim {{claimNumber}} requires attention.", status, approvedBy, approvedAt,
                createdAt, createdAt);
    }
}
