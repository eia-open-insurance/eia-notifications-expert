package com.eia.notifications.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationTemplateIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID APPROVER = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final String BASE = "/api/v1/notifications/templates";

    @Autowired
    private NotificationTemplateService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notification_delivery_attempt");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_template");
    }

    @Test
    void createPersistsDraftAndCreatedEvent() {
        NotificationTemplateDto created = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "Claim {{claimNumber}} requires attention."));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.approvedBy()).isNull();
        assertThat(created.approvedAt()).isNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT template_code, template_version, channel, locale, body_template, status,
                       approved_by, approved_at
                  FROM notification_template
                 WHERE tenant_id = ? AND id = ?
                """, TENANT, created.id());
        assertThat(row.get("template_code")).isEqualTo("CLAIM_NOTICE");
        assertThat(row.get("template_version")).isEqualTo(2);
        assertThat(row.get("channel")).isEqualTo("EMAIL");
        assertThat(row.get("locale")).isEqualTo("en-US");
        assertThat(row.get("body_template")).isEqualTo("Claim {{claimNumber}} requires attention.");
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("approved_by")).isNull();
        assertThat(row.get("approved_at")).isNull();

        List<Map<String, Object>> events = events(created.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("event_type")).isEqualTo("notifications.template.created");
        assertThat(events.get(0).get("aggregate_type")).isEqualTo("NotificationTemplate");
        assertThat(events.get(0).get("aggregate_version")).isEqualTo(1L);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"templateId\":\"" + created.id() + "\"")
                .contains("\"code\":\"CLAIM_NOTICE\"")
                .contains("\"version\":2")
                .contains("\"locale\":\"en-US\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"approvedBy\":null");
    }

    @Test
    void duplicateTemplateReturnsConflictAndWritesNoSecondEvent() {
        service.create(TENANT, request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "body"));
        NotificationTemplateDto duplicateLocale = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "fr-FR", "body"));

        assertThatThrownBy(() -> service.create(TENANT,
                request("CLAIM_NOTICE", 2, "SMS", "en-US", "another body")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_ALREADY_EXISTS);
                    assertThat(ex.errorCode().status().value()).isEqualTo(409);
                });

        assertThat(count("notification_template")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(duplicateLocale.locale()).isEqualTo("fr-FR");
    }

    @Test
    void listReturnsNewestFirstWithStatusAndCodeFilters() {
        NotificationTemplateDto first = service.create(TENANT,
                request("CLAIM_NOTICE", 1, "EMAIL", "en-US", "body"));
        pause();
        NotificationTemplateDto second = service.create(TENANT,
                request("DUNNING_NOTICE", 1, "EMAIL", "en-US", "body"));
        pause();
        NotificationTemplateDto third = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "body"));

        List<NotificationTemplateDto> all = service.list(TENANT, null, null);
        assertThat(all).extracting(NotificationTemplateDto::id)
                .containsExactly(third.id(), second.id(), first.id());

        List<NotificationTemplateDto> byCode = service.list(TENANT, null, "CLAIM_NOTICE");
        assertThat(byCode).extracting(NotificationTemplateDto::version).containsExactly(2, 1);

        assertThat(service.list(TENANT, "ACTIVE", null)).isEmpty();
        assertThat(service.list(TENANT, "DRAFT", null)).hasSize(3);

        service.approve(TENANT, third.id(), APPROVER);
        List<NotificationTemplateDto> active = service.list(TENANT, "ACTIVE", null);
        assertThat(active).singleElement()
                .satisfies(dto -> assertThat(dto.id()).isEqualTo(third.id()));
    }

    @Test
    void approveMovesDraftToActiveWithEvidenceAndEvent() {
        NotificationTemplateDto created = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "body"));

        NotificationTemplateDto approved = service.approve(TENANT, created.id(), APPROVER);

        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.approvedBy()).isEqualTo(APPROVER);
        assertThat(approved.approvedAt()).isNotNull();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, approved_by, approved_at
                  FROM notification_template
                 WHERE tenant_id = ? AND id = ?
                """, TENANT, created.id());
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("approved_by")).isEqualTo(APPROVER);
        assertThat(row.get("approved_at")).isNotNull();

        List<Map<String, Object>> events = events(created.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("event_type")).isEqualTo("notifications.template.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("notifications.template.approved");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);
        assertThat((String) events.get(1).get("payload"))
                .contains("\"templateId\":\"" + created.id() + "\"")
                .contains("\"code\":\"CLAIM_NOTICE\"")
                .contains("\"version\":2")
                .contains("\"locale\":\"en-US\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"approvedBy\":\"" + APPROVER + "\"");

        assertThatThrownBy(() -> service.approve(TENANT, created.id(), APPROVER))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_ALREADY_ACTIVE));
        assertThat(count("outbox_event")).isEqualTo(2);
    }

    @Test
    void retireActiveMovesToRetiredWithEvent() {
        NotificationTemplateDto created = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "body"));
        service.approve(TENANT, created.id(), APPROVER);

        NotificationTemplateDto retired = service.retire(TENANT, created.id());

        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.approvedBy()).isEqualTo(APPROVER);

        List<Map<String, Object>> events = events(created.id());
        assertThat(events).hasSize(3);
        assertThat(events.get(2).get("event_type")).isEqualTo("notifications.template.retired");
        assertThat(events.get(2).get("aggregate_version")).isEqualTo(3L);
        assertThat((String) events.get(2).get("payload"))
                .contains("\"templateId\":\"" + created.id() + "\"")
                .contains("\"status\":\"RETIRED\"")
                .contains("\"approvedBy\":\"" + APPROVER + "\"");

        assertThatThrownBy(() -> service.retire(TENANT, created.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TEMPLATE_STATUS_CONFLICT));
        assertThat(count("outbox_event")).isEqualTo(3);
    }

    @Test
    void draftCanBeRetiredDirectly() {
        NotificationTemplateDto created = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "body"));

        NotificationTemplateDto retired = service.retire(TENANT, created.id());

        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.approvedBy()).isNull();
        List<Map<String, Object>> events = events(created.id());
        assertThat(events.get(1).get("event_type")).isEqualTo("notifications.template.retired");
        assertThat((String) events.get(1).get("payload")).contains("\"approvedBy\":null");
    }

    @Test
    void templateOperationsAreTenantScoped() {
        NotificationTemplateDto created = service.create(TENANT,
                request("CLAIM_NOTICE", 2, "EMAIL", "en-US", "body"));

        assertThat(service.list(OTHER_TENANT, null, null)).isEmpty();

        assertThatThrownBy(() -> service.approve(OTHER_TENANT, created.id(), APPROVER))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.errorCode().status().value()).isEqualTo(404);
                });
        assertThatThrownBy(() -> service.retire(OTHER_TENANT, created.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_template WHERE id = ?", String.class, created.id()))
                .isEqualTo("DRAFT");
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void httpLifecycleWorksEndToEnd() throws Exception {
        String createBody = """
                {
                  "templateCode": "HTTP_NOTICE",
                  "templateVersion": 1,
                  "channel": "EMAIL",
                  "locale": "en-US",
                  "bodyTemplate": "Hello {{name}}"
                }
                """;

        String response = mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("HTTP_NOTICE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.approvedBy").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).path("id").asText();

        mvc.perform(get(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(post(BASE + "/{id}/approve", id)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"" + APPROVER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.approvedBy").value(APPROVER.toString()));

        mvc.perform(post(BASE + "/{id}/retire", id)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-NTF-9205"));
    }

    @Test
    void internalAliasIsAvailableForDomainCalls() throws Exception {
        service.create(TENANT, request("ALIAS_NOTICE", 1, "EMAIL", "en-US", "body"));

        mvc.perform(get("/internal/v1/notifications/templates")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ALIAS_NOTICE"));
    }

    private CreateNotificationTemplateRequest request(String code, int version, String channel,
                                                     String locale, String body) {
        return new CreateNotificationTemplateRequest(null, code, version, channel, locale, body);
    }

    private List<Map<String, Object>> events(UUID templateId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, templateId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void pause() {
        try {
            Thread.sleep(15);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
