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
- Terraform 1.6+
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
terraform init
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
          "token.actions.githubusercontent.com:sub": "repo:vriesjo/itzitzo-jobs-feed:*"
        }
      }
    }
  ]
}
```

Attach permissions that allow Terraform to manage Lambda/IAM/CloudWatch/DynamoDB used by this project.

### 2) Configure repository settings

In GitHub repository settings:

- **Secrets and variables → Actions → Secrets**
  - `AWS_ROLE_ARN`: ARN of the IAM role from step 1

- **Secrets and variables → Actions → Variables**
  - `AWS_REGION`: optional, e.g. `eu-west-1`; defaults to `aws_region` in the selected `infra/envs/<environment>.tfvars`
  - `TF_STATE_BUCKET`: optional S3 backend bucket for Terraform state

### 3) Configure protected environment (recommended)

The apply job uses GitHub environment `production`.  
Create environment `production` in repository settings and add required reviewers if you want approval gates before apply.

### 4) Trigger deployment

Two trigger modes are supported:

- Push to `main`
  - Runs build + plan + apply/deploy
  - Uses `prod` tfvars by default

- Manual `workflow_dispatch`
  - Select `environment` (`dev` or `prod`)
  - Set `apply=true` to execute apply/deploy
  - If `apply=false`, only build + plan runs

## Notes

- If set, keep `vars.AWS_REGION` aligned with your tfvars region. Both Terraform jobs resolve the region before configuring AWS credentials.
- Bedrock model IDs are set in:
  - `infra/envs/dev.tfvars`
  - `infra/envs/prod.tfvars`
- Lambda runtime env vars are provisioned by Terraform:
  - `AWS_REGION`
  - `BEDROCK_MODEL_ID`
  - `DYNAMODB_TABLE_NAME`
