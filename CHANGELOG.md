# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Notification source record linkage: optional `sourceType`/`sourceId` pair (`DIRECT_CLAIM`,
  `ASSUMED_CLAIM`, `POLICY`, `CONTRACT`, `PAYMENT`, `CESSION`, `RETROCESSION`, `DOCUMENT`, `PARTY`,
  `OTHER`) and optional `sourceReference` on create, the notification representation, and
  `notifications.notification.created`/`.sent` event payloads.
- Source-filtered notification listing: optional `sourceType` + `sourceId` query parameters on
  `GET /internal/v1/notifications`, tenant-scoped and all-or-nothing, with `INVALID_SOURCE_FILTER`
  (`EIA-NTF-9412`) for half a pair and `INVALID_SOURCE_TYPE` (`EIA-NTF-9409`) for an unknown type.
- Notification template management: tenant-scoped list, DRAFT creation, approval, and retirement at
  `/api/v1/notifications/templates`, plus `notifications.template.created`, `.approved`, and `.retired`
  outbox events.
- Delivery provider abstraction with a default local `SimulatedDeliveryProvider` (`SIMULATOR`) selected
  by `eia.notifications.provider=simulator`; sends record attempt 1 in `notification_delivery_attempt`,
  store `delivered_at` and `provider_reference` on the notification, and include both fields in the
  `notifications.notification.sent` payload.
- Tenant-scoped delivery history: `GET /internal/v1/notifications/{id}/attempts`.

### Changed

- Notification template approval accepts a stable directory identifier (for example `a-adjuster`) as
  `approvedBy` instead of a UUID: the value is trimmed and limited to 1..128 characters,
  `notification_template.approved_by` is a forward-migrated `VARCHAR(128)`, and the template
  projection and lifecycle event payloads carry the string identifier.
