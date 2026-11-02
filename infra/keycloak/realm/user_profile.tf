# The user profile: Keycloak's default, but only admins change a user's email, not the user in the Account Console.
# MetaOS links an account to the person with its verified email (Accounts.kt), so their email is who they are there.
# Keycloak marked an address a user changed to as unverified, which MetaOS doesn't link, so it was never a way in; now
# it isn't even a try. Google's or Discord's email is still taken at the first sign-in, also when Keycloak's review
# form asks for something else (it doesn't show the email then). An admin changing one keeps "Email verified" as it was
# (identity_providers.tf says when it's on).
#
# The resource is the whole profile: Keycloak's defaults are said here as they are, as anything left out goes. Its
# validators' numbers are strings, which Keycloak takes as numbers.
locals {
  user_profile_attributes = {
    username = {
      validators = {
        length                           = { min = "3", max = "255" }
        "username-prohibited-characters" = {}
        "up-username-not-idn-homograph"  = {}
      }
      required = false
      # Users rename themselves too (edit_username_allowed, realm.tf); MetaOS knows them by their ID
      edit = ["admin", "user"]
    }
    email = {
      validators = {
        email  = {}
        length = { max = "255" }
      }
      required = true
      edit     = ["admin"]
    }
    firstName = {
      validators = {
        length                              = { max = "255" }
        "person-name-prohibited-characters" = {}
      }
      required = true
      edit     = ["admin", "user"]
    }
    lastName = {
      validators = {
        length                              = { max = "255" }
        "person-name-prohibited-characters" = {}
      }
      required = true
      edit     = ["admin", "user"]
    }
  }
}

resource "keycloak_realm_user_profile" "metaos" {
  realm_id = keycloak_realm.metaos.id

  # In Keycloak's order, which forms show them in
  dynamic "attribute" {
    for_each = ["username", "email", "firstName", "lastName"]
    content {
      name               = attribute.value
      display_name       = "$${${attribute.value}}"
      required_for_roles = local.user_profile_attributes[attribute.value].required ? ["user"] : []

      dynamic "validator" {
        for_each = local.user_profile_attributes[attribute.value].validators
        content {
          name   = validator.key
          config = validator.value
        }
      }

      permissions {
        view = ["admin", "user"]
        edit = local.user_profile_attributes[attribute.value].edit
      }
    }
  }

  group {
    name                = "user-metadata"
    display_header      = "User metadata"
    display_description = "Attributes, which refer to user metadata"
  }
}
