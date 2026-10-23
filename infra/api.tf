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
