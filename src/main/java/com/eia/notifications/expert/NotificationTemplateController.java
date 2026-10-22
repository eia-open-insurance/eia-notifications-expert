package com.eia.notifications.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Notification template management.
 *
 * <p>Tenant context is accepted from {@code X-EIA-Tenant} (the public API
 * convention) or the {@code tenantId} query parameter (the existing internal
 * expert convention); creation and approval also accept it in the body for
 * direct internal callers. The tenant is never inferred from the template id.</p>
 *
 * <p>The internal path mirrors {@code /internal/v1/notifications} so the
 * domain service can keep calling the expert on its usual surface.</p>
 */
@RestController
@RequestMapping({"/api/v1/notifications/templates", "/internal/v1/notifications/templates"})
public class NotificationTemplateController {

    private final NotificationTemplateService service;

    public NotificationTemplateController(NotificationTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationTemplateDto> list(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "code", required = false) String code) {
        return service.list(tenant(headerTenant, queryTenant), status, code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationTemplateDto create(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @Valid @RequestBody CreateNotificationTemplateRequest request) {
        return service.create(tenant(headerTenant, queryTenant, request.tenantId()), request);
    }

    @PostMapping("/{id}/approve")
    public NotificationTemplateDto approve(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID id,
            @Valid @RequestBody ApproveNotificationTemplateRequest request) {
        return service.approve(tenant(headerTenant, queryTenant, request.tenantId()), id, request.approvedBy());
    }

    @PostMapping("/{id}/retire")
    public NotificationTemplateDto retire(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID id,
            @RequestBody(required = false) RetireNotificationTemplateRequest request) {
        UUID bodyTenant = request == null ? null : request.tenantId();
        return service.retire(tenant(headerTenant, queryTenant, bodyTenant), id);
    }

    private UUID tenant(UUID headerTenant, UUID queryTenant) {
        return tenant(headerTenant, queryTenant, null);
    }

    private UUID tenant(UUID headerTenant, UUID queryTenant, UUID bodyTenant) {
        UUID tenantId = headerTenant != null ? headerTenant : queryTenant != null ? queryTenant : bodyTenant;
        if (tenantId == null) {
            throw new ApiException(ErrorCode.TENANT_REQUIRED,
                    "Tenant is required; send X-EIA-Tenant or tenantId.");
        }
        return tenantId;
    }
}
