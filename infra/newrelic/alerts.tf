# Alert policy MetaOS: its conditions, each an incident of its own, mailed to Oto when it opens and closes.

resource "newrelic_alert_policy" "metaos" {
  incident_preference = "PER_CONDITION"
  name                = "MetaOS"
}

resource "newrelic_nrql_alert_condition" "down" {
  aggregation_method             = "event_timer"
  aggregation_timer              = "300"
  aggregation_window             = 300
  close_violations_on_expiration = false
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "MetaOS is down: uptime checks fail"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 259200
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "above"
    threshold                       = 1
    threshold_duration              = 300
    threshold_occurrences           = "at_least_once"
  }
  nrql {
    data_account_id = 8666724
    query           = "SELECT count(*) FROM SyntheticCheck WHERE monitorName IN ('MetaOS pages', 'MetaOS API', 'MetaOS sign-in') AND result = 'FAILED' FACET monitorName"
  }
}

resource "newrelic_nrql_alert_condition" "errors" {
  aggregation_method             = "event_timer"
  aggregation_timer              = "300"
  aggregation_window             = 900
  close_violations_on_expiration = false
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "MetaOS errors: more than 3 in 15 minutes"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 259200
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "above"
    threshold                       = 3
    threshold_duration              = 900
    threshold_occurrences           = "at_least_once"
  }
  nrql {
    data_account_id = 8666724
    query           = "SELECT count(*) FROM TransactionError WHERE appName IN ('MetaOS', 'patreon-gateway') AND error.expected IS NOT true FACET appName"
  }
}

resource "newrelic_nrql_alert_condition" "browser_errors" {
  aggregation_method             = "event_timer"
  aggregation_timer              = "300"
  aggregation_window             = 900
  close_violations_on_expiration = false
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "MetaOS web: more than 10 JavaScript errors in 15 minutes"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 259200
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "above"
    threshold                       = 10
    threshold_duration              = 900
    threshold_occurrences           = "at_least_once"
  }
  nrql {
    data_account_id = 8666724
    query           = "SELECT count(*) FROM JavaScriptError WHERE appName = 'MetaOS web'"
  }
}

resource "newrelic_nrql_alert_condition" "connection_down" {
  aggregation_delay              = "120"
  aggregation_method             = "event_flow"
  aggregation_window             = 300
  close_violations_on_expiration = false
  description                    = "One of MetaOS's outside connections (Drive, Gmail, Cloud Storage, Koofr, Dropbox, Patreon) or patreon-gateway's (Patreon's API, the bot signed in) failed its hourly check: metaos_*_connected or patreon_gateway_* at 0. /actuator/health on port 9090, or Prometheus, says which."
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "MetaOS: an outside connection is down"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 86400
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "below"
    threshold                       = 1
    threshold_duration              = 900
    threshold_occurrences           = "all"
  }
  nrql {
    data_account_id = 8666724
    query           = "FROM Metric SELECT min(metaos_drive_connected) * min(metaos_gmail_connected) * min(metaos_storage_connected) * min(metaos_koofr_connected) * min(metaos_dropbox_connected) * min(metaos_patreon_connected) * min(patreon_gateway_api_connected) * min(patreon_gateway_signed_in) WHERE prometheus_server = 'ogrodje-one'"
  }
}

resource "newrelic_nrql_alert_condition" "sign_ins_failing" {
  aggregation_delay              = "120"
  aggregation_method             = "event_flow"
  aggregation_window             = 900
  close_violations_on_expiration = false
  description                    = "More than 5 failed sign-ins or token exchanges in realm metaos in 15 minutes (keycloak_user_events_total with an error; refresh errors, an old tab's expired session, left out). Keycloak's events say which and why."
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "Keycloak: sign-ins failing"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 86400
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "above"
    threshold                       = 5
    threshold_duration              = 900
    threshold_occurrences           = "at_least_once"
  }
  nrql {
    data_account_id = 8666724
    query           = "FROM Metric SELECT sum(keycloak_user_events_total) WHERE prometheus_server = 'ogrodje-one' AND realm = 'metaos' AND error IS NOT NULL AND event != 'refresh_token'"
  }
}

