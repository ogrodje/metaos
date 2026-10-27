# The node MetaOS runs on and its pods, from the cluster's Prometheus (k8s/prometheus/newrelic-values.yaml)

resource "newrelic_one_dashboard" "ogrodje_one" {
  description = "The node MetaOS runs on and its pods in metaos-prod, from the cluster's Prometheus (k8s/prometheus/newrelic-values.yaml), with the uptime checks, outside connections and Keycloak's sign-ins. OG-38."
  name        = "MetaOS on ogrodje-one"
  permissions = "public_read_write"
  page {
    name = "MetaOS on ogrodje-one"
    widget_bar {
      column                   = 7
      facet_show_other_series  = false
      filter_current_dashboard = false
      height                   = 3
      ignore_time_range        = false
      legend_enabled           = false
      linked_entity_guids      = []
      row                      = 4
      title                    = "Memory against the container's limit (%)"
      width                    = 6
      y_axis_left_max          = 0
      y_axis_left_min          = 0
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT 100 * latest(container_memory_working_set_bytes) / filter(latest(kube_pod_container_resource_limits), WHERE resource = 'memory') WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' AND container IS NOT NULL AND container NOT IN ('', 'POD', 'kube-state-metrics') FACET container, pod LIMIT 20"
      }
    }
    widget_billboard {
      column                  = 9
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      legend_enabled          = false
      row                     = 1
      title                   = "Node now"
      width                   = 4
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT latest(node_memory_MemAvailable_bytes) / 1e9 AS 'Memory free (GB)', latest(node_filesystem_avail_bytes) / 1e9 AS 'Disk free (GB)', latest(node_load1) AS 'Load (1 min)' WHERE prometheus_server = 'ogrodje-one'"
      }
    }
    widget_billboard {
      column                  = 10
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      legend_enabled          = false
      row                     = 7
      title                   = "Replicas available"
      width                   = 3
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT latest(kube_deployment_status_replicas_available) WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' FACET deployment"
      }
    }
    widget_billboard {
      column                  = 1
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = true
      legend_enabled          = false
      row                     = 10
      title                   = "Uptime checks (a day, % passed)"
      width                   = 4
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      nrql_query {
        account_id = "8666724"
        query      = "FROM SyntheticCheck SELECT percentage(count(*), WHERE result = 'SUCCESS') FACET monitorName SINCE 1 day ago"
      }
    }
    widget_billboard {
      column                  = 5
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      legend_enabled          = false
      row                     = 10
      title                   = "Outside connections (1 = connected)"
      width                   = 4
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT min(metaos_drive_connected) AS 'Drive', min(metaos_gmail_connected) AS 'Gmail', min(metaos_storage_connected) AS 'Cloud Storage', min(metaos_koofr_connected) AS 'Koofr', min(metaos_dropbox_connected) AS 'Dropbox', min(metaos_patreon_connected) AS 'Patreon', min(patreon_gateway_signed_in) AS 'Patreon bot signed in' WHERE prometheus_server = 'ogrodje-one' SINCE 10 minutes ago"
      }
    }
    widget_line {
      column                  = 1
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      is_label_visible        = false
      legend_enabled          = false
      row                     = 1
      title                   = "Node: memory available (GB)"
      width                   = 4
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      y_axis_left_zero        = false
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT min(node_memory_MemAvailable_bytes) / 1e9 AS 'Available (GB)' WHERE prometheus_server = 'ogrodje-one' TIMESERIES AUTO"
      }
    }
    widget_line {
      column                  = 5
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      is_label_visible        = false
      legend_enabled          = false
      row                     = 1
      title                   = "Node: memory pressure"
      width                   = 4
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      y_axis_left_zero        = false
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT derivative(node_vmstat_pgmajfault, 1 second) AS 'Major page faults /s', 100 * rate(sum(node_pressure_memory_waiting_seconds_total), 1 second) AS 'Time waiting on memory (%)' WHERE prometheus_server = 'ogrodje-one' TIMESERIES AUTO"
      }
    }
    widget_line {
      column                  = 1
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      is_label_visible        = false
      legend_enabled          = false
      row                     = 4
      title                   = "MetaOS's containers: memory (MB)"
      width                   = 6
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      y_axis_left_zero        = false
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT latest(container_memory_working_set_bytes) / 1e6 WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' AND container IS NOT NULL AND container NOT IN ('', 'POD') FACET container, pod TIMESERIES AUTO LIMIT 20"
      }
    }
    widget_line {
      column                  = 1
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      is_label_visible        = false
      legend_enabled          = false
      row                     = 7
      title                   = "CPU (cores)"
      width                   = 6
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      y_axis_left_zero        = false
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT rate(sum(container_cpu_usage_seconds_total), 1 second) WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' AND container IS NOT NULL AND container NOT IN ('', 'POD') FACET container TIMESERIES AUTO"
      }
    }
    widget_line {
      column                  = 9
      facet_show_other_series = false
      height                  = 3
      ignore_time_range       = false
      is_label_visible        = false
      legend_enabled          = false
      row                     = 10
      title                   = "Keycloak: sign-ins"
      width                   = 4
      y_axis_left_max         = 0
      y_axis_left_min         = 0
      y_axis_left_zero        = false
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT sum(keycloak_user_events_total) WHERE prometheus_server = 'ogrodje-one' AND realm = 'metaos' AND event IN ('login', 'code_to_token', 'identity_provider_login') FACET event, error TIMESERIES AUTO"
      }
    }
    widget_table {
      column                   = 7
      facet_show_other_series  = false
      filter_current_dashboard = false
      height                   = 3
      ignore_time_range        = true
      legend_enabled           = false
      linked_entity_guids      = []
      row                      = 7
      title                    = "Restarts (a day)"
      width                    = 3
      y_axis_left_max          = 0
      y_axis_left_min          = 0
      nrql_query {
        account_id = "8666724"
        query      = "FROM Metric SELECT sum(kube_pod_container_status_restarts_total) AS 'Restarts' WHERE prometheus_server = 'ogrodje-one' AND namespace = 'metaos-prod' FACET pod, container SINCE 1 day ago"
      }
    }
  }
}
