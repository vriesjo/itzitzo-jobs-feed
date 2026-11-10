---
todos:
  - id: "github-secret"
    content: "Document that CONTACT_TOKEN must exist on the production GitHub environment before apply"
    status: completed
  - id: "strip-tfvars"
    content: "Remove contact_token from dev and prod tfvars"
    status: completed
  - id: "workflow"
    content: "Pass TF_VAR_contact_token into plan and apply and fail closed when it is empty"
    status: completed
  - id: "docs"
    content: "Point architecture and README rotation instructions at the Actions secret instead of tfvars"
    status: completed
isProject: true
---
# Uncommit the contact token

The same live `contact_token` was committed in `infra/envs/dev.tfvars` and `infra/envs/prod.tfvars`. `infra/variables.tf` marks `contact_token` sensitive and defaults it to empty. `infra/lambda.tf` copies that variable to `CONTACT_TOKEN`. The next apply that omits the variable would store an empty token and make `POST /contact` return 503, so the workflow requires a replacement secret before it can apply.

Rotation is the actual fix. The current value remains in git history on `main`, so deleting the line does not unpublish it. After Lambda is updated, requests that still send the old token get HTTP 403. This change does not rewrite history or force-push `main`.

## Before the code change is applied

Create a new token locally and store it as the Actions secret `CONTACT_TOKEN` on the GitHub environment `production`. Both the plan and apply jobs use that environment, including a dev apply. Do not commit the new value. Dev and prod share one secret.

## Code

- `contact_token` is removed from both tfvars files.
- `.github/workflows/deploy-lambda.yml` sets `TF_VAR_contact_token` from `secrets.CONTACT_TOKEN` on the plan job and the apply job. Each of those steps fails before Terraform when the variable is empty.
- Architecture and README say the token comes from `TF_VAR_contact_token` or the `CONTACT_TOKEN` Actions secret, not from tfvars.

## After merge

Pushing to `main` applies prod with the new secret and replaces the Lambda environment. Confirm `POST /contact` with the new token returns 202, and a request with the old token returns 403.