resource "newrelic_nrql_alert_condition" "node_memory" {
  aggregation_delay              = "120"
  aggregation_method             = "event_flow"
  aggregation_window             = 60
  close_violations_on_expiration = false
  description                    = "The node's available memory below 1.5 GB for 5 minutes (node-exporter). Its kubelet evicts pods below 1 GiB, and below that the kernel thrashes, as in #41's deploy. Also opens when the node's metrics stop for 15 minutes: the node, Prometheus or its remote write is down."
  enabled                        = true
  expiration_duration            = 900
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "ogrodje-one: memory running low"
  open_violation_on_expiration   = true
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 86400
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "below"
    threshold                       = 1.5
    threshold_duration              = 300
    threshold_occurrences           = "all"
  }
  nrql {
    data_account_id = 8666724
    query           = "FROM Metric SELECT min(node_memory_MemAvailable_bytes) / 1e9 WHERE prometheus_server = 'ogrodje-one'"
  }
}

resource "newrelic_nrql_alert_condition" "container_restarted" {
  aggregation_delay              = "120"
  aggregation_method             = "event_flow"
  aggregation_window             = 300
  close_violations_on_expiration = false
  description                    = "A container in namespace metaos-prod restarted (kube-state-metrics): crashed, failed its liveness probe or was OOMKilled; kube_pod_container_status_last_terminated_reason says which."
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "MetaOS: a container restarted"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 86400
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "above"
    threshold                       = 0
    threshold_duration              = 300
    threshold_occurrences           = "at_least_once"
  }
  nrql {
    data_account_id = 8666724
    query           = "FROM Metric SELECT sum(kube_pod_container_status_restarts_total) WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' FACET pod, container"
  }
}

resource "newrelic_nrql_alert_condition" "short_of_replicas" {
  aggregation_delay              = "120"
  aggregation_method             = "event_flow"
  aggregation_window             = 60
  close_violations_on_expiration = false
  description                    = "A Deployment in namespace metaos-prod (metaos, metaos-web, keycloak, patreon-gateway) has had fewer replicas available than it asks for, for 10 minutes: pods not ready, pending or crashing."
  enabled                        = true
  fill_option                    = "none"
  fill_value                     = 0
  ignore_on_expected_termination = false
  name                           = "MetaOS: a Deployment is short of replicas"
  open_violation_on_expiration   = false
  policy_id                      = newrelic_alert_policy.metaos.id
  type                           = "static"
  violation_time_limit_seconds   = 86400
  critical {
    disable_event_creation          = false
    disable_health_status_reporting = false
    operator                        = "above"
    threshold                       = 0
    threshold_duration              = 600
    threshold_occurrences           = "all"
  }
  nrql {
    data_account_id = 8666724
    query           = "FROM Metric SELECT latest(kube_deployment_spec_replicas) - latest(kube_deployment_status_replicas_available) WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' FACET deployment"
  }
}

# Where they go
resource "newrelic_notification_destination" "email" {
  active = true
  name   = "Oto (otobrglez@gmail.com)"
  type   = "EMAIL"
  property {
    key   = "email"
    value = "otobrglez@gmail.com"
  }
}

resource "newrelic_notification_channel" "email" {
  active         = true
  destination_id = newrelic_notification_destination.email.id
  name           = "MetaOS: email Oto"
  product        = "IINT"
  type           = "EMAIL"
  property {
    key   = "subject"
    value = "{{ issueTitle }}"
  }
}

resource "newrelic_workflow" "metaos" {
  enabled               = true
  enrichments_enabled   = true
  muting_rules_handling = "NOTIFY_ALL_ISSUES"
  name                  = "MetaOS"
  destination {
    channel_id              = newrelic_notification_channel.email.id
    notification_triggers   = ["ACTIVATED", "CLOSED"]
    update_original_message = true
  }
  issues_filter {
    name = "Policy MetaOS"
    type = "FILTER"
    predicate {
      attribute = "labels.policyIds"
      operator  = "EXACTLY_MATCHES"
      values    = [newrelic_alert_policy.metaos.id]
    }
  }
}
