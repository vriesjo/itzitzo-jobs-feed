# Feature: SES email ingestion to a DynamoDB-backed RSS feed

## 1) Goal

Implement two event paths through the existing Java 17 Lambda:

```text
SES receipt rule
  -> S3: store raw email
  -> Lambda: receive SES event, fetch and parse email
  -> Bedrock: extract structured jobs
  -> DynamoDB: persist jobs without duplicates

API Gateway HTTP API: GET /feed.xml
  -> Lambda: query recent jobs from DynamoDB
  -> RSS 2.0 XML
  -> HTTP 200
```

Keep `LambdaHandler` as the event router. Implement ingestion and feed rendering in separate services, reusing the existing Bedrock and DynamoDB integrations.

This file is an implementation specification, not a statement that these features are already deployed.

## 2) Baseline before this feature

The following describes the starting point. Application code and opt-in infrastructure now implement the planned paths; live dev acceptance and the Micro/Lite comparison remain required before production rollout. See the README for configuration and replay instructions.

- `LambdaHandler` accepts a generic map and routes manual `action` values. An SES or API Gateway event currently falls through to the health response.
- `BedrockService` invokes a Titan-style model and returns the raw response body. It does not extract or validate job JSON.
- `DynamoDbService` stores opaque payloads using `PutItem` and reads individual items using `GetItem`. It has no feed query or duplicate protection.
- The DynamoDB table already has string `pk` and `sk` keys and can support the proposed access patterns without a new index.
- Terraform provisions the application Lambda, table, IAM permissions, and logs. Email receipt wiring and an HTTP API must be added.

## 3) Scope and initial behavior

Include:

- One configured receiving address and one feed per deployment environment.
- Zero to 20 extracted jobs per email.
- Plain-text and HTML MIME messages, including multipart messages and encoded headers.
- A public, read-only RSS endpoint returning the latest 50 jobs.
- Duplicate-safe writes, explicit failures, asynchronous failure capture, and structured logging.
- Infrastructure and application changes deployed to dev before prod.

Exclude initially:

- Attachment extraction, OCR, linked-page scraping, and following application URLs.
- Cross-email job deduplication, editing workflows, multiple subscriber feeds, and authentication.
- Public HTTP access to the existing `saveJob`, `getJob`, or `bedrockInvoke` actions.

Treat these limits and defaults as the initial contract. Make changes explicitly rather than silently truncating messages or extracted job lists.

## 4) Event routing

Update `LambdaHandler.handleRequest` to recognize supported event shapes before evaluating legacy actions:

1. SES events: a non-empty `Records` array whose records have `eventSource: aws:ses` and valid SES metadata.
2. API Gateway HTTP API events: payload version `2.0` with `requestContext.http`.
3. Existing direct invocation actions, retained for compatibility but not accessible through HTTP.

Validate complete event shapes without unchecked casts. Reject unsupported or mixed-source record batches explicitly; do not acknowledge them as successful health checks.

Process every SES record. If a later record fails, propagate the failure so asynchronous retries occur; previously completed records must be safe to process again.

For HTTP requests, support only `GET /feed.xml`. Return 404 for unknown paths and 405 with `Allow: GET` for unsupported methods on the feed path.

## 5) SES receipt and email parsing

SES Lambda receipt events contain metadata, not the full MIME body.

Configure the receipt rule with actions in this order:

1. Store the raw message in the private email bucket under a configured prefix.
2. Invoke Lambda asynchronously.

Derive the object key from the configured prefix and SES `mail.messageId`, matching the receipt rule's S3 key convention. Do not accept arbitrary bucket names or object locations from email content.

Add an email reader using the AWS SDK S3 client and a maintained MIME library:

- Bound the downloaded raw message size; proposed initial limit: 5 MiB.
- Decode character sets, transfer encodings, headers, and multipart structures.
- Prefer a non-attachment plain-text body; otherwise convert HTML to readable text using an HTML parser.
- Ignore attachments, scripts, remote images, and external resources.
- Bound normalized text before inference; proposed initial limit: 64 KiB, additionally constrained by the selected model's token limit.
- Reject oversized or unreadable messages explicitly and send failed events through the configured asynchronous failure path.

Use SES receipt verdicts to enforce the agreed spam, malware, and authentication policy. Document the accepted verdicts before enabling the receiving rule.

## 6) Bedrock extraction contract

Introduce a typed `Job` model and a dedicated extraction operation rather than passing raw model responses into storage.

### Model selection and API

Use Amazon Nova instead of Titan for the new extraction path:

| Model | Proposed role | Model ID |
|---|---|---|
| Nova Micro | Default candidate for normalized email text; prioritize low latency and cost | `amazon.nova-micro-v1:0` |
| Nova Lite | Alternative candidate if representative extraction examples show better results; also supports image/video inputs outside this feature's initial scope | `amazon.nova-lite-v1:0` |

Start by evaluating Micro and Lite against the same email fixtures. Compare field accuracy, unsupported/invented facts, valid JSON rate, latency, and token usage before choosing the production default. Lite's multimodal support alone is not a reason to use it when the pipeline sends only normalized text.

