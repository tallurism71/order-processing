output "swagger_ui" {
  description = "Open in a browser once bootstrap finishes (about 10 minutes after apply)."
  value       = "http://${aws_instance.app.public_dns}:8081/swagger-ui.html"
}

output "order_api" {
  value = "http://${aws_instance.app.public_dns}:8081/api/v1/orders"
}

output "instance_id" {
  value = aws_instance.app.id
}

output "extract_bucket" {
  value = aws_s3_bucket.extracts.bucket
}

output "shell" {
  description = "Open a shell on the instance (needs the AWS CLI Session Manager plugin)."
  value       = "aws ssm start-session --target ${aws_instance.app.id} --region ${var.region}"
}

output "bootstrap_log" {
  value = "aws ssm start-session --target ${aws_instance.app.id} --region ${var.region}  then: sudo tail -f /var/log/order-processing-bootstrap.log"
}
