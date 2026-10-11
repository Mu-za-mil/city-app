# City App service identity contract

**Status:** Proposed contract for the staged service-boundary cleanup  
**Scope:** Contract only. This document does not change JWTs, authorization behavior, entities, migrations, or database configuration.

## Why this contract exists

The gateway and resource services must agree on who the caller is without requiring every service to load a full copy of the `User` persistence entity. A notification record or device token needs a stable owner identifier; it does not need a password hash, profile fields, or a service-local JPA `User`.

Today, the shared JWT contract uses the user's email as `sub`, and the notification service loads a local `User` through `UserDetailsService`. This is a transitional implementation, not the target contract.

## Current behavior (do not assume this is already migrated)

- Access-token `sub` is the user's email.
- The token includes `iat` and `exp` and is signed with HS256.
- The shared JWT filter loads service-local `UserDetails`, validates the token, and checks enabled/locked/expiry flags.
- The gateway's `X-User-*` headers are not an identity source for resource services.
- Notification data already stores `user_id` as a scalar identifier in `device_tokens` and `notifications`; it does not need a JPA relationship to the full user entity.
- The main backend still contains profile and admin account operations. Account-status ownership and propagation must be resolved before local user lookups can safely be removed from every resource service.

## Target identity contract

### Stable identity

- Every account has one immutable, globally stable `userId`.
- The identifier is created once by the identity-owning component and is preserved when services or data stores are migrated.
- Email is a mutable contact/login attribute, not the permanent cross-service identity key.
- Existing numeric user IDs must not be regenerated during this cleanup.

### Authenticated principal

Resource services should receive a minimal typed principal with:

- `userId`: required stable account identifier.
- `authorities`: the authenticated caller's granted roles/authorities.
- Optional display/contact claims only when a service demonstrably needs them; never include password hashes or other credentials.

The principal is request-scoped security context data, not a JPA entity. Domain rows continue to store the stable `userId` as an application-level owner reference.

### Trust and authorization rules

- A resource service must verify the bearer token's signature, expiry, and revocation state before building the principal.
- Never derive identity from client-supplied `X-User-*` or `X-Gateway-Request` headers.
- The gateway may strip and replace identity headers for internal routing, but those headers are not a substitute for resource-service token validation.
- Every user-owned read/write must scope its query or authorization check to the principal's `userId`; a valid token alone does not grant access to another user's data.
- Role claims must be validated against the issuer's contract. Role changes need a defined revocation/refresh strategy so stale access tokens do not preserve removed privileges indefinitely.
- Account disable/suspension must have a defined propagation and revocation mechanism before any service stops checking current account eligibility locally.

## Ownership boundaries (target, staged)

| Concern | Intended owner | Notes |
|---|---|---|
| Credentials, login, refresh sessions, token issuance | `auth-service` | The main backend's duplicate registration path has been removed. |
| Canonical stable identity ID and authentication status | `auth-service`, subject to completing the status-management handoff | Main-backend suspend/reinstate operations must be migrated or made to call the owner before removing local status checks. |
| Marketplace profile and business-domain data | Main backend, pending explicit field-by-field ownership decision | Do not remove profile/admin functionality as part of notification decoupling. |
| Notification history and device-token registrations | `notification-service` | Store stable `userId` values; do not own credentials or a duplicate full user record. |

## Migration sequence

1. Agree on this contract before changing runtime behavior.
2. Add a versioned JWT identity representation in the issuer and shared security module. Keep the current email-subject tokens compatible during the transition; do not silently reinterpret existing tokens.
3. Add focused tests for valid/invalid tokens, stable user ID extraction, role handling, account-status/revocation behavior, and forged identity headers.
4. Change notification controllers and services to consume a minimal principal and stable `userId`, not `notification.entity.User`.
5. Remove notification's local user lookup/entity only after event consumers, persistence mappings, startup configuration, and tests no longer depend on it.
6. Handle main-backend identity/profile/status ownership separately.
7. Perform database/schema separation last, with an explicit data migration and reconciliation plan.

## Explicit non-goals for this phase

- No schema or database changes.
- No changes to the current JWT claim format in this documentation-only step.
- No trust in gateway identity headers.
- No deletion of user records or regeneration of user IDs.
- No removal of main-backend profile or account administration until ownership is migrated and tested.
