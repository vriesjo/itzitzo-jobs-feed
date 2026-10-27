mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
  mock_resource "aws_apigatewayv2_api" {
    defaults = {
      api_endpoint  = "https://example.execute-api.eu-west-1.amazonaws.com"
      execution_arn = "arn:aws:execute-api:eu-west-1:123456789012:example"
    }
  }
}

variables {
  environment      = "dev"
  aws_region       = "eu-west-1"
  bedrock_model_id = "eu.amazon.nova-micro-v1:0"
}

run "safe_defaults" {
  command = plan

  assert {
    condition     = length(aws_ses_receipt_rule.ingestion) == 0 && length(aws_lambda_permission.ses) == 0
    error_message = "SES receipt integration must remain opt-in."
  }
  assert {
    condition     = aws_apigatewayv2_integration.feed.payload_format_version == "2.0"
    error_message = "The handler requires API Gateway HTTP API payload version 2."
  }
  assert {
    condition     = aws_apigatewayv2_route.contact.route_key == "ANY /contact" && aws_lambda_permission.contact.statement_id == "AllowContactApi"
    error_message = "Contact endpoint route and invoke permission must be provisioned."
  }
  assert {
    condition     = aws_lambda_function.app.environment[0].variables["BEDROCK_MODEL_ID"] == "eu.amazon.nova-micro-v1:0" && aws_lambda_function.app.environment[0].variables["CONTACT_ALLOWED_EMAIL"] == "vriesjo@gmail.com" && !contains(keys(aws_lambda_function.app.environment[0].variables), "AWS_REGION")
    error_message = "Configure Nova without overriding Lambda's reserved region variable."
  }
  assert {
    condition     = aws_lambda_function_event_invoke_config.ingestion.maximum_retry_attempts == 2
    error_message = "Asynchronous ingestion must use bounded retries."
  }
}

run "enable_reviewed_rule" {
  command = plan
  variables {
    enable_ses_receipt_rule   = true
    ses_receipt_rule_set_name = "existing-rule-set"
    ses_recipient             = "jobs@example.com"
  }
  assert {
    condition     = aws_ses_receipt_rule.ingestion[0].rule_set_name == "existing-rule-set" && one(aws_ses_receipt_rule.ingestion[0].s3_action).position == 1 && one(aws_ses_receipt_rule.ingestion[0].lambda_action).position == 2
    error_message = "Write the email to S3 before invoking Lambda in the selected existing rule set."
  }
}

run "reject_missing_receipt_configuration" {
  command = plan
  variables {
    enable_ses_receipt_rule = true
  }
  expect_failures = [aws_ses_receipt_rule.ingestion]
}

run "reject_unsupported_model_without_iam_overrides" {
  command = plan
  variables {
    bedrock_model_id = "unconfigured-custom-profile"
  }
  expect_failures = [aws_iam_role_policy.lambda_policy]
}
