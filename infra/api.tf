resource "aws_apigatewayv2_api" "feed" {
  name          = "${local.name_prefix}-feed"
  protocol_type = "HTTP"
  tags          = local.tags
}

resource "aws_apigatewayv2_integration" "feed" {
  api_id                 = aws_apigatewayv2_api.feed.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.app.invoke_arn
  payload_format_version = "2.0"
  timeout_milliseconds   = 15000
}

resource "aws_apigatewayv2_route" "feed" {
  api_id = aws_apigatewayv2_api.feed.id
  # Let the handler return 405 for unsupported methods rather than the gateway's default 404.
  route_key = "ANY /feed.xml"
  target    = "integrations/${aws_apigatewayv2_integration.feed.id}"
}

resource "aws_apigatewayv2_route" "contact" {
  api_id = aws_apigatewayv2_api.feed.id
  # Let the handler enforce method/validation and consistent error responses.
  route_key = "ANY /contact"
  target    = "integrations/${aws_apigatewayv2_integration.feed.id}"
}

resource "aws_apigatewayv2_stage" "feed" {
  api_id      = aws_apigatewayv2_api.feed.id
  name        = "$default"
  auto_deploy = true
  tags        = local.tags

  default_route_settings {
    throttling_burst_limit = 20
    throttling_rate_limit  = 10
  }
}

resource "aws_lambda_permission" "feed" {
  statement_id  = "AllowFeedApi"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.app.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.feed.execution_arn}/*/*/feed.xml"
}

resource "aws_lambda_permission" "contact" {
  statement_id  = "AllowContactApi"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.app.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.feed.execution_arn}/*/*/contact"
}

resource "aws_cloudwatch_metric_alarm" "api_errors" {
  for_each = {
    "4XXError" = var.api_alarm_4xx_threshold
    "5XXError" = var.api_alarm_5xx_threshold
  }

  alarm_name          = "${local.name_prefix}-api-${each.key}"
  alarm_description   = "Investigate API Gateway error spikes."
  namespace           = "AWS/ApiGateway"
  metric_name         = each.key
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = each.value
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  dimensions = {
    ApiId = aws_apigatewayv2_api.feed.id
    Stage = aws_apigatewayv2_stage.feed.name
  }
  tags = local.tags
}

resource "aws_cloudwatch_metric_alarm" "api_request_count" {
  alarm_name          = "${local.name_prefix}-api-count"
  alarm_description   = "Investigate API Gateway request spikes."
  namespace           = "AWS/ApiGateway"
  metric_name         = "Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.api_alarm_count_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  dimensions = {
    ApiId = aws_apigatewayv2_api.feed.id
    Stage = aws_apigatewayv2_stage.feed.name
  }
  tags = local.tags
}

resource "aws_cloudwatch_metric_alarm" "api_throttles" {
  alarm_name          = "${local.name_prefix}-api-throttles"
  alarm_description   = "Investigate API Gateway throttle spikes."
  namespace           = "AWS/ApiGateway"
  metric_name         = "Throttles"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.api_alarm_throttle_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  dimensions = {
    ApiId = aws_apigatewayv2_api.feed.id
    Stage = aws_apigatewayv2_stage.feed.name
  }
  tags = local.tags
}
