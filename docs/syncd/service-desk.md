# Service-desk integration

This page describes syncd 0.7.0 (tag `v0.7.0`, commit `6de2fa1`), the first release with the
request API for tools, with the rest of [Governance](governance.md). The review before the
release changed nothing in this flow; it held the same routes to the scope of a scoped
governance administrator who calls them (see
[Delegated administration](governance.md#delegated-administration)).

A service-desk tool can take access requests in its own forms and hand them to syncd, which
runs the approvals, the policy checks and the provisioning, and tells the tool when it is
done. The tool needs one API token with the `governance:request` scope; the approvals still
happen in syncd, by the people its workflows name. No tool approves on anyone's behalf.
Asking needs the Governance pack in the license (the evaluation enables every pack);
reading a request back does not.

## The flow

1. **Find what can be asked for.** `GET /api/v1/governance/catalog?q=<text>&for=<login>`
   lists requestable entitlements and roles (`kind`, `id`, `system_name`, `name`,
   `description`, `privileged`, `max_duration_days`, `requires_end_date`), marking what the
   person already holds (`held`) or waits for (`pending`).
2. **Create the request with the ticket number.**

    ```http
    POST /api/v1/governance/requests
    Authorization: Bearer sdt_…
    Content-Type: application/json

    {
      "on_behalf_of": "jdoe",
      "beneficiary_login": "jdoe",
      "reason": "Needs the finance share for the quarter close",
      "ticket": "RITM0012345",
      "items": [{ "entitlement_id": "6f1c…" , "ends_at": "2027-03-31T00:00:00Z" }]
    }
    ```

    `on_behalf_of` is the person asking, by login: the request is theirs, with their rights
    (a manager may ask for a direct report). It records both the person and the token
    (`via_token`), and the audit trail's `governance.request.created` names the token. The
    answer is the request, with its `id`.
3. **Follow it.** Either poll `GET /api/v1/governance/requests/{id}` (its `state`; each
   item's `approvals` so far and `policy_hits`; a request token reads only the requests it
   made) or subscribe a [webhook](webhooks.md) to the governance events. Each event carries
   the request id, the ticket reference and logins, so the tool can find its ticket:

    | Event | The tool might |
    |---|---|
    | `governance.request-created` | Note syncd's request id on the ticket |
    | `governance.task-created` | Show who is asked to approve |
    | `governance.request-approved` | Move the ticket to "being fulfilled" |
    | `governance.request-rejected` | Close the ticket as rejected |
    | `governance.request-completed` | Close the ticket (`data.state`: `completed`, `failed` or `cancelled`) |

4. **Close the ticket** on `governance.request-completed`.

The token's rate limit is the console's per-token limit (`CONSOLE_API_REQUESTS_PER_MINUTE` (300)).
Verify each webhook delivery's signature as [Webhooks](webhooks.md#verifying-a-delivery)
describes.

## Example: ServiceNow

This is an outline for a ServiceNow administrator, not code syncd ships.

- **Outbound:** on a catalog item's submission, a Flow Designer action (or a business rule
  on `sc_req_item`) calls `POST /api/v1/governance/requests` through a REST Message with the
  token in a Connection & Credential alias, sends the requester's `user_name` as
  `on_behalf_of`, the item's number (`RITM…`) as `ticket`, and stores the answer's `id` in a
  field of the requested item.
- **Inbound:** a Scripted REST API receives syncd's webhook. It checks the
  `X-Syncd-Signature` header against the endpoint's signing secret, finds the requested item
  by `data.ticket`, and on `governance.request-completed` sets its state to Closed Complete
  (or Closed Incomplete when `data.state` is `failed`), with `governance.request-rejected`
  closing it as rejected.
- **Without inbound access:** a scheduled job polls `GET /api/v1/governance/requests/{id}`
  for open items instead.

## Tested, and not yet

The request API and the events were tested against PostgreSQL with a stand-in engine (a
request made through a token, read back with it, approved, granted, and the events written in
the change's transaction). It was not run against a real ServiceNow instance.
