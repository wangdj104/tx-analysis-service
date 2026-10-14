# Measurement follow-up notification contract

The independent `ALERT_WORKFLOW_ENABLED` / `alert-workflow.enabled` switch is false by default. `CARE_PLAN_ENABLED` keeps its independent false default. No channel is registered by this feature. Existing enabled user-owned WEBHOOK, WECHAT_WEBHOOK and DINGTALK_WEBHOOK channels are reused.

## Delivery is not completion

- `DELIVERED` means provider acceptance only. It does not mean read, accepted assignment, handling, feedback, clinical review or closure
- `FAILED` is an explicit failure. Only an explicit retryable provider rejection schedules another attempt
- `UNKNOWN` means the outcome could not be established. It never retries automatically
- `NO_CHANNEL` and `SUPPRESSED` never count as delivery, acceptance or completion
- `QUEUED` and `CLAIMED` are pending delivery states

All outbound text is a generic localized update plus a relative `/alert-workflows/{id}?patientId={patientId}` path. It contains no patient names, source values, clinical notes or provider configuration text. Delivery-status responses omit channel IDs, configuration, provider URLs, secrets, raw response bodies and exceptions. `lastResult` is an allowlisted code or null.

The worker checks current feature enablement, account/role/module/source ownership, current cycle, current recipients, applicable clinician/nurse assignment and channel ownership. Checks run at enqueue, claim/preparation, and immediately before transport; the provider adapter rechecks the loaded channel's expected owner. External delivery is outside business and claim transactions. Authorization checks do not hold clinical locks across external network operations.

## Bounded delivery cycles

Claims have a fixed 300-second lease. Expired unattempted claims requeue, including when a paused claimant resumes or preparation crosses expiry while waiting for its row lock. No attempt is consumed in that case. Expired claims with a persisted attempt start become UNKNOWN. Persisted attempt start precedes transport. Completion is conditional on the same active claim token, so a late result cannot overwrite recovered uncertainty.

A delivery cycle permits three total attempts. Retryable failures schedule attempts at +60 and +300 seconds from that cycle's actual first attempt. Manual queue time is not the anchor. Nonretryable failures and UNKNOWN require a deliberate current-admin retry. Session lock-wait settings use the shared notification claim primitive and are explicitly restored before transaction completion.

## Current-authority HTTP interface

Routes use the app's normal `/api` prefix, existing Result success/error envelopes, `Cache-Control: no-store, private` and `X-Content-Type-Options: nosniff`.

`GET /api/alert-workflows/{id}/delivery-status?cursor=...&limit=50`

- Requires current MEASUREMENTS and source read authority; admin role alone gives no clinical read access
- Shows only the authenticated recipient's rows, including historical delivery rows
- Accepts only cursor and limit (1–100); cursor is bound to actor and alert
- Returns `alertId`, `items`, `nextCursor`, `canRetryNotifications` and `deliveryMeaning: "PROVIDER_ACCEPTANCE_ONLY"`
- Each item contains `id`, `status`, `attemptCount`, `nextAttemptAt`, `lastResult`, `deliveredAt`, `createdAt`, `updatedAt`, `retryToken`, `canRetry`, and `requiresDuplicateRiskAcknowledgement`
- Times are UTC ISO-8601 strings or null. The retry token is an opaque row condition, not authority

`POST /api/alert-workflows/notifications/{id}/retry`

The body is exactly `{ "retryToken": "<64 lowercase hexadecimal characters>", "duplicateRiskAcknowledged": false }`. No command envelope or client actor is accepted. Both fields are required. UNKNOWN requires an explicit true flag acknowledging duplicate-delivery risk.

The server requires a currently enabled admin and rechecks governance after the notification row-lock wait and before commit. It checks current event/source/cycle/recipient/channel authority independently before locking and again after waiting, before any token/state/acknowledgement-specific response. Forbidden and absent targets share the same generic403 denial. A successful reset audits the previous safe status/count and assigns a fresh cycle UUID; it returns the updated item above. A row without an original usable channel cannot be redirected or revived by this endpoint.

A stale token returns HTTP 409 `RETRY_CONFLICT`; an ineligible state returns 409 `RETRY_UNAVAILABLE`; malformed input or missing UNKNOWN acknowledgement returns 400 `INVALID_REQUEST`; absent or unauthorized targets return 403 `ACCESS_DENIED`; disabled feature routes return 404 `FEATURE_DISABLED`.

Clients retain the exact original token/body after a timeout. On 409, refresh and show the new status, then obtain a new explicit retry decision. Never silently refresh a token and resubmit: a delayed identical POST must not reset a later FAILED cycle.

## Automated due events

At most100 new due targets are processed per tick. The query skips already-recorded current due identities so later batches make progress and completed targets are not repeatedly locked. One append-only DUE event is recorded per alert/current cycle/exact UTC deadline identity under the alert lock, including when no eligible recipient exists. Outbox dispatch keys additionally identify recipient and channel. Changing a deadline or entering a new cycle creates a different due identity; terminal or changed targets suppress stale pending due deliveries. The due event does not change aggregate version, state, acceptance, evidence or closure.

Only the internal scheduler boundary can append DUE. Its actor ID/name and entry mode are null; actor role is SYSTEM and relation is SCHEDULER. Occurred/recorded UTC times and unchanged from/to state are explicit. The UI must label this as an automated reminder, never attribute it to the patient or a clinician. The human append API and public action allowlist cannot create it. Fresh-init and upgrade event guards enforce the narrow system shape and exact two-field dueKey/dueAt payload against the current UTC deadline; the locked service also verifies current state/cycle and exact deadline/key. Canonical dueAt retains Instant serialization: no fraction for whole seconds,3 digits for milliseconds, otherwise6 digits through microseconds. Epoch-second calculation subtracts the microsecond component before integer conversion to avoid fractional-division rounding and session timezone coupling.

## Acceptance limits

Synthetic H2 and fake JDBC/provider tests are not native MySQL 5.7.18, pooled-driver, browser or actual Java 8 runtime acceptance. Source remains Java 8-compatible. No production migration, feature enablement, live transport, channel configuration or deployment is performed by these tests.

The location is an exact allowlisted relative path with positive numeric alert/patient
IDs, no extra parameters or fragment. Old no-query locations remain recognized but
use the current selection. New worker output always includes patient context. This is
routing information, not an access grant; the destination repeats capabilities/source
checks and the patient-mismatch guard. It does not overwrite the global patient choice.
Producer/localizer and compiled-route synthetic checks do not establish provider delivery
or live browser acceptance.
