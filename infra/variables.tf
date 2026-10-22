variable "app_name" {
  type        = string
  description = "Application name prefix."
  default     = "itzitzo-jobs-feed"
}

variable "environment" {
  type        = string
  description = "Deployment environment (dev, prod)."
}

variable "aws_region" {
  type        = string
  description = "AWS deployment region."
}

variable "bedrock_model_id" {
  type        = string
  description = "Bedrock model ID used by Lambda."
}

variable "lambda_artifact_path" {
  type        = string
  description = "Path to the built Lambda deployment zip."
  default     = "../build/deploy/lambda.zip"
}

variable "lambda_memory_size" {
  type        = number
  description = "Lambda memory in MB."
  default     = 1024
}

variable "lambda_timeout_seconds" {
  type        = number
  description = "Lambda timeout in seconds."
  default     = 30
}

variable "log_retention_days" {
  type        = number
  description = "CloudWatch log retention period."
  default     = 14
}
