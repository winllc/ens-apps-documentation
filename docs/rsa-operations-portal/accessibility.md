# Accessibility

The Portal is checked against WCAG 2.0, 2.1 and 2.2 levels A and AA on every build. It does not claim
conformance: the self-assessment against WCAG 2.1 AA and the Accessibility Conformance Report
(VPAT 2.5) are drafts for ENS Solutions to review, not a published conformance claim. An external
audit is still to come.

## How it is checked

Every build of a branch or pull request runs axe-core (the `accessibility` CI job, Playwright with
Chromium) against the Portal's main pages, signed in as an administrator and anonymously, in the
read-only demo mode with its sample data and a stand-in Connector. It fails on a serious or critical
violation of the WCAG A and AA rules.

## Known limits

- The interface uses Bootstrap 4, which is end of life; moving off it is not in this release.
- The Portal is English only; there are no translations yet.

## Conformance documents

Kept in the Portal's source repository under `docs/accessibility/`:

| Document | Status |
|---|---|
| `wcag-2.1-aa-self-audit.md` | The self-assessment against WCAG 2.1 AA: a draft for ENS Solutions to review |
| `vpat-2.5-rsa-operations-portal-draft.md` | The Accessibility Conformance Report (VPAT 2.5): a **draft**, not a published conformance claim |

Ask ENS Solutions for the current copies when a procurement needs them.
