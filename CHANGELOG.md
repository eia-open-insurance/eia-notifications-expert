# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Notification source record linkage: optional `sourceType`/`sourceId` pair (`DIRECT_CLAIM`,
  `ASSUMED_CLAIM`, `POLICY`, `CONTRACT`, `PAYMENT`, `OTHER`) and optional `sourceReference` on create,
  the notification representation, and `notifications.notification.created`/`.sent` event payloads.
- Notification template management: tenant-scoped list, DRAFT creation, approval, and retirement at
  `/api/v1/notifications/templates`, plus `notifications.template.created`, `.approved`, and `.retired`
  outbox events.
