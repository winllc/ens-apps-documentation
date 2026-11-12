# Accessibility

From syncd 0.5.0 the web console is checked against **WCAG 2.1, levels A and AA**, on every build,
and its makers have written a **self-audit** against those criteria and a **draft accessibility
conformance report** in the VPAT 2.5 international layout (WCAG, Revised Section 508, EN 301 549).
Both are self-assessments: nobody has tested the console with a screen reader or other assistive
technology, or with users, and no accessibility auditor has reviewed them. syncd does **not**
claim conformance: the report lists several criteria as only partially supported (see
[Known limits](#known-limits)).

This page describes syncd 0.5.0 (commit `a9d17c6`).

## How it is checked

| Check | Status |
|---|---|
| Automated accessibility scan | **axe-core 4.14** through Playwright, with the rule tags `wcag2a`, `wcag2aa`, `wcag21a` and `wcag21aa`, in Chromium, on every route of the UI and every dialog, each in the light and the dark theme: 104 checks (64 page states, 40 dialog states), 208 scans (`console/web/e2e/a11y.spec.ts`, and `x509.spec.ts` for the sign-in page with certificate sign-in). The build fails on a violation axe rates serious or critical, and a test fails for a route that no check visits. The run at 0.5.0 reports no violation of any impact |
| Exclusions from the scan | The insides of the Monaco code editors and React Flow's own canvas, controls and attribution, which the libraries generate. Their names, keyboard behaviour and the alternatives around them are tested instead; colour contrast inside them is **not** verified |
| Keyboard and behaviour tests | In the same suite: the skip link, page titles and the announcement of a navigation, status regions, every dialog (its name, focus moved in and kept in, **Escape**, focus given back), the notice before sign-in, adding and removing a role assignment, unlocking an account, the Promote dialog, leaving the code editors, the data flow designer without a pointer, focus order against visual order on eight pages, reflow at 320 and 640 CSS pixels on seventeen pages and two dialogs, and text spacing on five pages |
| Contrast | Calculated for the palette steps the UI uses for text, field borders and the focus ring: body text 17:1 (light) and 16:1 (dark), secondary text 4.8:1 and 6.7:1, field borders 4.8:1 and 6.7:1, the focus ring 5.3:1 and 6.7:1 |
| Screen-reader and assistive-technology testing | **Not done**: no NVDA, JAWS, VoiceOver, TalkBack or Orca, no magnifier, no speech input, no switch. Names, roles and live regions were checked in the accessibility tree through Playwright and axe, which is not the same as hearing them |
| Browsers | Chromium only (Playwright 1.56). Firefox, Safari, and high-contrast and forced-colours modes were not tried |
| Roles and states | Scanned as an administrator of everything, and as an operator of one engine, a viewer, a designer and an auditor of everything, and an auditor and an administrator of one environment. Not scanned: other roles at the scope of one engine or environment, the roles dialog of an account that signs in through an identity provider, and states the test fixture does not produce (an HA pair, drift findings, quarantined entities, the password synchronisation card, expiring certificates, a FIPS build) |
| Banner colours | The server refuses any banner whose text-to-background contrast is below 4.5:1; a unit test covers the calculation |

axe decides perhaps a third to a half of the success criteria on its own. Whether a label is a
good label, whether an order makes sense, whether an error message helps, were judged by reading
the code.

The checks found, and 0.5.0 fixed: insufficient contrast of secondary text (mostly in the dark
theme), a malformed definition list on the dashboard, the PagerDuty **Routing key** button and
an account's **change** roles link named differently from what they show, scrolling regions (the
audit log's table, the dependency graph, the notice before sign-in) that the keyboard could not
reach, a refused Audit log page without its heading, and focus lost while the Promote dialog
waited.

## Conformance documents

In the syncd source repository, under `docs/accessibility/`:

| Document | What it is |
|---|---|
| `wcag-2.1-aa-self-audit.md` | The self-audit: each WCAG 2.1 A and AA success criterion with its status (Supports, Partially supports, Does not support, Not applicable), how it was checked (axe rule, test or reading), and remarks; the scan's results, its exclusions, the exported documents, and the known limits in one list |
| `vpat-2.5-draft.md` | A **draft** Accessibility Conformance Report, VPAT 2.5 international edition: WCAG 2.1 A and AA (and so 2.0), Revised Section 508 and EN 301 549 V3.2.1, with exported PDF files answered under EN 301 549's chapter on non-web documents. Not reviewed by an auditor, not a certification and not a legal claim of conformance |

Both cover the console's web interface and the files it exports, not the `syncd` and `syncdctl`
command lines or the engine's API. Contact for both is the project's issue tracker; there is no
separate accessibility contact. Ask ENS Solutions when a procurement needs a reviewed report.

## What the console offers

- **Language and structure**: the page declares `lang="en"`. The banners, the header, the main
  navigation (labelled **Main**), the main content and the footer are landmarks. Each page has one
  level-one heading, and cards and dialogs have their own headings.
- **Skip link** (from 0.5.0): the first **Tab** on any page reaches **Skip to main content**, which
  moves the focus to the main content.
- **Page titles** (from 0.5.0): each page's title is its heading and "syncd console" ("Audit log ·
  syncd console"), and a navigation inside the console is announced.
- **Forms**: every form field has a label tied to it, and a field's hint is linked as its
  description; groups of checkboxes (token scopes, webhook events, the roles of a new account) are
  named groups. Buttons that show only an icon have a text name (for example **Reset password of
  …**, **Remove engine**, **Retry …**, **Discard …**); decorative icons are hidden from assistive
  technology. Row buttons name what they act on: **Revoke &lt;token&gt;**, **Send a test to
  &lt;webhook&gt;**, **Edit ops-chat**, **Change roles of &lt;account&gt;**, **Review group
  &lt;group&gt;**, **Join &lt;record&gt; to &lt;entity&gt;**. Since 0.5.0 a PagerDuty webhook's
  **Routing key** button is named **Routing key of &lt;webhook&gt;**, matching what it shows. Sign-in
  and password fields carry `autocomplete` for password managers.
- **Messages**: errors are announced as alerts (`role="alert"`), and other notices (success,
  information, warnings, the count of audit entries shown, a promotion's result) as status
  messages.
- **Keyboard**: everything is reached with standard links, buttons and form controls, and the
  keyboard focus has a visible outline, fields included. Dialogs are native modal dialogs, named by
  their title: focus moves in and stays inside while one is open, **Escape** closes it, and focus
  goes back where it was. A frame that scrolls (a wide table, the notice before sign-in) takes a tab
  stop while its content overflows.
- **Code editors**: the YAML editor and the expression editors use **Tab** for indentation. Press
  **Escape**, then **Tab** (or **Shift+Tab**), to leave an editor; the page says so beside each one.
- **Data flow designer**: **Tab** reaches nodes and edges, **Enter** selects, the arrow keys move a
  selected node, and **Delete** removes a selected source edge (it no longer removes a node). The
  **Connections** list under the graph does the same edits as forms: add an object to a view,
  publish a view to a directory, remove a source. Everything can also be done in the **Forms** view
  and the **YAML** tab.
- **Dependency graph**: "The same as a list" under the picture has its content in text.
- **Expression editors**: CEL expressions can be built as **Blocks** (made of drop-downs, fields and
  buttons) as well as typed in the code editor.
- **State**: toggles such as the metrics time window and the visual editor's **Forms** / **Data
  flow** switch say whether they are pressed; the editor's tabs say which is selected; the selected
  item in the configuration editor's list is marked as current; a count of errors on an item is
  read out as text, not only shown as a red icon.
- **Status shown as text**: engine, source, version and dead-letter states, held-change reasons, HA
  roles, audit severities, output states, correlation scores, whether an engine's password keys are
  sealed, and the dashboard's data-quality counts are words, not colour alone. From 0.5.0 high CPU
  and memory carry a warning sign named "High", old and new values in an entity's history and on
  the **Compare** tab are marked up as deleted and inserted text with "was" and "now", and the one
  link inside a sentence is underlined.
- **Charts** (live metrics): each chart is an image with a name, can be focused, and is read with the
  arrow keys, **Home** and **End** (**Escape** clears); the values at the chosen point are announced
  as a status message. Each series is named in a legend and labelled at the line's end, and a table
  under the charts gives the same rates as numbers.
- **Sparklines**: the dashboard's CPU and memory sparklines are images with a name, with the current
  values beside them as text. The **Data quality** page's sparklines (the last seven days and, from
  0.5.0, the last 24 hours) are images whose name reads out every count.
- **Downloads**: the reconciliation report comes as CSV, Excel (from 0.5.0) or PDF, and an entity's
  lineage as JSON or PDF. From 0.5.0 the PDFs are **tagged** (PDF 1.7 with a structure tree in
  reading order, headings, tables with header cells, the language and the title; page furniture
  marked as artifacts) and print Latin, Greek and Cyrillic text. They were checked with qpdf and
  poppler, **not** with veraPDF, PAC, Acrobat or a screen reader, and make no PDF/UA claim.
- **Light and dark, and motion**: the console follows the operating system's light or dark setting
  and, from 0.5.0, its reduced-motion setting (spinners stand still, transitions are off).
- **Banners**: the top and bottom [banners](admin-guide.md#banners) are text in landmarks that
  assistive technology reads, and their colours must contrast at least 4.5:1 (WCAG AA for normal
  text). White on yellow, for example, is refused.
- **Notice before sign-in**: with the consent banner on, the notice is shown alone, scrolls from the
  keyboard, and is acknowledged with a button.

## Known limits

The criteria the self-audit and the draft report give as **partially supported** at 0.5.0:

| Criterion | What falls short |
|---|---|
| 1.3.1 Info and Relationships | The tab lists have `tab` roles and the selected state, but no tab panels tied to them; some explanations are only browser tooltips (`title`), shown on hover |
| 1.4.3 Contrast (Minimum), 1.4.11 Non-text Contrast | Not verified inside the Monaco editors and React Flow's controls, nor for text and lines drawn in SVG (chart labels and lines, the dependency graph), which axe does not measure; the unselected state of tabs and the time-window buttons |
| 1.4.10 Reflow | Data tables scroll inside their own frame, and the flow designer, the dependency graph, the charts and the YAML editor need two dimensions; whether the last three stay usable at 320 CSS pixels was not judged |
| 2.2.1 Timing Adjustable | A session ends after its idle timeout (30 minutes by default) without a warning and without a way to extend it from the page; text not saved in the configuration editor is lost. After signing in again the user is back on the same page |
| 2.2.2 Pause, Stop, Hide | Engine status and the metrics charts update once a second and cannot be paused |
| 2.4.5 Multiple Ways | No search across pages and no site map; an engine's sub-pages are reached from the engine's page |
| 4.1.3 Status Messages | Not every inline result was audited, and counts that change with the live status are not announced |

Also:

- **No assistive-technology testing**, one browser, and not every role at every scope (see
  [How it is checked](#how-it-is-checked)).
- **PDF exports**: characters outside Latin, Greek and Cyrillic (Chinese, Japanese, Korean, Arabic,
  Hebrew, the Indic scripts, Thai, emoji) are drawn and read as U+FFFD; there are no bookmarks; the
  table text is 7 points; and the files were not validated against PDF/UA-1. The CSV, Excel and
  JSON exports carry every character.
- **Data quality**: a check's name is a button that lists its failing entities below the table; it
  does not say whether the list is open.
- **Target sizes**: native checkboxes are 13 pixels and the tabs of an expression editor about 16;
  WCAG 2.1 AA sets no minimum, but WCAG 2.2's 24-pixel rule (2.5.8) would not be met without
  spacing.
- **English only.**
