# Network Load Balancer: the device agent's outbound TCP long-connection entry
# point into the Fargate relay (PRD topology). TCP passthrough — the relay keeps
# end-to-end SSH encryption intact.

resource "aws_lb" "relay" {
  name               = "${var.name_prefix}-nlb"
  load_balancer_type = "network"
  internal           = false
  subnets            = var.subnet_ids
}

resource "aws_lb_target_group" "relay" {
  name        = "${var.name_prefix}-tg"
  port        = var.relay_port
  protocol    = "TCP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    protocol = "TCP"
    port     = "traffic-port"
  }
}

resource "aws_lb_listener" "relay" {
  load_balancer_arn = aws_lb.relay.arn
  port              = var.relay_port
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.relay.arn
  }
}
