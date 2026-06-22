output "websocket_url" {
  description = "WebSocket URL the mobile app connects to (App <-> Relay control plane)."
  value       = aws_apigatewayv2_stage.prod.invoke_url
}

output "nlb_dns_name" {
  description = "NLB DNS name the device agent dials outbound (TCP long connection)."
  value       = aws_lb.relay.dns_name
}

output "registry_table" {
  description = "DynamoDB device/connection registry table name."
  value       = aws_dynamodb_table.registry.name
}

output "lambda_function" {
  description = "Connection-lifecycle / routing Lambda function name."
  value       = aws_lambda_function.ws.function_name
}

output "ecs_cluster" {
  description = "ECS cluster running the Fargate relay data-pipe."
  value       = aws_ecs_cluster.main.name
}
