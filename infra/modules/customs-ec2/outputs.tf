output "instance_id" {
  value = var.enabled ? aws_instance.app[0].id : null
}

output "instance_public_ip" {
  value = var.enabled ? aws_instance.app[0].public_ip : null
}

output "security_group_id" {
  value = var.enabled ? (var.manage_security_group ? aws_security_group.app[0].id : try(var.security_group_ids[0], null)) : null
}

output "security_group_ids" {
  value = var.enabled ? (var.manage_security_group ? [aws_security_group.app[0].id] : var.security_group_ids) : []
}

output "elastic_ip" {
  value = var.enabled && var.enable_eip ? aws_eip.app[0].public_ip : null
}
