# New Relic account 8666724, in its EU region: what watches MetaOS beside its agents (CLAUDE.md, "New Relic"). The
# agents make their APM apps themselves; the browser apps, the uptime checks, the alerts and where they go, and the
# dashboard are here. Signs in with a User API key (NEW_RELIC_API_KEY; justfile takes .envrc's NEW_RELIC_USER_KEY).
terraform {
  required_version = ">= 1.9"

  required_providers {
    newrelic = {
      source  = "newrelic/newrelic"
      version = "~> 3.101"
    }
  }

  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "newrelic"
  }
}

provider "newrelic" {
  account_id = 8666724
  region     = "EU"
}
