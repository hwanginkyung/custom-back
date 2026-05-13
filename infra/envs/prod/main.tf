provider "aws" {
  region = var.aws_region
}

locals {
  common_tags = merge(
    {
      Project     = var.project_name
      Environment = var.env_name
      ManagedBy   = "terraform"
    },
    var.tags
  )

  alarm_name_prefix = "${var.project_name}-${var.env_name}-customs"
}

module "customs_app" {
  source = "../../modules/customs-ec2"

  enabled               = var.enabled
  name                  = "${var.project_name}-${var.env_name}-app"
  environment           = var.env_name
  vpc_id                = var.vpc_id
  subnet_id             = var.public_subnet_id
  ami_id                = var.ami_id
  instance_type         = var.instance_type
  key_name              = var.key_pair_name
  app_port              = var.app_port
  enable_eip            = var.enable_eip
  manage_security_group = var.manage_security_group
  security_group_ids    = var.security_group_ids

  ingress_cidrs_ssh       = var.ingress_cidrs_ssh
  ingress_cidrs_app       = var.ingress_cidrs_app
  extra_ingress_tcp_ports = var.extra_ingress_tcp_ports
  root_volume_size        = var.root_volume_size
  root_volume_encrypted   = var.root_volume_encrypted

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "customs_ec2_cpu_high" {
  count = var.enabled && var.enable_cloudwatch_alarms ? 1 : 0

  alarm_name          = "${local.alarm_name_prefix}-ec2-cpu-high"
  alarm_description   = "Customs EC2 CPU usage is high"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = var.cpu_high_evaluation_periods
  threshold           = var.cpu_high_threshold_percent
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 300
  statistic           = "Average"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = module.customs_app.instance_id
  }

  alarm_actions             = var.alarm_actions
  ok_actions                = var.ok_actions
  insufficient_data_actions = []

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "customs_ec2_status_check_failed" {
  count = var.enabled && var.enable_cloudwatch_alarms ? 1 : 0

  alarm_name          = "${local.alarm_name_prefix}-ec2-status-check-failed"
  alarm_description   = "Customs EC2 status check failed"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = var.status_check_failed_evaluation_periods
  threshold           = var.status_check_failed_threshold
  metric_name         = "StatusCheckFailed"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Maximum"
  treat_missing_data  = "breaching"

  dimensions = {
    InstanceId = module.customs_app.instance_id
  }

  alarm_actions             = var.alarm_actions
  ok_actions                = var.ok_actions
  insufficient_data_actions = []

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "customs_ec2_network_out_high" {
  count = var.enabled && var.enable_cloudwatch_alarms ? 1 : 0

  alarm_name          = "${local.alarm_name_prefix}-ec2-network-out-high"
  alarm_description   = "Customs EC2 outbound network traffic is high"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = var.network_out_high_evaluation_periods
  threshold           = var.network_out_high_threshold_bytes
  metric_name         = "NetworkOut"
  namespace           = "AWS/EC2"
  period              = 300
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = module.customs_app.instance_id
  }

  alarm_actions             = var.alarm_actions
  ok_actions                = var.ok_actions
  insufficient_data_actions = []

  tags = local.common_tags
}
