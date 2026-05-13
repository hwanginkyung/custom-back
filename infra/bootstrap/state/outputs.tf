output "state_bucket_name" {
  value       = aws_s3_bucket.state.bucket
  description = "Terraform remote state S3 bucket name"
}

output "lock_table_name" {
  value       = try(aws_dynamodb_table.lock[0].name, null)
  description = "Terraform remote state lock table name (생성 시)"
}
