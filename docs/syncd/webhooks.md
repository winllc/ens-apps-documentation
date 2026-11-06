# Webhooks

syncd sends **no webhooks**. Neither the engine nor the console makes outbound HTTP calls to
addresses you register: there are no event subscriptions, signing secrets or delivery queues. The
console's only outbound HTTP is to its OpenID Connect provider; the engine's outbound connections
are to the sources and targets its configuration names.

Integrators watch and drive syncd through these instead:

| Need | Use |
|---|---|
| Alerting on failures (a source down or stalled, a target unreachable, dead letters, held deletes, rule errors, memory) | The engine's Prometheus metrics at `GET /metrics` and the shipped alert rules (`share/monitoring/syncd-alerts.yaml`). Route the alerts through Alertmanager to e-mail, chat or a ticketing system. See [Operator guide](operator-guide.md) |
| A live feed of the engine's state in another tool | The console's server-sent events stream, `GET /api/v1/engines/{id}/status/stream`: a `status` event with the engine's status every second. See [API reference](api-reference.md#engines) |
| The same, without the console | The engine's gRPC `WatchStatus` stream (`syncd.v1.EngineControl`), with a control API client certificate. See [API reference](api-reference.md#engine-control-api-grpc) |
| Polling status, dead letters and configuration versions, or starting runs and approvals | The console REST API (`/api/v1`), signed in as an account with the right role |
| A record of who did what | The console's audit log, `GET /api/v1/audit` (admins and auditors), with `GET /api/v1/audit/verify` to check its hash chain |

Some things that look like webhooks are inbound to the engine, not outbound:

- a **Debezium Server** sidecar posts change events to a `debezium` connector's listener;
- the **JDBC bridge** answers the engine's gRPC queries.

Both are sources; see [Connectors](connectors.md).

A script that wants an event-style feed can follow the status stream and act when a value changes
(a target's `up` turning false, `dead_letters` rising, a new entry under `blocked`). Prometheus
alerting does the same with less code and is the recommended route.
