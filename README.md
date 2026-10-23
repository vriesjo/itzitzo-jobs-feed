# itzitzo-jobs-feed

Java 17 AWS Lambda project (Gradle) with:
- SES/S3 email ingestion and Amazon Nova job extraction through Bedrock Converse
- Duplicate-safe DynamoDB persistence and a public RSS feed through API Gateway
- Terraform provisioning
- GitHub Actions deployment via AWS OIDC

## How it works

The Lambda handler is `com.itzitzo.jobsfeed.LambdaHandler::handleRequest`.

- SES receipt events fetch raw MIME from the configured private S3 bucket, normalize text, extract jobs with Nova, and atomically save jobs plus a processed-message marker.
- API Gateway HTTP API v2 events serve the latest 50 jobs at `GET /feed.xml` as RSS 2.0. Other methods return 405; unknown paths return 404.
- Direct invocations can still use these internal `action` values (they are not exposed through HTTP):
- `health` (default) → returns `{ "status": "ok", "message": "ok" }`
- `saveJob` → stores `pk`, `sk`, `payload` in DynamoDB
- `getJob` → reads item from DynamoDB
- `bedrockInvoke` → invokes Bedrock with `prompt`

Infrastructure is managed by Terraform in `infra/`:
- Lambda function (`java17`, memory/timeout, env vars)
- DynamoDB table (`pk` + `sk`, on-demand billing)
- IAM role/policy for logs + DynamoDB + Bedrock invoke
- CloudWatch log group
- Private email bucket, 30-day raw-email retention, asynchronous failure queue, and alarms
- API Gateway HTTP API and an opt-in SES rule in an existing receipt rule set

CI/CD is in `.github/workflows/deploy-lambda.yml`:
1. Build/test/package Lambda artifact.
2. Run Terraform `plan`.
3. Optionally run Terraform `apply` and publish Lambda code.

## Repository structure

```text
.
├── .github/workflows/deploy-lambda.yml
├── infra/
│   ├── envs/dev.tfvars
│   ├── envs/prod.tfvars
│   ├── *.tf
├── src/main/java/com/itzitzo/jobsfeed/
│   ├── LambdaHandler.java
│   ├── config/AwsClientsConfig.java
│   └── service/
└── build.gradle
```

## Local prerequisites

- Java 17+
- Gradle 8+
- Terraform 1.10+ (S3 state locking)
- AWS CLI (for manual validation)

## Run locally (build/tests)

```bash
./gradlew clean test lambdaZip
```

This creates a deployment JAR in `build/libs/` and `build/deploy/lambda.zip`, with the JAR under `lib/` for the Lambda Java runtime.

## Terraform usage (manual)

From repo root:

```bash
cd infra
terraform init \
  -backend-config="bucket=itzitzo-jobs-feed-tfstate-607399097622" \
  -backend-config="key=itzitzo-jobs-feed/dev.tfstate" \
  -backend-config="region=eu-west-1"
terraform plan -var-file=envs/dev.tfvars -var="lambda_artifact_path=../build/deploy/lambda.zip"
terraform apply -var-file=envs/dev.tfvars -var="lambda_artifact_path=../build/deploy/lambda.zip"
```

`lambda_artifact_path` points to the zip produced in CI (`build/deploy/lambda.zip`).

Infrastructure checks that do not create AWS resources:

```bash
terraform -chdir=infra validate
terraform -chdir=infra test
```

The Terraform tests use a mocked AWS provider and require the deployment ZIP to have been built first.

## Email-to-RSS configuration

Both environment files select `eu.amazon.nova-micro-v1:0`. Nova Lite can be selected with `eu.amazon.nova-lite-v1:0`; evaluate both on representative fixtures before choosing the production model. Requests use Bedrock Converse, not Titan's `inputText` format.

Confirm the inference profile is available from the deployment region and review its cross-region destinations before sending real email content. IAM is scoped to the selected standard model and profile; custom model/profile ARNs require an explicit `bedrock_resource_arns` list including the underlying models. There is no automatic model fallback.

SES receipt integration is **disabled by default**. Terraform never creates or activates a receipt rule set, changes MX records, or verifies a domain. After reviewing the existing SES routes, domain identity, and MX configuration, opt in for dev with:

```hcl
enable_ses_receipt_rule    = true
ses_receipt_rule_set_name = "your-existing-rule-set"
ses_recipient             = "jobs@your-domain.example"
```

Ensure that rule set is already active in the intended region and earlier rules do not stop delivery before this rule. The new rule stores the email under `incoming/` before invoking Lambda asynchronously and requires TLS. It does not stop subsequent rules.

The initial receipt policy requires spam, virus, and DMARC verdicts to be `PASS`, plus at least one of SPF or DKIM to be `PASS`. Missing, gray, failed, or error verdicts are rejected and follow the asynchronous failure path. Review this strict policy before enabling receipt; forwarded mail may be rejected.

Ingestion accepts raw messages up to 5 MiB and normalized text up to 64 KiB, ignores attachments, and extracts at most 20 jobs per message. Oversize messages, malformed/truncated extraction output, and dependency errors fail explicitly. Zero-job messages are marked processed. Replayed messages cannot duplicate published jobs, although concurrent processing can still incur repeated inference charges before one transaction wins.

The feed is public. Only reviewed job fields are rendered; sender metadata and raw MIME are not included. Review extraction quality and publication suitability before routing private correspondence to this address.

After a dev apply:

