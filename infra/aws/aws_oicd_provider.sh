#!/usr/bin/env bash
set -euo pipefail

OIDC_URL="token.actions.githubusercontent.com"
AUDIENCE="sts.amazonaws.com"
THUMBPRINT="6938fd4d98bab03faadb97b34396831e3780aea1"

existing_arn="$(
  aws iam list-open-id-connect-providers \
    --query "OpenIDConnectProviderList[?contains(Arn, '${OIDC_URL}')].Arn | [0]" \
    --output text
)"

if [[ "${existing_arn}" != "None" && -n "${existing_arn}" ]]; then
  echo "OIDC provider already exists: ${existing_arn}"
  aws iam get-open-id-connect-provider --open-id-connect-provider-arn "${existing_arn}" >/dev/null
  echo "OIDC provider verification succeeded."
  exit 0
fi

created_arn="$(
  aws iam create-open-id-connect-provider \
    --url "https://${OIDC_URL}" \
    --thumbprint-list "${THUMBPRINT}" \
    --client-id-list "${AUDIENCE}" \
    --query "OpenIDConnectProviderArn" \
    --output text
)"

echo "OIDC provider created: ${created_arn}"
