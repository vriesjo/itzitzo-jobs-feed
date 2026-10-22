# itzitzo-jobs-feed

Java 17 AWS Lambda project (Gradle) prepared for:
- AWS Bedrock calls
- DynamoDB persistence
- Terraform provisioning
- GitHub Actions deployment via AWS OIDC

## How it works

The Lambda handler is `com.itzitzo.jobsfeed.LambdaHandler::handleRequest`.  
It routes by `action` in the input payload:
- `health` (default) → returns `{ "status": "ok", "message": "ok" }`
- `saveJob` → stores `pk`, `sk`, `payload` in DynamoDB
- `getJob` → reads item from DynamoDB
- `bedrockInvoke` → invokes Bedrock with `prompt`

Infrastructure is managed by Terraform in `infra/`:
- Lambda function (`java17`, memory/timeout, env vars)
- DynamoDB table (`pk` + `sk`, on-demand billing)
- IAM role/policy for logs + DynamoDB + Bedrock invoke
- CloudWatch log group

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
gradle clean test shadowJar
```

This creates a deployment JAR in `build/libs/`.

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
bash scripts/terraform-apply.sh
```

The apply script sets `apply=true` and changes AWS resources. Both scripts run the remote version of `main`, not your local uncommitted changes.

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
