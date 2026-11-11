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
| Browser tests | The Playwright smoke test (`console/web/e2e/smoke.spec.ts`, run in CI) finds controls by their role and accessible name (`getByRole`, `getByLabel`), so a control that loses its name or label fails it. It also moves through a metrics chart with the keyboard, finds the engine CPU and memory charts and the dashboard's sparklines by their names, opens the public password page (`/password`, turned off) and joins a record on the Correlation page by its labelled field. These are functional tests, not accessibility audits. The pages added in 0.4.0 (**Integrations**, **Reports**, **Data quality**, the engine page's **Held changes** and **Groups** cards, the Studio's **Quality check** block) are not visited by any browser test, nor are the 0.4.1 additions (the **Support bundle** card, the webhook secret dialog, the per-publication table of the **Passwords** card); 0.4.1 changed no browser test |
| Screen-reader testing | Not done |
| Banner colours | The server refuses any banner whose text-to-background contrast is below 4.5:1; a unit test covers the calculation |

## What the console offers

- **Language and structure**: the page declares `lang="en"`. The header, the main navigation
  (labelled **Main**) and the main content are landmarks. Each page has a level-one heading, and
  cards and dialogs have their own headings.
- **Forms**: every form field has a label tied to it, and a field's hint is linked as its
  description. Buttons that show only an icon have a text name (for example **Reset password of
  …**, **Remove engine**, **Retry …**, **Discard …**); decorative icons are hidden from assistive
  technology. The 0.4.0 pages follow the same pattern:
    - row buttons name what they act on: **Revoke &lt;token&gt;**, **Send a test to &lt;webhook&gt;**,
      **Deliveries of &lt;webhook&gt;**, **Send &lt;report&gt; now**, **Edit …**, **Delete …**,
      **Review group &lt;group&gt;**, **Join &lt;record&gt; to &lt;entity&gt;**;
    - the API token scopes and webhook events are checkboxes with labels and a description each;
      the webhook events are grouped in fieldsets with legends;
    - the rows of the environment editor are named **Key 1**, **Value 1**, **Secret 1**,
      **Remove row 1**, and so on; the correlation checkboxes **Select &lt;entity&gt; for
      &lt;record&gt;**; the Data quality page's filter **Severity**;
    - the public password page (`/password`) labels its fields and marks them for password
      managers (user name, current password, new password), and gives the password policy as the
      new password's description; a refusal is announced as an alert;
    - the webhook secret dialog (0.4.1) labels its field **Secret**, or **Routing key** for
      PagerDuty.
- **Messages**: errors are announced as alerts (`role="alert"`), and other notices (success,
  information, warnings) as status messages.
- **Keyboard**: everything is reached with standard links, buttons and form controls, and the
  keyboard focus has a visible outline. Dialogs are native modal dialogs: focus stays inside while
  one is open, and **Escape** closes it. The confirmations added in 0.4.1 (revoking a token,
  deleting a scheduled report, removing an environment) are the browser's own confirmation box.
- **State**: toggles such as the metrics time window and the visual editor's **Forms** / **Data
  flow** switch say whether they are pressed; the editor's tabs say which is selected; the selected
  item in the configuration editor's list is marked as current; a count of errors on an item is
  read out as text, not only shown as a red icon.
- **Status shown as text**: engine, source, version and dead-letter states are badges with words
  (**running**, **failed**, **draft**, **awaiting approval**), not colour alone. So are the 0.4.0
  additions: held-change reasons (**window**, **canary_failed**, …), HA roles (**active**,
  **passive**), audit severities (**warning**, **alert**), output states (**failing**, **catching
  up**), correlation scores and how each attribute matched (**exact**, **normalized**,
  **similar**), whether an engine's password keys are sealed (**keys sealed**, **password keys
  stored in the clear**), and the dashboard's data-quality counts, which are sentences ("2 checks
  failing with errors · 0 with warnings") as well as red and amber. The golden-test diffs keep their `+` and `-`
  signs beside the green and red.
- **Charts** (live metrics): each chart is an image with a name, can be focused, and is read with
  the arrow keys, **Home** and **End** (**Escape** clears); the values at the chosen point are
  announced as a status message. Each series is named in a legend, and a **Sources** table under
  the charts gives the same rates as numbers. The **Engine process** charts (CPU and memory) work
  the same way, with the current values as text in tiles above them. The series colours are a fixed palette that a comment
  in the stylesheet describes as checked for colour-vision deficiency; no test checks it.
- **Dashboard sparklines**: the small CPU and memory charts on each engine card are images named
  **CPU over the last minute** and **Memory over the last minute**. They cannot be focused or read
  point by point; the current CPU and memory are shown beside them as text.
- **Data quality sparklines**: each check's seven-day line is an image whose name holds the check
  and the seven daily counts ("mail-format: 0, 0, 3, 3, 2, 0, 0 over the last seven days"), so the
  values are read out. It cannot be focused; the current count is in the **Failing** column.
- **Downloads**: the reconciliation report comes as CSV as well as PDF, and an entity's lineage as
  JSON as well as PDF. The CSV and JSON carry everything the PDF does.
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
  green; the difference is visual only. The same goes for the changed fields under **What
  changed** on the configuration editor's **Compare** tab.
- **PDF exports**: the report and lineage PDFs are not tagged (no structure, reading order or
  language for assistive technology), and characters outside Latin-1 print as `?`. Use the CSV or
  JSON instead.
- **Data quality**: a check's name is a button that lists its failing entities below the table; it
  does not say whether the list is open.
- **Action columns**: the last column of the token, webhook, environment, report and group tables
  holds the row's buttons and has an empty header.
- **PagerDuty routing key button**: on a PagerDuty webhook's row the button reads **Routing key**,
  but its accessible name is **New secret for &lt;webhook&gt;**, so a speech-input user saying what
  is shown may not reach it.
- **High CPU and memory**: CPU at 90% or more, and memory at 90% of its limit, are marked only by
  turning the value amber, on the dashboard and the live metrics page.
- **Motion**: loading spinners animate whatever the operating system's reduced-motion setting is.
- **No session warning**: a session ends after its idle timeout without warning; the console then
  asks you to sign in again and returns you to the page you were on.
- **English only.**

## Conformance documents

There are none. The syncd source repository holds no WCAG self-audit, no VPAT and no accessibility
conformance report (ACR), unlike the other applications in the suite. Ask ENS Solutions about plans
for one when a procurement needs it.
