# TLS for the relay data plane: a Route53-managed custom domain + ACM cert +
# an NLB TLS listener, so the app/agent can use wss:// (not plaintext ws://).
# All gated on var.domain_name — leave it empty to keep the plaintext-only setup.
#
# Delegation: create the hosted zone first (terraform apply -target=
# aws_route53_zone.primary), point the registrar's nameservers at its NS records,
# then apply the rest — ACM DNS validation only completes once delegation is live.

locals {
  tls_enabled = var.domain_name != ""
  relay_fqdn  = local.tls_enabled ? "${var.relay_subdomain}.${var.domain_name}" : ""
}

resource "aws_route53_zone" "primary" {
  count = local.tls_enabled ? 1 : 0
  name  = var.domain_name
}

resource "aws_acm_certificate" "relay" {
  count             = local.tls_enabled ? 1 : 0
  domain_name       = local.relay_fqdn
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "cert_validation" {
  for_each = local.tls_enabled ? {
    for o in aws_acm_certificate.relay[0].domain_validation_options : o.domain_name => {
      name   = o.resource_record_name
      type   = o.resource_record_type
      record = o.resource_record_value
    }
  } : {}

  zone_id = aws_route53_zone.primary[0].zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "relay" {
  count                   = local.tls_enabled ? 1 : 0
  certificate_arn         = aws_acm_certificate.relay[0].arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

# TLS listener on the NLB (port 443) terminating TLS and forwarding the decrypted
# TCP stream to the same relay target group (relay still speaks plaintext :8080).
resource "aws_lb_listener" "relay_tls" {
  count             = local.tls_enabled ? 1 : 0
  load_balancer_arn = aws_lb.relay.arn
  port              = 443
  protocol          = "TLS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.relay[0].certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.relay.arn
  }
}

# relay.<domain> -> the NLB.
resource "aws_route53_record" "relay" {
  count   = local.tls_enabled ? 1 : 0
  zone_id = aws_route53_zone.primary[0].zone_id
  name    = local.relay_fqdn
  type    = "A"

  alias {
    name                   = aws_lb.relay.dns_name
    zone_id                = aws_lb.relay.zone_id
    evaluate_target_health = true
  }
}

output "route53_nameservers" {
  description = "Set these as the domain's nameservers at the registrar (Namecheap)."
  value       = local.tls_enabled ? aws_route53_zone.primary[0].name_servers : []
}

output "relay_wss_url" {
  description = "wss endpoint for the app/agent data plane once TLS is live."
  value       = local.tls_enabled ? "wss://${local.relay_fqdn}" : ""
}
