# Device / connection registry. Maps WebSocket connectionId <-> deviceId and
# tracks status + heartbeat, mirroring the in-memory registry of the self-hosted
# relay (relay/registry.go).
resource "aws_dynamodb_table" "registry" {
  name         = "${var.name_prefix}-registry"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "connectionId"

  attribute {
    name = "connectionId"
    type = "S"
  }

  attribute {
    name = "deviceId"
    type = "S"
  }

  # Look up a device's current connection by deviceId (for routing app->agent).
  global_secondary_index {
    name            = "by-device"
    hash_key        = "deviceId"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  tags = {
    Project = var.name_prefix
  }
}
