# Accessibility

ReadyRoom **targets** WCAG 2.1 level AA (Section 508). It does not yet claim conformance: the
accessibility conformance report is a draft for ENS Solutions to complete, and the self-audit lists
what still falls short, including keyboard gaps.

## How it is checked

Every push runs axe-core (the WCAG 2.0 and 2.1 A and AA rules) through Playwright against the main
pages, at desktop and 320-pixel widths, as an administrator, a manager and a user (the
`accessibility` CI job). A serious or critical violation fails the build.

## Known limits

- The interface is English only; translation is planned.
- Repairs to what third-party widgets draw (tables, calendars) are applied by ReadyRoom's own
  scripts; the self-audit records where they still fall short.

## Conformance documents

Kept in the ReadyRoom source repository under `docs/accessibility/`:

| Document | Status |
|---|---|
| `wcag-2.1-aa-self-audit.md` | The self-audit: what was fixed and what still falls short |
| `vpat-2.5-draft.md` | A **draft** conformance report (ITI VPAT 2.5, 508 edition) for ENS Solutions to complete |

Ask ENS Solutions for the current copies when a procurement needs them.

## Changed defaults

The calendar colours of organization events (`APPLICATION_CALENDARGLOBALEVENTCOLOR`, was `orange`,
now `#a84400`) and of requested statuses (`APPLICATION_CALENDARREQUESTEDEVENTCOLOR`, was `gray`, now
`#5c636a`) are darker, so their white text is readable. Colours you configured are kept.
