resource "aws_s3_bucket" "email" {
  bucket = "${local.name_prefix}-email-${data.aws_caller_identity.current.account_id}"
  tags   = local.tags
}

resource "aws_s3_bucket_public_access_block" "email" {
  bucket                  = aws_s3_bucket.email.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "email" {
  bucket = aws_s3_bucket.email.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "email" {
  bucket = aws_s3_bucket.email.id
  rule {
    id     = "expire-raw-email"
    status = "Enabled"
    filter {
      prefix = var.email_object_prefix
    }
    expiration {
      days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "ses_email" {
  count = var.enable_ses_receipt_rule ? 1 : 0
  statement {
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.email.arn}/${var.email_object_prefix}*"]
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
    condition {
      test     = "ArnEquals"
      variable = "AWS:SourceArn"
      values   = [local.ses_rule_arn]
    }
  }
}

resource "aws_s3_bucket_policy" "ses_email" {
  count  = var.enable_ses_receipt_rule ? 1 : 0
  bucket = aws_s3_bucket.email.id
  policy = data.aws_iam_policy_document.ses_email[0].json

  depends_on = [aws_s3_bucket_public_access_block.email]
}

resource "aws_lambda_permission" "ses" {
  count          = var.enable_ses_receipt_rule ? 1 : 0
  statement_id   = "AllowSesReceipt"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.app.function_name
  principal      = "ses.amazonaws.com"
  source_account = data.aws_caller_identity.current.account_id
  source_arn     = local.ses_rule_arn
}

resource "aws_ses_receipt_rule" "ingestion" {
  count         = var.enable_ses_receipt_rule ? 1 : 0
  name          = local.ses_rule_name
  rule_set_name = var.ses_receipt_rule_set_name
  recipients    = [var.ses_recipient]
  enabled       = true
  scan_enabled  = true
  tls_policy    = "Require"

  s3_action {
    position          = 1
    bucket_name       = aws_s3_bucket.email.id
    object_key_prefix = var.email_object_prefix
  }

  lambda_action {
    position        = 2
    function_arn    = aws_lambda_function.app.arn
    invocation_type = "Event"
  }

  lifecycle {
    precondition {
      condition     = trimspace(var.ses_receipt_rule_set_name) != "" && can(regex("^[^@ ]+@[^@ ]+\\.[^@ ]+$", var.ses_recipient))
      error_message = "Enabling SES requires a reviewed existing rule set and one receiving email address."
    }
  }

  depends_on = [
    aws_s3_bucket_policy.ses_email,
    aws_s3_bucket_server_side_encryption_configuration.email,
    aws_lambda_permission.ses
  ]
}
