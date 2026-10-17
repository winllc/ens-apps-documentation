# Wave 2 working notes (not part of the published site)

Working notes for Wave 2 ("safe to sell") of the ENS production-readiness plan. The plan and its
status are in the readiness report: https://claude.ai/code/artifact/913fe455-e80a-431a-9eaa-f8d5498f1359

- `baseline-spec.md`: the shared platform baseline every product implements to the same settings,
  audit events and metric names: security headers, the consent banner, session controls,
  observability, supply chain, FIPS, and X.509.
- `status.md`: where the work stood when it was paused.

## Paused 2026-10-17: work in progress, pushed as WIP branches (no PRs yet)
| Repo | Branch | Scope |
|---|---|---|
| cert-alert | `claude/wave2-platform` | Baseline, CA-05/06/07/10/11/12/17 |
| directory-services-portal | `claude/wave2-platform` | Baseline, DSP-08 to DSP-13, DSP-17 |
| in-n-out-work | `claude/wave2-platform` | Baseline, RR-11 (Flyway), RR-16, RR-20 (rebased onto the purged main) |
| rsa-operations-portal | `claude/wave2-platform` | Baseline, RSA-09/10/12/15/16/18 |
| rsa-operations-portal | `claude/wave2-connector` | RSA-03 (TLS hop), RSA-04, the Connector health check |
| ens-products-page | `claude/wave2-fips` | Item 16: ECDSA P-256 license signatures alongside Ed25519 |

Each branch ends in a "WIP" commit made when the work was stopped. It may not build yet.
Still to come, in round 2:
- ReadyRoom RR-07/RR-12, on its platform branch;
- RSA-05/06/07, on the RSA platform branch;
- the docs for item 23 and all of Wave 2.