Use `BedrockRuntimeClient.converse` with typed SDK messages and a system instruction, not the existing Titan-specific `inputText` request. Read generated text from the Converse response's output message and validate its JSON against the extraction contract below. Set an explicit output token budget and low temperature; detect truncation or other incomplete stop reasons rather than storing partial output.

Keep `BEDROCK_MODEL_ID` configurable so either Nova model can be selected without Java changes. In `eu-west-1`, verify whether the chosen model requires a supported cross-region inference profile and configure its ID/ARN instead of assuming the bare model ID works. Review profile destination regions and IAM permissions before enabling cross-region processing of email content.

Do not silently fall back between models. Choose the model explicitly per environment, and report invocation failures through the normal ingestion failure path.

References: [Nova model specifications](https://docs.aws.amazon.com/nova/latest/userguide/what-is-nova.html) and [Nova Converse API](https://docs.aws.amazon.com/nova/latest/userguide/using-converse-api.html).

### Extraction output

Expected generated JSON:

```json
{
  "jobs": [
    {
      "title": "Java Developer",
      "company": "Example Company",
      "location": "Remote",
      "description": "Build and maintain Java services.",
      "applicationUrl": "https://example.com/jobs/java-developer",
      "publishedAt": null
    }
  ]
}
```

Requirements:

- Instruct the model to treat the email as untrusted content, not executable instructions.
- Request only job data; do not invent missing employers, dates, or links.
- Accept `{"jobs":[]}` as a successfully processed non-job email.
- Require nonblank title and description for every job. Allow missing company, location, application URL, and publication date.
- Use typed SDK requests and a JSON library for generated-output deserialization, replacing manual JSON escaping.
- Read the Converse output message's text blocks before parsing generated JSON; handle unexpected content or stop reasons explicitly.
- Validate types, required fields, maximum field lengths, maximum job count, and parseable timestamps.
- Accept only absolute HTTP/HTTPS application URLs. Never fetch those URLs during ingestion.
- Reject malformed or invalid output explicitly; do not persist partially validated jobs.
- Verify Nova model/profile access, regional availability, Converse response handling, and inference limits in dev. Changing only the model ID while keeping the Titan request body is not a valid migration.
- Distinguish dependency failures from valid empty extraction. Bound SDK retries and Lambda execution time.

## 7) DynamoDB model and idempotency

Reuse the existing table:

| Item | Partition key | Sort key |
|---|---|---|
| Feed job | `FEED#jobs` | `<receivedAt>#<jobId>` |
| Processed message | `EMAIL#<sesMessageId>` | `METADATA` |

Job attributes:

- `jobId`: deterministic ID derived from the SES message ID and the extracted job ordinal.
- `title`, `company`, `location`, `description`, `applicationUrl`.
- `publishedAt`: validated source date when present.
- `receivedAt`: stable SES receipt timestamp normalized to fixed-width UTC.
- `sourceMessageId`: internal traceability; do not publish it in RSS.

Order the feed by receipt time, not an LLM-generated date. Use the same receipt timestamp on retries rather than the current wall-clock time.

Ingestion write algorithm:

1. Read the processed-message marker before calling Bedrock; skip completed messages.
2. Extract and validate all jobs.
3. Use a DynamoDB transaction to write the jobs and conditionally create the processed-message marker only if it does not exist.
4. For zero jobs, still write the marker.
5. If the transaction loses a concurrent duplicate race, confirm the marker exists before acknowledging success. Propagate other transaction failures.

The 20-job bound leaves room for the marker within DynamoDB's transaction item limit. Also enforce individual-item and total transaction size limits.

Keep markers indefinitely in the first version so replay protection does not expire. Do not claim cross-email deduplication: identical vacancies in different emails may create separate feed entries.

Extend `JobStore` with domain operations for completed-message lookup, atomic ingestion, and querying recent jobs. Preserve existing direct-invocation operations where compatibility is required.

Use `Query`, not `Scan`, with descending sort order. Continue through DynamoDB pages until 50 jobs are collected or the partition is exhausted.

## 8) RSS and HTTP contract

Generate RSS 2.0 with an XML writer:

- Channel: configured title, website link, description, and feed URL.
- Item: title, plain-text description, stable GUID with `isPermaLink="false"`, optional application link, and RFC 1123 publication date.
- Use the validated publication date when present; otherwise use receipt time.
- Escape text and handle invalid XML characters. Do not interpolate raw HTML into XML.
- Exclude sender addresses, raw MIME, internal metadata, and private email details.
- Publish only job fields explicitly allowed by the extraction schema.

API Gateway response:

```json
{
  "statusCode": 200,
  "headers": {
    "Content-Type": "application/rss+xml; charset=utf-8"
  },
  "body": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>...",
  "isBase64Encoded": false
}
```

An empty table returns 200 with a valid empty channel. A storage or rendering failure returns a logged 5xx response without internal stack traces or a success-shaped empty feed.

Keep the HTTP path independent of S3 reads and Bedrock calls.

## 9) Application changes

Expected implementation surfaces:

- `LambdaHandler.java`: source detection and delegation, HTTP-only route restrictions.
- `service/JobStore.java` and `service/DynamoDbService.java`: typed storage operations, transactions, and recent-jobs query.
- `service/BedrockGateway.java` and `service/BedrockService.java`: Nova extraction through Converse with structured output validation; preserve the existing direct invocation contract where required.
- `config/AwsClientsConfig.java`: reusable S3 client.
- New focused classes for the job model, email reading/parsing, ingestion orchestration, and RSS rendering.
- `build.gradle`: direct dependencies for S3, MIME parsing, JSON serialization, and HTML-to-text conversion as needed.

Reuse the existing constructor-injection approach for testability. Do not put MIME parsing, model prompts, DynamoDB transactions, or XML construction directly in the Lambda router.

Configuration should include the email bucket/prefix, feed metadata, and public feed URL. Lambda supplies `AWS_REGION`; do not declare it in Terraform's Lambda environment map.

## 10) Infrastructure and deployment

Add or update Terraform for:

- Private encrypted S3 email storage with public access blocked and a proposed 30-day raw-message lifecycle.
- SES receipt rule actions and a bucket policy scoped to the intended SES account/rule.
- Lambda invoke permission restricted to the intended SES receipt rule.
- Lambda S3 read permission limited to the email prefix.
- DynamoDB permissions for the selected conditional/transactional operations and feed query.
- Bedrock permissions scoped to the selected model or inference profile where supported.
- HTTP API, `GET /feed.xml` route, Lambda proxy integration using payload format v2, stage, and narrowly scoped invoke permission.
- Asynchronous failure destination, delivery permissions, retry/event-age settings, and alarms for ingestion failures and destination delivery failures.
- An output for the feed URL and appropriate configuration variables.

Inspect the existing SES receiving setup before modifying it. Verify receiving-domain identity, MX routing, regional support, and active receipt rules. Do not replace an active rule set or interrupt other mail delivery without approval.

Use the existing persistent S3 Terraform backend and environment-specific state keys. Plan and apply from the feature branch in dev first. Merging to `main` currently triggers prod deployment, so review the prod plan and SES impact before merging.

A failure-destination record wraps the original invocation event. Document how to extract and replay that original SES event; do not attach an SQS trigger and assume the wrapper is already an SES event.

## 11) Logging and failure handling

Log request IDs, SES message IDs, stage names, durations, extracted counts, duplicate outcomes, and explicit failure codes.

Do not log full email bodies, model prompts, raw model responses, or sender details. SES ingestion failures must propagate for asynchronous retry and eventual failure capture.

Use the bounded raw-email retention window to support investigation and controlled replay. Acknowledge that a processed-marker lookup avoids routine repeat inference but concurrent invocations can still incur duplicate Bedrock calls before one transaction wins.

## 12) Implementation sequence

1. Define typed job/extraction contracts, routing rules, limits, and configuration.
2. Add DynamoDB recent-jobs query and transaction-based ingestion with duplicate handling.
3. Add S3/MIME parsing and Nova extraction through Converse with validation; compare Micro and Lite on representative fixtures.
4. Implement SES ingestion orchestration and explicit retryable failure behavior.
5. Implement RSS rendering and API Gateway response handling.
6. Wire SES, S3, HTTP API, IAM, and asynchronous failure handling in Terraform.
7. Deploy to dev, run the acceptance scenarios, and document replay/deployment instructions.
8. Review production email routing and Terraform changes before enabling prod.

## 13) Acceptance criteria

- [ ] A real email delivered to the configured address is stored in S3 and processed by Lambda.
- [ ] Plain-text, HTML, encoded-header, and multipart fixtures extract the expected text without processing attachments.
- [ ] Bedrock returns validated structured jobs; malformed output cannot enter the feed.
- [ ] Nova Micro and Lite are evaluated on the same fixtures, and the chosen model/profile works through Converse in the deployment region.
- [ ] Truncated responses and model/profile access failures are explicit failures, not partial jobs or silent model switches.
- [ ] A valid non-job email is marked complete without publishing a job.
- [ ] Replaying the same SES event does not create duplicate jobs, including concurrent delivery.
- [ ] A multi-record event retries safely after a partial processing failure.
- [ ] The ingestion transaction cannot leave a completed marker with missing jobs.
- [ ] A failed ingestion reaches the failure destination and can be replayed using its original SES event.
- [ ] `GET /feed.xml` returns HTTP 200 and parseable RSS with the most recently received jobs first.
- [ ] Empty feeds, non-ASCII text, XML-sensitive characters, missing optional fields, and GUID stability are covered.
- [ ] Feed queries handle multiple DynamoDB pages without scans.
- [ ] Unsupported HTTP paths/methods are rejected, and internal actions are unreachable through HTTP.
- [ ] Dependency failures are logged and return the appropriate failure rather than a health response or empty success.
- [ ] All infrastructure remains tracked in persistent state; no existing resources are deleted to resolve state conflicts.
- [ ] Dev smoke test proves email -> job storage -> RSS, while prod and unrelated SES routes remain unchanged.
