SUPERGOAL_PHASE_START
Phase: 5 of 10 — AWS infra-as-code
Task: Deliver the PRD's production relay topology as deploy-ready, validated Terraform + Lambda source + relay Dockerfile (not deployed).
Type: greenfield
Mandatory commands: terraform -chdir=infra/terraform init -backend=false, terraform -chdir=infra/terraform validate, terraform -chdir=infra/terraform fmt -check, docker build -f relay/Dockerfile -t remux-relay ., GOOS=linux go build ./infra/lambda/...
Acceptance criteria: 7
Evidence required: terraform validate success, grep of 5 resource types, docker build success, lambda cross-build exit 0
Depends on phases: 1

## Why
Deliver the PRD's production relay topology as deploy-ready, validated infrastructure without incurring AWS cost.

## Work
- `infra/terraform/`: author Terraform for the PRD topology —
  - API Gateway **WebSocket API** with `$connect` / `$disconnect` / `$default` routes integrated to Lambda.
  - **Lambda** function(s) for connection lifecycle + routing (Go runtime, `provided.al2`/`al2023`).
  - **DynamoDB** table for device/connection registry (device_id, connection_id, status, heartbeat ts).
  - **ECS Fargate** service running the containerized Go relay data-pipe (task def + service, 0.25vCPU/0.5GB).
  - **NLB** (Network Load Balancer) for the agent's outbound TCP long-connection ingress.
  - IAM roles/policies, variables (`region`, `token` etc.), outputs (api endpoint, nlb dns). Use `aws` provider with version pin; no remote backend required for validation.
- `infra/lambda/`: Go Lambda handler source (connect/disconnect/route) that compiles for linux. Use `aws-lambda-go`.
- `relay/Dockerfile`: multi-stage build (golang builder → distroless/alpine) producing the relay image runnable on Fargate.
- `infra/README.md`: deploy steps (`terraform init/plan/apply`, build+push image to ECR, configure token), and the PRD cost estimate (~$28/mo).
- `terraform fmt` everything.

## Acceptance criteria (all must pass — verify each in transcript)
- `terraform -chdir=infra/terraform init -backend=false` succeeds.
- `terraform -chdir=infra/terraform validate` reports success.
- `terraform -chdir=infra/terraform fmt -check` passes.
- Terraform defines each PRD component — API Gateway WebSocket API, Lambda, DynamoDB, ECS Fargate, NLB — verifiable by grepping the resource types (`aws_apigatewayv2_api`, `aws_lambda_function`, `aws_dynamodb_table`, `aws_ecs_service`/`aws_ecs_task_definition`, `aws_lb` with `load_balancer_type = "network"`).
- `docker build -f relay/Dockerfile -t remux-relay .` succeeds and produces an image.
- `GOOS=linux go build ./infra/lambda/...` exits 0.
- `infra/README.md` documents deploy steps and the ~cost estimate.

## Mandatory commands (run each, surface last ~10 lines + exit code)
- `terraform -chdir=infra/terraform init -backend=false`
- `terraform -chdir=infra/terraform validate`
- `terraform -chdir=infra/terraform fmt -check`
- `docker build -f relay/Dockerfile -t remux-relay .`
- `GOOS=linux go build ./infra/lambda/...`

## Evidence required in transcript
- `terraform validate` success line.
- `grep -rE 'aws_apigatewayv2_api|aws_lambda_function|aws_dynamodb_table|aws_ecs_(service|task_definition)|load_balancer_type *= *"network"' infra/terraform` showing all 5.
- `docker build` success line; lambda cross-build exit 0.

## Notes
`terraform validate` does NOT require AWS credentials — that's the gate. Do NOT run `terraform apply`. Pin the aws provider version. If `docker build` cannot reach the network for the Go base image, document the constraint and ensure the Dockerfile is correct by `docker build`-ing against an already-pulled base if available; the build succeeding is the criterion. Consult Context7 for current `aws_apigatewayv2_*` Terraform resource syntax.
