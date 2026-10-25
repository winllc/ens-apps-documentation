# Accessibility

The web client is built and checked against WCAG 2.1 levels A and AA. It does not yet claim
conformance: the accessibility conformance report is a draft self-assessment for ENS Solutions to
review before publication.

## How it is checked

Every build runs axe-core against the web client (the `accessibility` CI job: 23 pages and 3
dialogs, in the light and dark colour schemes, plus keyboard checks). A serious or critical
violation fails the build.

## What changed for accessibility

- Darker secondary text, and underlined links in text.
- Form-field borders at 3:1 contrast.
- A skip link, and a title for every page.

## Conformance documents

Kept in the portal's source repository under `docs/accessibility/`:

| Document | Status |
|---|---|
| `wcag-2.1-aa-self-audit.md` | A self-audit against WCAG 2.1 AA |
| `vpat-2.5-draft.md` | A **draft** VPAT 2.5: a self-assessment for ENS Solutions to review before publication |

Ask ENS Solutions for the current copies when a procurement needs them.
