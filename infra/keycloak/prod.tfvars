# Production: https://metaos-auth.ogrodje.si, realm metaos. Secrets come from the environment (README.md).
keycloak_url = "https://metaos-auth.ogrodje.si"

metaos_mcp_url = "https://metaos.ogrodje.si/mcp"
metaos_api_url = "https://metaos.ogrodje.si/graphql"
metaos_ui_url  = "https://metaos.ogrodje.si"

google = {
  client_id = "FILL-ME.apps.googleusercontent.com"
}

discord = {
  client_id = "FILL-ME"
}

# Through delta.srv4all.net on 587 with StartTLS, as Hetzner blocks 25 and 465
smtp = {
  host              = "delta.srv4all.net"
  port              = "587"
  starttls          = true
  from              = "iva@ogrodje.si"
  from_display_name = "MetaOS"
  username          = "iva@ogrodje.si"
}
