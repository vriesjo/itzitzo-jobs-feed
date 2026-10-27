curl -fsS -o /tmp/feed.xml -w "%{http_code}\n" https://lrw4ixxdpk.execute-api.eu-west-1.amazonaws.com/feed.xml && head -n 5 /tmp/feed.xml

```terraform
terraform -chdir=infra init -reconfigure \
-backend-config="bucket=itzitzo-jobs-feed-tfstate-607399097622" \
-backend-config="key=itzitzo-jobs-feed/prod.tfstate" \
-backend-config="region=eu-west-1"
```

```terraform
terraform -chdir=infra output
terraform -chdir=infra output -raw feed_url
terraform -chdir=infra output -raw email_bucket_name
```
```
aws ses describe-active-receipt-rule-set --region eu-west-1 --output json | jq -r '.Metadata.Name as $set | .Rules[]? | [$set, .Name, (.Recipients|join(",")),
(.Enabled|tostring)] | @tsv'
```

Right now: there is no SES recipient wired to this jobs-feed Lambda yet.

Your Terraform state has no  aws_ses_receipt_rule.ingestion , and the active SES rule ( INBOUND_MAIL ) currently routes mail to another 
bucket/Lambda ( itzitzo-ses-emails-forwarder-1972  /  ItzitzoSESForwardToGmail ), 
not  itzitzo-jobs-feed-prod-* .

To proceed, set and apply these in  infra/envs/prod.tfvars :

```
enable_ses_receipt_rule    = true
ses_receipt_rule_set_name  = "INBOUND_MAIL"   # or your intended existing set
ses_recipient              = "jobs@itzitzo.nl" # address you will send to
```

How to check the ses rules:

```
set -e
printf "## Terraform SES rule in state\n"
if terraform -chdir=infra state list | rg -q '^aws_ses_receipt_rule\.ingestion\[0\]$'; then
  terraform -chdir=infra state show 'aws_ses_receipt_rule.ingestion[0]' | rg 'rule_set_name|recipients|enabled|tls_policy|scan_enabled' -n || true
else
  echo "(none)"
fi

printf "\n## Active SES rules targeting jobs-feed resources\n"
aws ses describe-active-receipt-rule-set --region eu-west-1 --output json | jq -r '
  .Metadata.Name as $set |
  (.Rules // [])[] |
  {name: .Name, recipients: (.Recipients // []), s3: ([.Actions[]? | select(.S3Action!=null) | .S3Action.BucketName] | join(",")), lambda: ([.Actions[]? | select(.LambdaAction!=null) | .LambdaAction.FunctionArn] | join(",")), enabled: .Enabled} |
  select((.s3|contains("itzitzo-jobs-feed")) or (.lambda|contains("itzitzo-jobs-feed")) or (.recipients|join(",")|contains("jobs@"))) |
  "RULESET\t" + $set,
  "RULE\t" + .name,
  "RECIPIENTS\t" + (.recipients|join(",")),
  "ENABLED\t" + (.enabled|tostring),
  "S3\t" + .s3,
  "LAMBDA\t" + .lambda,
  "---"'

```

then run your deploy/apply. After that, send the test email to  jobs@itzitzo.nl  (or whatever you set in  ses_recipient ).

Now that  /feed.xml  is live, do this in order: 

1) send one synthetic job email to your SES recipient, 
2) confirm raw email lands in the S3 email bucket, 
3) confirm one new RSS item appears in feed,
4) replay the same SES event and verify no duplicate feed item,
5) check the ingestion-failure SQS queue is empty.

### Send one synthetic job email to your SES recipient,

Use this exact test content:

• Subject:  TEST JOB – Senior Java Developer – Remote 
• Body:

Company: Example Labs
Title: Senior Java Developer
Location: Remote (EU)
Apply: https://example.com/jobs/senior-java-dev
Description: Build and maintain Java 17 backend services on AWS.

If you’re not sure which address is configured: it comes from Terraform var  ses_recipient  (and works only if  enable_ses_receipt_rule=true  was applied). 
After sending, validate with:

aws s3 ls s3://$(terraform -chdir=infra output -raw email_bucket_name)/incoming/ --recursive | tail -n 5
curl -fsS "$(terraform -chdir=infra output -raw feed_url)" | rg -n "Senior Java Developer|Example Labs"
