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

  # Source NLB->target traffic from the NLB (in-VPC) rather than the real client,
  # so the relay SG can restrict the plaintext port to the VPC CIDR. The relay
  # logs byte counts only, not client IPs, so losing client-IP preservation is fine.
  preserve_client_ip = false

  health_check {
    protocol = "TCP"
    port     = "traffic-port"
  }
}

# Plaintext TCP listener — only when TLS is NOT enabled. With a custom domain the
# data plane is TLS-only on :443 (see tls.tf / aws_lb_listener.relay_tls).
resource "aws_lb_listener" "relay" {
  count             = local.tls_enabled ? 0 : 1
  load_balancer_arn = aws_lb.relay.arn
  port              = var.relay_port
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.relay.arn
  }
}
