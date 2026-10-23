locals {
  name_prefix          = "${var.app_name}-${var.environment}"
  lambda_function_name = "${local.name_prefix}-lambda"
  dynamodb_table_name  = "${local.name_prefix}-jobs"
  ses_rule_name        = "${local.name_prefix}-ingestion"
  ses_rule_arn         = "arn:${data.aws_partition.current.partition}:ses:${var.aws_region}:${data.aws_caller_identity.current.account_id}:receipt-rule-set/${var.ses_receipt_rule_set_name}:receipt-rule/${local.ses_rule_name}"
  feed_url             = "${aws_apigatewayv2_api.feed.api_endpoint}/feed.xml"
  nova_model_id        = replace(var.bedrock_model_id, "/^(eu|us|apac|global)\\./", "")
  bedrock_resources = length(var.bedrock_resource_arns) > 0 ? var.bedrock_resource_arns : concat(
    ["arn:${data.aws_partition.current.partition}:bedrock:*::foundation-model/${local.nova_model_id}"],
    startswith(var.bedrock_model_id, "amazon.") ? [] : [
      "arn:${data.aws_partition.current.partition}:bedrock:${var.aws_region}:${data.aws_caller_identity.current.account_id}:inference-profile/${var.bedrock_model_id}"
    ]
  )
  tags = {
    Application = var.app_name
    Environment = var.environment
    ManagedBy   = "terraform"
  }

}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}
