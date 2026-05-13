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

variable "enabled" {
  description = "리소스 생성/관리 활성화 여부 (초기 false 권장)"
  type        = bool
  default     = false
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "public_subnet_id" {
  description = "EC2 subnet ID"
  type        = string
}

variable "ami_id" {
  description = "EC2 AMI ID"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.small"
}

variable "key_pair_name" {
  description = "EC2 key pair name"
  type        = string
}

variable "app_port" {
  description = "App inbound port"
  type        = number
  default     = 8080
}

variable "enable_eip" {
  description = "EIP 연결 여부"
  type        = bool
  default     = false
}

variable "manage_security_group" {
  description = "SG를 Terraform에서 직접 관리할지 여부"
  type        = bool
  default     = true
}

variable "security_group_ids" {
  description = "기존 SG를 재사용할 때 입력 (manage_security_group=false일 때 필요, 여러 개 가능)"
  type        = list(string)
  default     = []
}

variable "ingress_cidrs_ssh" {
  description = "SSH 허용 CIDR"
  type        = list(string)
  default     = []
}

variable "ingress_cidrs_app" {
  description = "App 포트 허용 CIDR"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "extra_ingress_tcp_ports" {
  description = "추가로 열 포트 목록 (예: 80, 443)"
  type        = list(number)
  default     = []
}

variable "enable_cloudwatch_alarms" {
  description = "Customs EC2 CloudWatch 알람 생성 여부"
  type        = bool
  default     = true
}

variable "alarm_actions" {
  description = "ALARM 전환 시 실행할 액션 ARN 목록(SNS 등)"
  type        = list(string)
  default     = []
}

variable "ok_actions" {
  description = "OK 전환 시 실행할 액션 ARN 목록(SNS 등)"
  type        = list(string)
  default     = []
}

variable "cpu_high_threshold_percent" {
  description = "CPUUtilization high alarm threshold (%)"
  type        = number
  default     = 80
}

variable "cpu_high_evaluation_periods" {
  description = "CPU high alarm evaluation periods"
  type        = number
  default     = 2
}

variable "status_check_failed_threshold" {
  description = "StatusCheckFailed alarm threshold"
  type        = number
  default     = 1
}

variable "status_check_failed_evaluation_periods" {
  description = "StatusCheckFailed alarm evaluation periods"
  type        = number
  default     = 2
}

variable "network_out_high_threshold_bytes" {
  description = "NetworkOut high alarm threshold (bytes / 5 min)"
  type        = number
  default     = 500000000
}

variable "network_out_high_evaluation_periods" {
  description = "NetworkOut high alarm evaluation periods"
  type        = number
  default     = 2
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}

variable "root_volume_size" {
  description = "Root EBS volume size (GiB)"
  type        = number
  default     = 30
}

variable "root_volume_encrypted" {
  description = "Root EBS encryption"
  type        = bool
  default     = true
}
