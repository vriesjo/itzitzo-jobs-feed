# Feature: AWS OIDC setup analysis for GitHub Actions

This document analyzes how to securely configure **GitHub Actions → AWS** authentication using **OIDC** (OpenID Connect), for repository `vriesjo/itzitzo-jobs-feed`.

> Note: file name uses `oicd` to match requested naming, while the protocol is **OIDC**.

---

## 1) Why OIDC is the right approach

Using OIDC avoids long-lived AWS access keys in GitHub secrets.  
GitHub issues short-lived identity tokens at job runtime, and AWS STS exchanges them for temporary credentials.

**Security benefits**
- No static AWS key rotation burden in GitHub.
- Short-lived credentials scoped to a single workflow/job execution.
- Trust policy can restrict by repository, branch, tag, or environment.

---

## 2) Trust model and security boundaries

There are three layers:

1. **AWS OIDC provider** (`token.actions.githubusercontent.com`) in IAM.
2. **IAM role trust policy** that defines which GitHub workloads may assume the role.
3. **IAM permission policy** attached to the role that defines what AWS actions are allowed.

The most critical control is the role trust policy condition on `sub`:
- `repo:<owner>/<repo>:ref:refs/heads/main` for main branch
- `repo:<owner>/<repo>:ref:refs/tags/*` for releases/tags
- `repo:<owner>/<repo>:environment:production` for protected environment jobs

This repository’s workflow uses `environment: production` for apply/deploy, so environment-based subject should be included if that job must assume the role.

---

## 3) Required AWS setup (analysis)

### 3.1 Create OIDC provider (once per AWS account)

- URL: `https://token.actions.githubusercontent.com`
- Audience (client ID): `sts.amazonaws.com`

### 3.2 Create dedicated IAM role for Actions

Role name suggestion:
- `github-actions-itzitzo-jobs-feed`

Trust policy should:
- Allow `sts:AssumeRoleWithWebIdentity`.
- Restrict `aud` to `sts.amazonaws.com`.
- Restrict `sub` to this repository and approved refs/environments.

### 3.3 Attach least-privilege permissions

Required surfaces for this project:
- Terraform state backend (S3 and DynamoDB lock table if used)
- Lambda management
- IAM role/policy management (for Lambda execution role)
- DynamoDB table management
- CloudWatch logs management
- Optional read permissions for Terraform data sources

For first enablement, broader permissions may accelerate setup, but should be tightened after first successful deployment.

---

## 4) Required GitHub setup (analysis)

Repository settings required:

- **Secret**
  - `AWS_ROLE_ARN`: ARN of IAM role assumed by workflow.

- **Variables**
  - `AWS_REGION`: deployment region (must align with Terraform tfvars).
  - `TF_STATE_BUCKET` (optional): remote Terraform state bucket.

- **Environment**
  - `production` (recommended with reviewers/approval gates), because apply job is bound to this environment in workflow.

Workflow permissions must include:
- `id-token: write` (required for OIDC token minting)
- `contents: read`

---

## 5) Threats and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Broad `sub` condition (wildcard repo access) | Unauthorized repo/workflow could assume role | Scope `sub` to `repo:vriesjo/itzitzo-jobs-feed:*` or narrower |
| No environment gate for apply | Unreviewed infra changes | Use `production` environment with required reviewers |
| Excessive IAM permissions | Privilege escalation or accidental destructive change | Reduce to least privilege after initial bootstrap |
| Shared role across many repos | Blast radius across projects | Use per-repo role |
| Local Terraform state in CI | State drift/loss | Use S3 backend (+ lock table) via `TF_STATE_BUCKET` |

---

## 6) Validation checklist

1. Run workflow manually with `apply=false`.
2. Confirm `configure-aws-credentials` step succeeds (OIDC exchange works).
3. Confirm Terraform `init` and `plan` succeed.
4. Run with `apply=true` in protected environment.
5. Confirm Lambda exists and code is updated.
6. Confirm CloudWatch log group and DynamoDB table created by Terraform.

---

## 7) Recommended trust policy baseline

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
            "repo:vriesjo/itzitzo-jobs-feed:ref:refs/heads/main",
            "repo:vriesjo/itzitzo-jobs-feed:ref:refs/tags/*",
            "repo:vriesjo/itzitzo-jobs-feed:environment:production"
          ]
        }
      }
    }
  ]
}
```

---

## 8) Decision summary

For this project, the preferred setup is:
- OIDC authentication with a **dedicated per-repo IAM role**
- Strict trust policy conditions for branch/tag/environment
- Protected `production` environment for apply/deploy
- Terraform-managed infrastructure and state backend in AWS

This aligns with the current workflow design and minimizes long-lived credential risk while preserving deployment automation.
