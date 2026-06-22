# Remux relay — AWS deployment (infrastructure-as-code)

This directory holds the **deploy-ready** AWS topology for the production Remux
relay, exactly as described in the PRD. It is written and validated but **not
applied** by the build (no AWS account is touched). Apply it yourself when you
want a hosted relay.

## Topology

```
Mobile app ──WSS──▶ API Gateway (WebSocket API)
                      │  $connect / $disconnect / $default
                      ▼
                   Lambda (infra/lambda)  ──▶ DynamoDB (device/connection registry)
                      │ routes "open" to the device's connection
                      ▼
   Device agent ──TCP──▶ NLB ──▶ ECS Fargate (relay/ data-pipe, content-agnostic)
```

| PRD component            | Terraform resource(s)                                            |
|--------------------------|------------------------------------------------------------------|
| API Gateway WebSocket    | `aws_apigatewayv2_api` (`protocol_type = WEBSOCKET`) + routes/stage |
| Lambda (lifecycle/route) | `aws_lambda_function` (+ IAM role, `infra/lambda` Go source)      |
| DynamoDB registry        | `aws_dynamodb_table` (+ `by-device` GSI, TTL)                    |
| ECS Fargate relay        | `aws_ecs_service` + `aws_ecs_task_definition` (FARGATE)          |
| NLB (agent ingress)      | `aws_lb` (`load_balancer_type = "network"`) + target group/listener |

## Validate (no credentials needed)

```bash
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform fmt -check
```

## Build the artifacts

```bash
# Relay container image (push to ECR):
docker build -f relay/Dockerfile -t remux-relay .
# ... docker tag / aws ecr get-login-password / docker push ...

# Lambda bootstrap zip (Go custom runtime, arm64):
cd infra/lambda
GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o bootstrap .
mkdir -p ../terraform/build && zip ../terraform/build/lambda.zip bootstrap
```

## Deploy

```bash
export TF_VAR_relay_token='<a strong shared token>'
terraform -chdir=infra/terraform apply \
  -var 'vpc_id=vpc-xxxx' \
  -var 'subnet_ids=["subnet-a","subnet-b"]' \
  -var 'relay_image=<acct>.dkr.ecr.<region>.amazonaws.com/remux-relay:latest'
```

Outputs: `websocket_url` (app connects here), `nlb_dns_name` (agent dials here),
`registry_table`, `lambda_function`, `ecs_cluster`.

## Cost estimate (low traffic, < 10 devices)

| Item                                   | ~Monthly |
|----------------------------------------|----------|
| API Gateway WebSocket (~$1/M messages) | ~$1      |
| Lambda                                 | free tier |
| DynamoDB on-demand                     | ~$1      |
| ECS Fargate (0.25 vCPU / 0.5 GB)       | ~$10     |
| NLB (fixed)                            | ~$16     |
| **Total**                              | **~$28** |

The self-hosted single-binary relay (`relay/`) is the cheaper alternative for a
single developer: run it on any small VPS / the same EC2 box behind a TLS proxy.
