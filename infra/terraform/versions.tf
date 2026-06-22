terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state in S3 with a DynamoDB lock, shared by local runs and CI.
  # Partial config: bucket/key/region/dynamodb_table come from backend.hcl
  # (local, gitignored) or -backend-config flags (CI), so the account id in the
  # bucket name never lands in git.
  backend "s3" {}
}

provider "aws" {
  region = var.region
}
