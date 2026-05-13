variable "enabled" {
  type = bool
}

variable "name" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "ami_id" {
  type = string
}

variable "instance_type" {
  type = string
}

variable "key_name" {
  type = string
}

variable "app_port" {
  type = number

  validation {
    condition     = var.app_port >= 1 && var.app_port <= 65535
    error_message = "app_port는 1~65535 범위여야 합니다."
  }
}

variable "enable_eip" {
  type = bool
}

variable "manage_security_group" {
  type = bool
}

variable "security_group_ids" {
  type    = list(string)
  default = []

  validation {
    condition = (
      (var.manage_security_group && length(var.security_group_ids) == 0) ||
      (!var.manage_security_group && length(var.security_group_ids) > 0)
    )
    error_message = "manage_security_group=true면 security_group_ids는 비워두고, false면 1개 이상 입력해야 합니다."
  }
}

variable "ingress_cidrs_ssh" {
  type = list(string)
}

variable "ingress_cidrs_app" {
  type = list(string)
}

variable "extra_ingress_tcp_ports" {
  type    = list(number)
  default = []

  validation {
    condition = alltrue([
      for p in var.extra_ingress_tcp_ports : p >= 1 && p <= 65535
    ])
    error_message = "extra_ingress_tcp_ports는 1~65535 범위 포트만 허용합니다."
  }
}

variable "root_volume_size" {
  type    = number
  default = 30
}

variable "root_volume_encrypted" {
  type    = bool
  default = true
}

variable "tags" {
  type = map(string)
}
