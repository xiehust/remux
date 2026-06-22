variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefix for all resource names."
  type        = string
  default     = "remux"
}

variable "vpc_id" {
  description = "VPC in which to run the Fargate relay and NLB."
  type        = string
  default     = "vpc-placeholder"
}

variable "subnet_ids" {
  description = "Subnets (>= 2 AZs) for the NLB and Fargate tasks."
  type        = list(string)
  default     = ["subnet-placeholder-a", "subnet-placeholder-b"]
}

variable "relay_image" {
  description = "Container image for the Go relay data-pipe (ECR URI). Build from relay/Dockerfile and push to ECR."
  type        = string
  default     = "PLACEHOLDER.dkr.ecr.us-east-1.amazonaws.com/remux-relay:latest"
}

variable "relay_port" {
  description = "TCP port the relay listens on inside the container (and behind the NLB)."
  type        = number
  default     = 8080
}

variable "relay_token" {
  description = "Shared bearer token gating the relay. Set via TF_VAR_relay_token; do NOT commit a real value."
  type        = string
  default     = ""
  sensitive   = true
}

variable "lambda_zip" {
  description = "Path to the built Lambda deployment zip (bootstrap binary). Build with: cd infra/lambda && GOOS=linux GOARCH=arm64 go build -o bootstrap . && zip ../terraform/build/lambda.zip bootstrap"
  type        = string
  default     = "build/lambda.zip"
}

variable "domain_name" {
  description = "Apex domain managed in Route53 for the relay's TLS endpoint (e.g. remuxapp.site). Empty disables the TLS/HTTPS path (plaintext ws:// only)."
  type        = string
  default     = ""
}

variable "relay_subdomain" {
  description = "Subdomain under domain_name for the relay's wss endpoint."
  type        = string
  default     = "relay"
}

variable "fargate_cpu" {
  description = "Fargate task CPU units (256 = 0.25 vCPU)."
  type        = string
  default     = "256"
}

variable "fargate_memory" {
  description = "Fargate task memory (MiB)."
  type        = string
  default     = "512"
}
