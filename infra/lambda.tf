resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.lambda_function_name}"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

resource "aws_lambda_function" "app" {
  function_name = local.lambda_function_name
  role          = aws_iam_role.lambda_exec.arn
  runtime       = "java17"
  handler       = "com.itzitzo.jobsfeed.adapter.inbound.LambdaHandler::handleRequest"

  filename         = var.lambda_artifact_path
  source_code_hash = filebase64sha256(var.lambda_artifact_path)

  memory_size = var.lambda_memory_size
  timeout     = var.lambda_timeout_seconds

  environment {
    variables = {
      BEDROCK_MODEL_ID    = var.bedrock_model_id
      DYNAMODB_TABLE_NAME = aws_dynamodb_table.jobs.name
      EMAIL_BUCKET_NAME   = aws_s3_bucket.email.id
      EMAIL_OBJECT_PREFIX = var.email_object_prefix
      FEED_TITLE          = var.feed_title
      FEED_DESCRIPTION    = var.feed_description
      FEED_PUBLIC_URL     = local.feed_url
      FEED_WEBSITE_URL    = var.feed_website_url == "" ? local.feed_url : var.feed_website_url
      CONTACT_ALLOWED_EMAIL = var.contact_allowed_email
      CONTACT_TOKEN         = var.contact_token
    }
  }

  tags = local.tags

  depends_on = [
    aws_cloudwatch_log_group.lambda,
    aws_iam_role_policy.lambda_policy
  ]
}
