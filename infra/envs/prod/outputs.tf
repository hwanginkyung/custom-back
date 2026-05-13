output "instance_id" {
  value       = module.customs_app.instance_id
  description = "Customs app EC2 instance ID"
}

output "instance_public_ip" {
  value       = module.customs_app.instance_public_ip
  description = "Customs app public IP"
}

output "security_group_id" {
  value       = module.customs_app.security_group_id
  description = "Security group ID"
}

output "security_group_ids" {
  value       = module.customs_app.security_group_ids
  description = "Security group IDs"
}

output "elastic_ip" {
  value       = module.customs_app.elastic_ip
  description = "Elastic IP"
}

output "cloudwatch_alarm_names" {
  value = compact([
    try(aws_cloudwatch_metric_alarm.customs_ec2_cpu_high[0].alarm_name, null),
    try(aws_cloudwatch_metric_alarm.customs_ec2_status_check_failed[0].alarm_name, null),
    try(aws_cloudwatch_metric_alarm.customs_ec2_network_out_high[0].alarm_name, null),
  ])
  description = "Customs EC2 CloudWatch alarm names"
}
