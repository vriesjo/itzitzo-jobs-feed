resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.lambda_function_name}"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

resource "aws_lambda_function" "app" {
  function_name = local.lambda_function_name
  role          = aws_iam_role.lambda_exec.arn
  runtime       = "java17"
  handler       = "com.itzitzo.jobsfeed.LambdaHandler::handleRequest"

  filename         = var.lambda_artifact_path
  source_code_hash = filebase64sha256(var.lambda_artifact_path)

  memory_size = var.lambda_memory_size
  timeout     = var.lambda_timeout_seconds

  environment {
    variables = {
      BEDROCK_MODEL_ID    = var.bedrock_model_id
      DYNAMODB_TABLE_NAME = aws_dynamodb_table.jobs.name
    }
  }

  tags = local.tags

  depends_on = [
    aws_cloudwatch_log_group.lambda
  ]
}
