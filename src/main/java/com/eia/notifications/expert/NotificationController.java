package com.eia.notifications.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal expert API. Only the domain service calls these endpoints; they are
 * not part of the public gateway surface.
 */
@RestController
@RequestMapping("/internal/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Notification> list(@RequestParam UUID tenantId,
                                   @RequestParam(name = "sourceType", required = false) String sourceType,
                                   @RequestParam(name = "sourceId", required = false) UUID sourceId) {
        return service.list(tenantId, sourceType, sourceId);
    }

    @GetMapping("/{id}")
    public Notification get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @GetMapping("/{id}/attempts")
    public List<NotificationDeliveryAttempt> attempts(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.attempts(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Notification create(@Valid @RequestBody CreateNotificationRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/send")
    public Notification send(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.send(tenantId, id);
    }
}
