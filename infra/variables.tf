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
  default     = 180
}

variable "log_retention_days" {
  type        = number
  description = "CloudWatch log retention period."
  default     = 14
}

variable "email_object_prefix" {
  type        = string
  description = "Prefix used by SES to store raw messages."
  default     = "incoming/"

  validation {
    condition     = can(regex("^[A-Za-z0-9/_-]+/$", var.email_object_prefix))
    error_message = "Use a nonempty S3 prefix ending in /."
  }
}

variable "enable_ses_receipt_rule" {
  type        = bool
  description = "Opt in to adding a rule to an existing SES receipt rule set; never activates or replaces the rule set."
  default     = false
}

variable "ses_receipt_rule_set_name" {
  type        = string
  description = "Existing SES rule set reviewed for this receiving address."
  default     = ""
}

variable "ses_recipient" {
  type        = string
  description = "Single receiving email address; identity and MX routing must already be configured."
  default     = ""
}

variable "feed_title" {
  type    = string
  default = "Itzitzo Jobs"
}

variable "feed_description" {
  type    = string
  default = "Recent job vacancies received by email."
}

variable "feed_website_url" {
  type        = string
  description = "Optional channel website URL; defaults to the generated feed URL."
  default     = ""

  validation {
    condition     = var.feed_website_url == "" || can(regex("^https?://[^/ ]+", var.feed_website_url))
    error_message = "The website URL must be an absolute HTTP or HTTPS URL."
  }
}

variable "bedrock_resource_arns" {
  type        = list(string)
  description = "Explicit model/profile resource ARNs, required for custom application inference profiles. Empty uses the configured standard Nova model/profile."
  default     = []
}

variable "alarm_actions" {
  type        = list(string)
  description = "Optional SNS topic ARNs for ingestion and destination failure alarms."
  default     = []
}
