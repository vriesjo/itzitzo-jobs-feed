resource "aws_sqs_queue" "ingestion_failures" {
  name                      = "${local.name_prefix}-ingestion-failures"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
  tags                      = local.tags
}

resource "aws_lambda_function_event_invoke_config" "ingestion" {
  function_name                = aws_lambda_function.app.function_name
  maximum_event_age_in_seconds = 3600
  maximum_retry_attempts       = 2

  destination_config {
    on_failure {
      destination = aws_sqs_queue.ingestion_failures.arn
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "ingestion" {
  for_each = toset(["Errors", "DestinationDeliveryFailures", "AsyncEventsDropped"])

  alarm_name          = "${local.name_prefix}-${each.value}"
  alarm_description   = "Investigate Lambda ingestion or asynchronous delivery failures."
  namespace           = "AWS/Lambda"
  metric_name         = each.value
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  dimensions = {
    FunctionName = aws_lambda_function.app.function_name
  }
  tags = local.tags
}
