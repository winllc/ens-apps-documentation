# Accessibility

CertAlert **targets** WCAG 2.1 level AA (Section 508). It does not yet claim conformance: the
accessibility conformance report is a draft, and screen-reader testing is the main step still to do.

## How it is checked

Every page is scanned with axe-core on every build (the `a11y` CI job), and the build fails on a
serious or critical violation.

## What the pages offer

- A **Skip to main content** link first in the tab order, landmarks (banner, navigation, main, the
  classification regions) and one level-one heading per page.
- Text, badges, field edges and focus rings at WCAG contrast; links in text are underlined.
- Every control named and operable from the keyboard. The search tables' rows open with a button
  that says whether the row is open.
- Result counts, job status and form messages announced as status messages.
- A warning two minutes before the idle timeout ends the session, with **Stay signed in**.
- The [classification banner](configuration.md#classification-banner-cert-alertbanner), where used,
  is real text that assistive technology reads.

## Conformance documents

Kept in the CertAlert source repository under `docs/accessibility/`:

| Document | Status |
|---|---|
| `wcag-2.1-aa-self-audit.md` | A self-audit against WCAG 2.1 AA: what was checked, what was fixed, what still falls short |
| `vpat-2.5-rev508-draft.md` | A **draft** Accessibility Conformance Report in the ITI VPAT 2.5 Rev 508 format, for ENS Solutions to review before it is published |

Ask ENS Solutions for the current copies when a procurement needs them.

## Upgrading

The page title is now each page's `h1` and card titles are `h2` (the look is unchanged). Custom CSS
that targeted `h2.page-title` or `h3.card-title` needs the new element.
