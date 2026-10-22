package com.eia.notifications.expert;

import java.util.UUID;

/**
 * Optional retirement body. Retirement needs no payload; the optional tenant
 * id lets direct internal callers keep tenant context in the JSON body when
 * they do not send the {@code X-EIA-Tenant} header or {@code tenantId} query
 * parameter.
 */
public record RetireNotificationTemplateRequest(UUID tenantId) {
}
