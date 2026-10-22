#!/usr/bin/env bash
set -euo pipefail

unset GITHUB_TOKEN GH_TOKEN

if ! command -v gh >/dev/null 2>&1; then
  echo "Error: GitHub CLI (gh) is required." >&2
  exit 1
fi

repo="vriesjo/itzitzo-jobs-feed"
output="$(
  gh workflow run deploy-lambda.yml \
    --repo "$repo" \
    --ref main \
    -f environment=dev \
    -F apply=true
)"

if [[ "$output" =~ https://github\.com/vriesjo/itzitzo-jobs-feed/actions/runs/([0-9]+) ]]; then
  run_id="${BASH_REMATCH[1]}"
  echo "Workflow run ID: $run_id"
  echo "View: https://github.com/$repo/actions/runs/$run_id"
else
  echo "Workflow dispatched, but gh did not return a run URL." >&2
  echo "View runs: https://github.com/$repo/actions/workflows/deploy-lambda.yml" >&2
  echo "Update GitHub CLI to a version that returns the created run URL." >&2
  exit 1
fi
