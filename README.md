# eia-notifications-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-notifications-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Notifications
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_notifications (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Notifications persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Notification rules, templates, routing, delivery, escalation, and acknowledgement.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

## Implemented first slice

- Spring Boot 3.4.0, Java 22, port **8099**.
- PostgreSQL database `eia_notifications`; `schema.sql` creates `notification` and `notification_template`.
- Internal API:
  - `GET /internal/v1/notifications?tenantId={uuid}` (optional `sourceType` + `sourceId` pair filters to the notifications linked to one source record)
  - `GET /internal/v1/notifications/{id}?tenantId={uuid}`
  - `POST /internal/v1/notifications`
  - `POST /internal/v1/notifications/{id}/send?tenantId={uuid}`
- Enforced invariants: SPEC 11.8.5 channel/status sets, `sent_at >= scheduled_at`, and issuance only with an approved `ACTIVE` template (NTF-01, NTF-14). Errors are RFC 9457 `application/problem+json` with stable `EIA-NTF-*` codes.
- Optional notification source linkage: `sourceType` (`DIRECT_CLAIM`, `ASSUMED_CLAIM`, `POLICY`, `CONTRACT`, `PAYMENT`, `CESSION`, `RETROCESSION`, `DOCUMENT`, `PARTY`, `OTHER`) and `sourceId` are supplied as a pair, with optional `sourceReference`; the fields are returned on reads and included in `notifications.notification.created`/`.sent` payloads when present.
- Source-filtered listing: `GET /internal/v1/notifications` accepts the same `sourceType` + `sourceId` pair to narrow the tenant-scoped list to one source record. The pair is all-or-nothing; half a pair returns `400 EIA-NTF-9412` and an unknown type returns `400 EIA-NTF-9409`.
- Notification template management (tenant-scoped via `X-EIA-Tenant` or `?tenantId=`):
  - `GET /api/v1/notifications/templates?status=&code=` (newest first)
  - `POST /api/v1/notifications/templates` (creates `DRAFT`; duplicate tenant/code/version/locale returns `409 EIA-NTF-9205`)
  - `POST /api/v1/notifications/templates/{id}/approve` `{"approvedBy":"<uuid>"}` (`DRAFT` -> `ACTIVE`; already active returns `409 EIA-NTF-9206`)
  - `POST /api/v1/notifications/templates/{id}/retire` (`DRAFT`/`ACTIVE` -> `RETIRED`)
  - The same routes are available under `/internal/v1/notifications/templates` for the domain service.
- Template lifecycle writes `notifications.template.created`, `notifications.template.approved`, and `notifications.template.retired` to the transactional outbox with payload `{templateId, code, version, locale, status, approvedBy}`.
- The demo tenant `11111111-1111-7111-8111-111111111111` is seeded with approved `CLAIM_NOTICE_STANDARD` v1 and unapproved `CLAIM_NOTICE_DRAFT` v1 templates.

## Local verification

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew build
java -jar build/libs/eia-notifications-expert-0.1.0-SNAPSHOT.jar
```

The service expects PostgreSQL on `localhost:5432`, database `eia_notifications`, user `postgres`, password `postgres`.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
