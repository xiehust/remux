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

Copy `terraform.tfvars.example` → `terraform.tfvars` (gitignored) and fill in your
`vpc_id` / `subnet_ids` / `relay_image`, then:

```bash
export TF_VAR_relay_token='<a strong shared token>'   # never put the token in a file
terraform -chdir=infra/terraform apply
```

> ⚠ Always export `TF_VAR_relay_token`. It has an empty default, so a plain
> `apply` without it would reset the relay/Lambda token to "" (auth disabled) and
> break already-connected agents/apps.

Outputs: `websocket_url` (app connects here), `nlb_dns_name` (agent dials here),
`registry_table`, `lambda_function`, `ecs_cluster`. State is local
(`terraform.tfstate`, gitignored) — updates must run from the same machine, or
migrate state to S3 first.

## Updating an existing deployment

Pick the path for what you changed (all from the repo root, `export AWS_REGION=us-east-2`):

**Relay code (`relay/`, `proto/`)** — rebuild the image and roll ECS. Because the
default `relay_image` uses the `:latest` tag, `terraform apply` sees no diff, so
force a new deployment:

```bash
REG=<acct>.dkr.ecr.us-east-2.amazonaws.com
docker build -f relay/Dockerfile -t remux-relay .
aws ecr get-login-password | docker login --username AWS --password-stdin "$REG"
docker tag remux-relay:latest "$REG/remux-relay:latest" && docker push "$REG/remux-relay:latest"
aws ecs update-service --cluster remux-cluster --service remux-relay --force-new-deployment
aws ecs wait services-stable --cluster remux-cluster --services remux-relay
```

Prefer an **immutable tag** (e.g. a git sha): push `…/remux-relay:<sha>`, set it in
`terraform.tfvars`, and `terraform apply` — then changes are visible and revertible.

**Lambda code (`infra/lambda/`)** — rebuild the zip; `source_code_hash` lets
`terraform apply` detect and redeploy it:

```bash
( cd infra/lambda && GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o /tmp/bootstrap . )
( cd /tmp && zip -j "$OLDPWD/infra/terraform/build/lambda.zip" bootstrap )
export TF_VAR_relay_token='<token>'
terraform -chdir=infra/terraform apply        # or: aws lambda update-function-code --function-name remux-ws --zip-file fileb://infra/terraform/build/lambda.zip
```

**Topology / variables (`*.tf`, CPU/memory, routes, …)** —

```bash
export TF_VAR_relay_token='<token>'
terraform -chdir=infra/terraform plan         # review, then `apply`
```

**Rollback:** relay → `aws ecs update-service … --task-definition remux-relay:<old-revision>`
or re-push the old image tag; Lambda → `aws lambda update-function-code … --zip-file fileb://<old.zip>`.
Verify with `curl http://<nlb>:8080/healthz` (→ `ok`) and `aws ecs wait services-stable …`.

## CI/CD: deploy from GitHub Actions

`.github/workflows/deploy.yml` deploys this infra from CI. It is **manual only**
(`workflow_dispatch`), runs in the `production` GitHub Environment (required-reviewer
approval), and authenticates to AWS via **GitHub OIDC** — no long-lived keys. It
builds + pushes the relay image (immutable git-sha tag), builds the Lambda zip,
and runs `terraform apply` against the S3 remote state.

### Prerequisites (one-time, account side)

- Remote state: S3 bucket + DynamoDB lock table (see *Remote state* above).
- OIDC: a GitHub OIDC provider (`token.actions.githubusercontent.com`) and an IAM
  role whose trust is scoped to `repo:<owner>/<repo>:environment:production`.
  (The role has broad permissions for a workshop — tighten for real production;
  the tight trust + manual approval bound who can assume it.)

### One-time GitHub setup (repo → Settings)

**Secrets and variables → Actions → Variables:**

| Variable | Value |
|---|---|
| `AWS_ROLE_ARN` | the OIDC deploy role ARN (`arn:aws:iam::<acct>:role/remux-github-deploy`) |
| `AWS_REGION` | `us-east-2` |
| `ECR_REPO` | `<acct>.dkr.ecr.us-east-2.amazonaws.com/remux-relay` |
| `VPC_ID` | your VPC id |
| `SUBNET_IDS` | JSON list, e.g. `["subnet-a","subnet-b","subnet-c"]` |
| `TF_STATE_BUCKET` | the S3 state bucket name |

**Secrets and variables → Actions → Secrets:** `RELAY_TOKEN` = the relay bearer token.

**Environments → New environment `production`:** add yourself as a **Required
reviewer** (this is the approval gate the deploy job pauses on).

### Run it

Actions → **Deploy infra (AWS)** → *Run workflow* → type `deploy` to confirm →
approve the `production` deployment when prompted. The job applies, forces a fresh
ECS rollout, waits for steady state, and prints the outputs.

## TLS / custom domain (`wss://`)

By default the data plane is plaintext `ws://<nlb>:8080` (the SSH session inside
is end-to-end encrypted regardless). To serve `wss://` with a publicly-trusted
cert, set `domain_name` (a domain you control — ACM can't issue for the AWS-owned
`*.elb.amazonaws.com` name) and `relay.tf` provisions a Route53 zone, an ACM
cert, an NLB `:443` TLS listener, and `relay.<domain>` → NLB.

Two-phase because ACM DNS validation needs the registrar delegated to Route53 first:

```bash
# 1. Create just the hosted zone, then point the registrar's nameservers at it.
terraform -chdir=infra/terraform apply -target=aws_route53_zone.primary
terraform -chdir=infra/terraform output route53_nameservers   # set these at your registrar
# 2. After NS delegation is live (dig NS <domain> shows the awsdns servers), apply the rest:
terraform -chdir=infra/terraform apply
terraform -chdir=infra/terraform output relay_wss_url          # wss://relay.<domain>
```

Then use `wss://relay.<domain>` as the data URL in the agent (`-data-url`) and the
app's Relay settings. (`ssl_policy` is TLS 1.2/1.3; the relay still speaks plaintext
behind the NLB, which terminates TLS.)

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
