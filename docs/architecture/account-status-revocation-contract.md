# Account-status ownership and token revocation contract

**Status:** Proposed implementation contract; no runtime behavior changes in this document-only change.

## Problem statement

The current system has multiple user records and more than one place that can change account status:

- The auth service stores credentials, refresh sessions, and issues access tokens.
- The main backend exposes suspend/reinstate operations and changes its local `User.enabled` flag.
- The shared JWT filter in each service loads a service-local `UserDetails` record and checks enabled, locked, account-expired, and credential-expired flags.
- Access tokens are short-lived (currently 15 minutes), and the shared JWT service supports a per-token Redis blacklist.
- The notification service still depends on its local user lookup for account eligibility.

This means a status change in one service is not yet a reliable, canonical status change across all services. We must not remove local lookups until this contract is implemented and tested.

## Decisions

### 1. Canonical ownership

- `auth-service` is the intended authority for authentication status and refresh-session lifecycle.
- The main backend may initiate an administrative suspension/reinstatement only after it has authenticated and authorized a `SUPER_ADMIN`; it must not independently become a second authority for authentication status.
- Marketplace profile fields remain owned by the main backend. A status handoff must not delete or silently overwrite profile data.
- Notification history and device-token ownership remain in notification-service and use the immutable stable user ID.

### 2. Status-change invariants

A successful suspension must:

1. Persist the account as disabled in the authoritative auth store.
2. Revoke every refresh session for that user in the same database transaction where possible.
3. Prevent new login and refresh-token exchange for the disabled account.
4. Make resource-service access-token rejection take effect through an explicitly defined mechanism—not merely when the access token expires.
5. Be idempotent: repeating suspension must not reactivate or corrupt the account.

Reinstatement may re-enable login only after the canonical status has been updated. It must not resurrect revoked refresh tokens; the user should authenticate again to obtain new sessions.

Only the authoritative service may perform these state transitions. Administrative endpoints must enforce role authorization in the service that performs the mutation, not rely only on authorization in the calling backend.

### 3. Access-token revocation options

Evaluate these options before choosing the runtime implementation:

- **Per-token blacklist:** already supported, but it only rejects tokens explicitly blacklisted. A suspension cannot efficiently revoke every issued access token unless all outstanding tokens are tracked.
- **Per-user status/version check:** can reject tokens whose user status or token version is stale, but requires a trusted, sufficiently fresh shared status source and a clear outage policy.
- **Status-change event:** useful for propagating status to resource services, but asynchronous delivery alone leaves a propagation window. The event must be reliably published (transactional outbox or equivalent), idempotently consumed, and paired with a defined cache TTL/fail-closed policy.
- **Short access-token TTL alone:** insufficient as the only suspension mechanism when immediate revocation is a requirement.

Do not implement a new claim or trust gateway `X-User-*` headers as a shortcut. The signed `uid` claim establishes stable identity; it does not prove that an account is still active or that a role is still current.

### 4. Resource-service behavior during migration

Until the replacement mechanism is live and verified:

- Keep the current local `UserDetailsService` lookup and account-eligibility checks.
- Keep JWT signature, expiry, blacklist, subject, and stable-ID validation.
- Never trust client-supplied `X-User-*` or `X-Gateway-Request` headers.
- Do not remove notification-service's `User` entity or lookup.
- Do not split databases or regenerate existing numeric IDs as part of this step.

### 5. Role changes

Role claims and status are separate concerns. Before resource services switch to a minimal JWT principal, decide whether authorities are:
- loaded from an authoritative online status/authority source, or
- carried as signed claims with a bounded staleness window and a revocation/version strategy.

A stale token must not preserve privileged access indefinitely after a role downgrade. Authorization-sensitive services must not accept unsigned role headers.

## Implementation sequence and acceptance criteria

1. **Status-owner API:** introduce a narrowly scoped, authenticated status-management operation in auth-service. It must authorize the caller, validate target ID, prevent suspending a `SUPER_ADMIN` according to policy, and update status plus refresh-session revocation transactionally.
2. **Caller migration:** route the existing main-backend suspend/reinstate actions through that authority. Preserve the public API contract and do not report success if the authoritative update fails. Avoid a dual-write design that can leave two status stores disagreeing.
3. **Immediate access-token policy:** choose and implement a status/version or reliable propagation mechanism. Document behavior when Redis/Kafka/auth-service is unavailable; do not silently fail open.
4. **Tests:** cover unauthorized caller, missing target, forbidden SUPER_ADMIN suspension, repeated suspension/reinstatement, refresh after suspension, existing access token after suspension, and rollback/failure behavior.
5. **Resource-service rollout:** only after the above tests pass, change resource services to a minimal principal and remove their local user lookup in a separate PR.
6. **Database separation:** migrate and reconcile stable IDs and all dependent data only after status ownership and runtime identity behavior are proven.

## Explicit non-goals

- No runtime code or configuration changes in this contract-only step.
- No deletion of user entities or database tables.
- No change to JWT subject/algorithm or stable `uid` semantics.
- No claim that Kafka delivery, local caches, or a 15-minute token lifetime alone provide immediate revocation.
