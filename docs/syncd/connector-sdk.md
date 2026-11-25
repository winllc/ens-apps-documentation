# Connector SDK

*In development, not in a release yet. Extensibility pack.*

When syncd has no connector for a system, one can be written by anyone, in any language
with gRPC: a service implementing syncd's connector protocol
(`proto/syncd/connector/v1/connector.proto` in the syncd repository). The engine calls it
as a **source** (reading its entries in full) and as a **target** (writing entries and
group memberships). A connector is configured with the connector type `external`.

The syncd repository ships a reference connector, `sdk/connector-example`, which keeps
its entries in a JSON file and implements every call: start from it.

## The protocol

| Call | What the engine asks |
|---|---|
| `Info` | Name, version, vendor, protocol version 1, and whether it reads, writes and has groups |
| `DiscoverSchema` | Its object types, each with its key attribute and attributes |
| `List` (streaming) | Every entry of an object type: its key, its own id, its attributes, whether it is enabled |
| `Read` | Entries by key, for drift checks |
| `Apply` | Planned changes, one export per entry: add, modify, rename, delete, enable or disable, with the whole entry as wanted |
| `Members`, `ChangeMembers` | A group's members, and members added or removed |

- Entries are named by an object type and a key, sent with every operation together with
  the connector's own id of the entry once it has returned one; a connector never parses
  names.
- An add of a key that exists takes the entry over; a delete of an entry already gone,
  and a member already added or already removed, are fine.
- An entry's error goes in its result; a gRPC status fails the whole call, and
  `UNAVAILABLE` makes the engine treat the connector as down (exports wait in the
  dead-letter queue).
- The engine sends `authorization: Bearer <token>` when the connection has a token.
  Beyond the engine's own host, serve TLS and require a token or a client certificate.

## Configuration

```yaml
connectors:
  - name: billing
    type: external
    connection:
      endpoint: https://billing-connector.corp.example.com:50081
      token: { env: BILLING_CONNECTOR_TOKEN }
      ca_file: /etc/syncd/connector-ca.pem
      # client_cert and client_key: mutual TLS
    objects:                      # as a source: object types, read in full
      - { type: User, anchor: userName }

publications:                     # as a target
  - name: people_to_billing
    view: people
    target: billing
    resource: User                # the object type
    external_id: e.login          # the key
    attributes: { displayName: e.display_name, email: e.mail }
    deprovision: disable
```

Validation refuses an endpoint beyond loopback without `https://` and a token or a client
certificate, and on a publication what the protocol cannot carry (`dn`, containers,
`writeback`, `adopt`, `passwords`, computed groups, references).

## Checking a connector

`syncd connector-test --endpoint <url> [--token-file F] [--group G]` runs a fixed sequence
against a connector and reports each step: Info, DiscoverSchema, add, read, modify, list,
disable, enable, membership add and remove (with `--group`), rename and delete. It needs
no configuration file, writes one test entry and deletes it. **Run it, and send its
output, before asking for support with a connector.**

## Tested, and not yet

Tested end to end against the reference connector (`tests/e2e/external-connector.sh`):
the conformance run, the engine writing to it (adds, a take-over, modifies, memberships,
a leaver, a rename, the inventory) and reading a second instance as a source. No
third-party connector has been run against it yet.

Not built: delta reads and streaming from a connector, passwords, and a WebAssembly
connector (custom WebAssembly functions have no network access).
