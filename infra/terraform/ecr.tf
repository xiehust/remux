# The ECR repository for the relay image is created out-of-band, before the first
# `terraform apply` — the Fargate service can only start once the image already
# exists to pull. Here we just attach a lifecycle policy to keep storage bounded:
# retain the 5 most recently pushed images and expire anything older.
resource "aws_ecr_lifecycle_policy" "relay" {
  repository = "${var.name_prefix}-relay"

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep only the 5 most recent images; expire older."
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 5
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
