output "lambda_function_name" {
  value       = aws_lambda_function.app.function_name
  description = "Provisioned Lambda function name."
}

output "lambda_function_arn" {
  value       = aws_lambda_function.app.arn
  description = "Provisioned Lambda function ARN."
}

output "dynamodb_table_name" {
  value       = aws_dynamodb_table.jobs.name
  description = "Provisioned DynamoDB table name."
}

output "lambda_execution_role_arn" {
  value       = aws_iam_role.lambda_exec.arn
  description = "IAM role ARN used by Lambda."
}

output "feed_url" {
  value       = local.feed_url
  description = "Public RSS endpoint."
}

output "email_bucket_name" {
  value       = aws_s3_bucket.email.id
  description = "Private raw-email storage bucket."
}

output "ingestion_failure_queue_url" {
  value       = aws_sqs_queue.ingestion_failures.url
  description = "Failure destination containing wrapped asynchronous invocation records."
}
