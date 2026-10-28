# Feature: Harden public contact endpoint against abuse

## 1) Goal

Protect the public contact API endpoint from anonymous abuse while preserving legitimate form submissions from the owner.

For the initial rollout, accept requests only when:

1. Payload is valid (`name`, `email`, `subject`, `message` constraints).
2. `email` equals `vriesjo@gmail.com` (exact allowlist entry).
3. Header `X-Contact-Token` matches a server-side secret.

Requests that fail validation or authorization must return **HTTP 400/401/403** and never trigger downstream side effects (email send, persistence, queueing).

## 2) Threat model and abuse cases

Address these risks:

- Anonymous bot spam against a public endpoint.
- Cost amplification from high-volume calls.
- Spoofed JSON payloads pretending to be the owner email.
- Header/token omission or replay attempts.
- Open-relay style misuse if user-controlled recipients are ever introduced.

Out of scope for this first feature:

- Full user authentication (OAuth/session/JWT).
- Multi-user allowlists.
- CAPTCHA UX integration (can be added later).

## 3) Required behavior

### Request contract

- Method/path: existing contact endpoint (no route change required).
- Required header: `X-Contact-Token`.
- Required JSON fields: `name`, `email`, `subject`, `message`.
- Content type: `application/json`.

### Validation rules

- `name`: nonblank, max 120 chars.
- `email`: nonblank, valid email format, max 320 chars.
- `subject`: nonblank, max 200 chars.
- `message`: nonblank, max 8_000 chars.
- Reject control characters except standard whitespace (`\n`, `\r`, `\t`) in free-text fields.
- If HTML is not needed, reject obvious markup/script payloads rather than sanitizing and forwarding.

### Authorization rules

Order checks to fail fast:

1. Validate payload shape/size.
2. Verify `email` is exactly `vriesjo@gmail.com`.
3. Verify `X-Contact-Token` equals configured secret.

Return codes:

- `400` for malformed/invalid payload.
- `401` for missing token header.
- `403` for wrong allowlisted email or wrong token.
- `429` when throttled by API Gateway (or WAF, if enabled later).

Do not reveal whether email or token failed in detail; use generic auth failure messages.

## 4) Secret handling

Do not hardcode the token in source control.

Use one of:

1. **Lambda environment variable** (fastest first version):
   - `CONTACT_TOKEN`
2. **AWS Secrets Manager** (preferred long-term):
   - Secret value loaded at startup and cached in memory.

If using env var now, keep code structured so Secrets Manager can replace the source without rewriting validation flow.

GitHub Secrets are only for CI/deployment injection; runtime validation must use values available inside AWS execution.

## 5) Infrastructure changes (Terraform)

Add/update:

- Lambda environment variables:
  - `CONTACT_ALLOWED_EMAIL=vriesjo@gmail.com`
  - `CONTACT_TOKEN` (or secret reference mechanism).
- API Gateway method/stage throttling (baseline burst/rate limits).
- CloudWatch alarms:
  - 4XX/5XX spikes.
  - Request-rate spikes.
  - Throttles > threshold.

Optional later hardening (only if abuse is observed):

- AWS WAF association with API Gateway stage.
- AWS managed common/bad-input rules.
- Rate-based rule to block/challenge abusive IPs.

Ensure values differ per environment (`dev` vs `prod`) and are never printed in plan/apply logs.

## 6) Application changes

Expected changes:

- Contact handler/service:
  - Parse and validate JSON.
  - Enforce allowlisted email.
  - Verify `X-Contact-Token`.
  - Return explicit HTTP status without downstream processing on failure.
- Configuration layer:
  - Load required env vars (and fail clearly when missing).
- Logging:
  - Log request id, decision (`accepted`/`rejected`), reason code, and coarse metadata.
  - Never log token value or full message body.

Use constant-time string comparison for token matching where practical.

## 7) Testing

Add focused tests for:

- Valid request (allowlisted email + correct token) -> success path.
- Missing token -> `401`.
- Wrong token -> `403`.
- Non-allowlisted email -> `403`.
- Invalid payload fields/lengths -> `400`.
- Ensure downstream sender/store is never called on rejected requests.

If integration tests exist, include one API-level test confirming throttling behavior is surfaced as `429`.

## 8) Rollout plan

1. Implement validation and token/email checks behind configuration.
2. Deploy to dev with temporary token.
3. Verify success and rejection scenarios manually with `curl`.
4. Enable API Gateway throttling + alarms in dev and confirm expected behavior.
5. Promote to prod with a rotated token.
6. Monitor abuse/cost patterns and add WAF only if needed.
7. Document token rotation procedure.

## 9) Acceptance criteria

- [ ] Endpoint rejects requests missing `X-Contact-Token` with `401`.
- [ ] Endpoint rejects invalid token with `403`.
- [ ] Endpoint rejects any email except `vriesjo@gmail.com` with `403`.
- [ ] Endpoint returns `400` for invalid payload structure/limits.
- [ ] Rejected requests do not trigger downstream side effects.
- [ ] Token is not hardcoded and is sourced from AWS runtime configuration.
- [ ] API Gateway throttling protections are active in target environment.
- [ ] WAF is documented as optional and only enabled when abuse monitoring justifies it.
- [ ] Logs/metrics support abuse detection without leaking secrets/content.
