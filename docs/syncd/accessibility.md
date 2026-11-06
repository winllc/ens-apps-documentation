# Accessibility

The syncd web console does **not** claim conformance with WCAG 2.1 or Section 508, and does not
yet state a target level. Its code uses accessible HTML and ARIA in many places (listed below), but
nothing checks the console for accessibility on each build, it has not been tested with screen
readers, and there is no self-audit or accessibility conformance report.

## How it is checked

| Check | Status |
|---|---|
| Automated accessibility scan (axe-core or similar) | **None.** The console's CI runs no accessibility scan |
| Accessibility lint rules | **None** |
| Browser tests | The Playwright smoke test (`console/web/e2e/smoke.spec.ts`, run in CI) finds controls by their role and accessible name (`getByRole`, `getByLabel`), so a control that loses its name or label fails it. It also moves through a metrics chart with the keyboard. These are functional tests, not accessibility audits |
| Screen-reader testing | Not done |
| Banner colours | The server refuses any banner whose text-to-background contrast is below 4.5:1; a unit test covers the calculation |

## What the console offers

- **Language and structure**: the page declares `lang="en"`. The header, the main navigation
  (labelled **Main**) and the main content are landmarks. Each page has a level-one heading, and
  cards and dialogs have their own headings.
- **Forms**: every form field has a label tied to it, and a field's hint is linked as its
  description. Buttons that show only an icon have a text name (for example **Reset password of
  …**, **Remove engine**, **Retry …**, **Discard …**); decorative icons are hidden from assistive
  technology.
- **Messages**: errors are announced as alerts (`role="alert"`), and other notices (success,
  information, warnings) as status messages.
- **Keyboard**: everything is reached with standard links, buttons and form controls, and the
  keyboard focus has a visible outline. Dialogs are native modal dialogs: focus stays inside while
  one is open, and **Escape** closes it.
- **State**: toggles such as the metrics time window and the visual editor's **Forms** / **Data
  flow** switch say whether they are pressed; the editor's tabs say which is selected; the selected
  item in the configuration editor's list is marked as current; a count of errors on an item is
  read out as text, not only shown as a red icon.
- **Status shown as text**: engine, source, version and dead-letter states are badges with words
  (**running**, **failed**, **draft**, **awaiting approval**), not colour alone.
- **Charts** (live metrics): each chart is an image with a name, can be focused, and is read with
  the arrow keys, **Home** and **End** (**Escape** clears); the values at the chosen point are
  announced as a status message. Each series is named in a legend, and a **Sources** table under
  the charts gives the same rates as numbers. The series colours are a fixed palette that a comment
  in the stylesheet describes as checked for colour-vision deficiency; no test checks it.
- **Data flow alternative**: the configuration editor's **Data flow** graph is edited by dragging,
  but everything it does can also be done in the **Forms** view and in the **YAML** tab.
- **Expression editors**: CEL expressions can be built as **Blocks** (made of drop-downs, fields and
  buttons) as well as typed in the code editor.
- **Light and dark**: the console follows the operating system's light or dark setting.
- **Banners**: the top and bottom [banners](admin-guide.md#banners) are text, marked as notes that
  assistive technology reads, and their colours must contrast at least 4.5:1 (WCAG AA for normal
  text). White on yellow, for example, is refused.

## Known limits

- **No skip link** to the main content.
- **One page title**: every page's browser title is "syncd console"; it does not change from page
  to page.
- **Dialog names**: a dialog's title is a heading inside it, but is not tied to the dialog as its
  accessible name.
- **Tabs**: the configuration editor's tabs are marked up as tabs but are moved between with **Tab**,
  not the arrow keys.
- **Code editors**: the YAML editor, the compare view and the CEL editor are Monaco editors, with
  Monaco's own keyboard and screen-reader behaviour. In them, **Tab** inserts indentation rather than
  leaving the editor.
- **Data flow graph**: adding a source or a publication by dragging needs a pointer; use the forms
  instead.
- **Entity history**: an attribute's old value is shown struck through in red and the new one in
  green; the difference is visual only.
- **Motion**: loading spinners animate whatever the operating system's reduced-motion setting is.
- **No session warning**: a session ends after its idle timeout without warning; the console then
  asks you to sign in again and returns you to the page you were on.
- **English only.**

## Conformance documents

There are none. The syncd source repository holds no WCAG self-audit, no VPAT and no accessibility
conformance report (ACR), unlike the other applications in the suite. Ask ENS Solutions about plans
for one when a procurement needs it.
