# ECS Fargate service running the containerized Go relay data-pipe
# (relay/Dockerfile). The stateful TCP relay process bridges the WebSocket app
# leg and the agent's NLB TCP leg.

resource "aws_ecs_cluster" "main" {
  name = "${var.name_prefix}-cluster"
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = "${var.name_prefix}-ecs-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_vpc" "selected" {
  id = var.vpc_id
}

resource "aws_security_group" "relay" {
  name = "${var.name_prefix}-relay-sg"
  # NOTE: description is immutable — changing it forces SG replacement, which
  # fails while the SG is attached to a running task. Keep it stable.
  description = "Allow inbound relay TCP from the NLB and outbound anywhere."
  vpc_id      = var.vpc_id

  ingress {
    # In-VPC only: the NLB (TLS :443) forwards to the task on this port. The task
    # has a public IP (needed to pull from ECR in public subnets), so restricting
    # to the VPC CIDR keeps the plaintext port off the public internet.
    description = "relay port from within the VPC (NLB)"
    from_port   = var.relay_port
    to_port     = var.relay_port
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_cloudwatch_log_group" "relay" {
  name              = "/ecs/${var.name_prefix}-relay"
  retention_in_days = 14
}

resource "aws_ecs_task_definition" "relay" {
  family                   = "${var.name_prefix}-relay"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.fargate_cpu
  memory                   = var.fargate_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "relay"
      image     = var.relay_image
      essential = true
      portMappings = [
        {
          containerPort = var.relay_port
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "REMUX_RELAY_ADDR", value = "0.0.0.0:${var.relay_port}" },
        { name = "REMUX_RELAY_TOKEN", value = var.relay_token },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.relay.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "relay"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "relay" {
  name            = "${var.name_prefix}-relay"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.relay.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.relay.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.relay.arn
    container_name   = "relay"
    container_port   = var.relay_port
  }

  # Whichever data-plane listener is active (plaintext 8080 or TLS 443) must exist
  # before the service registers with the target group.
  depends_on = [aws_lb_listener.relay, aws_lb_listener.relay_tls]
}
