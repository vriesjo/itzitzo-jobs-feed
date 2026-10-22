locals {
  name_prefix          = "${var.app_name}-${var.environment}"
  lambda_function_name = "${local.name_prefix}-lambda"
  dynamodb_table_name  = "${local.name_prefix}-jobs"
  tags = {
    Application = var.app_name
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}