```bash
terraform -chdir=infra output -raw feed_url
terraform -chdir=infra output -raw email_bucket_name
terraform -chdir=infra output -raw ingestion_failure_queue_url
```

Send a synthetic job email, confirm its S3 object and DynamoDB feed entries, and fetch the feed URL with `curl`. Replay the same SES event and verify the GUIDs and item count remain unchanged. Also check an empty feed and a failed ingestion before enabling prod.

Failures are retried twice with a one-hour maximum event age, then captured as Lambda destination records in the encrypted SQS queue for up to 14 days. Use the queue output to inspect a message, extract the original SES event from `requestPayload`, and invoke Lambda asynchronously with that event:

```bash
aws lambda invoke \
  --function-name itzitzo-jobs-feed-dev-lambda \
  --invocation-type Event \
  --cli-binary-format raw-in-base64-out \
  --payload file://original-ses-event.json \
  replay-response.json
```

The example requires AWS CLI v2. Do not replay the complete SQS/destination wrapper, and do not attach the queue directly as a Lambda event source. Confirm processing before deleting a failure message. Raw email expires after 30 days, so recovery must happen while the S3 object still exists; processed-message markers do not expire.

Set `alarm_actions` to your SNS topic ARNs to receive notifications. Without those ARNs, alarms record state changes but do not notify operators.

See [the implementation specification](.github/issues/feature_email_to_rss.md) for the complete scope and acceptance checklist. Live email delivery, model comparison, and production routing approval remain deployment checks, not unit-test guarantees.

## Enable GitHub Actions deployment to AWS

Follow these steps once per AWS account/repository.

### 1) Create an IAM role for GitHub OIDC

Create IAM OIDC provider (if not already present):
- Provider URL: `https://token.actions.githubusercontent.com`
- Audience: `sts.amazonaws.com`

Create a role trusted by GitHub Actions, with trust policy like:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::607399097622:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:vriesjo@13521613/itzitzo-jobs-feed@1429390963:ref:refs/heads/main",
            "repo:vriesjo@13521613/itzitzo-jobs-feed@1429390963:ref:refs/tags/*",
            "repo:vriesjo@13521613/itzitzo-jobs-feed@1429390963:environment:production"
          ]
        }
      }
    }
  ]
}
```

The subjects above match this repository's ID-qualified OIDC subject format. When configuring another repository, use its actual token subject rather than assuming the name-only format.

To update the existing role from the checked-in policy, run from the repository root:

```bash
aws iam update-assume-role-policy \
  --role-name github-actions-itzitzo-jobs-feed \
  --policy-document file://infra/aws/github_actions_trust_policy.json
```

Attach permissions that allow Terraform to manage Lambda/IAM/CloudWatch/DynamoDB used by this project.

### 2) Configure repository settings

In GitHub repository settings:

- **Secrets and variables → Actions → Secrets**
  - `AWS_ROLE_ARN`: ARN of the IAM role from step 1

- **Secrets and variables → Actions → Variables**
  - `AWS_REGION`: e.g. `eu-west-1`
  - `TF_STATE_BUCKET`: required S3 backend bucket for Terraform state (`itzitzo-jobs-feed-tfstate-607399097622` in this account)

The state bucket must exist before Terraform runs. Keep it private, enable versioning and encryption, and grant the Actions role access to the state objects and their `.tflock` lock files. Terraform uses separate `itzitzo-jobs-feed/dev.tfstate` and `itzitzo-jobs-feed/prod.tfstate` keys, with S3 state locking. The workflow fails if `TF_STATE_BUCKET` is missing instead of using disposable local state.

If a previous run created resources without persistent state, import those resources into the correct environment's backend before applying again; do not delete them to resolve "already exists" errors.

### 3) Configure protected environment (recommended)

The plan and apply jobs use GitHub environment `production`.
Create environment `production` in repository settings and add required reviewers if you want approval gates before AWS access.

### 4) Trigger deployment

Two trigger modes are supported:

- Push to `main`
  - Runs build + plan + apply/deploy
  - Uses `prod` tfvars by default

- Manual `workflow_dispatch`
  - Select `environment` (`dev` or `prod`)
  - Set `apply=true` to execute apply/deploy
  - If `apply=false`, only build + plan runs

To trigger a dev plan on `main` and print its run ID and URL:

```bash
bash scripts/terraform-plan.sh
```

To trigger a dev apply/deploy on `main` and print its run ID and URL:

```bash
make workflow-deploy
```

`make workflow-deploy` runs `scripts/terraform-apply.sh`. The apply script sets `apply=true` and changes AWS resources. Both scripts run the remote version of `main`, not your local uncommitted changes.

The scripts use your saved GitHub CLI login and ignore `GITHUB_TOKEN` and `GH_TOKEN` for their process. If needed, first log in with `env -u GITHUB_TOKEN -u GH_TOKEN gh auth login`. Never put a personal access token in the scripts.

## Notes

- Keep `vars.AWS_REGION` aligned with your tfvars region.
- Bedrock model IDs are set in:
  - `infra/envs/dev.tfvars`
  - `infra/envs/prod.tfvars`
- Lambda supplies the reserved `AWS_REGION` runtime variable automatically.
- Additional Lambda runtime env vars are provisioned by Terraform:
  - `BEDROCK_MODEL_ID`
  - `DYNAMODB_TABLE_NAME`
  - `EMAIL_BUCKET_NAME` and `EMAIL_OBJECT_PREFIX`
  - `FEED_TITLE`, `FEED_DESCRIPTION`, `FEED_WEBSITE_URL`, and `FEED_PUBLIC_URL`
