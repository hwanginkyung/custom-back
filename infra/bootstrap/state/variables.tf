variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "Project name"
  type        = string
  default     = "cariv-customs"
}

variable "env_name" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "state_bucket_name" {
  description = "Terraform state bucket name (비우면 자동 생성 규칙 사용)"
  type        = string
  default     = ""
}

variable "lock_table_name" {
  description = "Terraform state lock table name (비우면 자동 생성 규칙 사용)"
  type        = string
  default     = ""
}

variable "enable_dynamodb_lock_table" {
  description = "DynamoDB lock table 생성 여부 (권한이 있을 때만 true)"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Additional tags"
  type        = map(string)
  default     = {}
}
