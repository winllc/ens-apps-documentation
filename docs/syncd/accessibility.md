# Accessibility

From syncd 0.5.0 the web console is checked against **WCAG 2.1, levels A and AA**, on every build,
and its makers have written a **self-audit** against those criteria and a **draft accessibility
conformance report** in the VPAT 2.5 international layout (WCAG, Revised Section 508, EN 301 549).
Both are self-assessments: nobody has tested the console with a screen reader or other assistive
technology, or with users, and no accessibility auditor has reviewed them. syncd does **not**
claim conformance: the report lists several criteria as only partially supported (see
[Known limits](#known-limits)).

This page describes syncd 0.7.0 (tag `v0.7.0`, commit `6de2fa1`). The self-audit and the draft
report in the source still describe 0.5.0's console, with a section added in 0.7.0 for the
governance pages; what 0.5.1, 0.5.5, 0.6.0 and 0.7.0 change for accessibility is noted below.
0.5.2, 0.5.4 and 0.6.1 change nothing for accessibility. 0.7.0 adds the
[governance pages](#the-governance-pages), which the automated checks scan on every route in
both themes; **the screen-reader pass of the reviewer's and the requester's pages, which the
plan asks for, has not been done**.

## How it is checked

| Check | Status |
|---|---|
| Automated accessibility scan | **axe-core 4.14** through Playwright, with the rule tags `wcag2a`, `wcag2aa`, `wcag21a` and `wcag21aa`, in Chromium, on every route of the UI and every dialog, each in the light and the dark theme: 104 checks (64 page states, 40 dialog states), 208 scans (`console/web/e2e/a11y.spec.ts`, and `x509.spec.ts` for the sign-in page with certificate sign-in). The build fails on a violation axe rates serious or critical, and a test fails for a route that no check visits. The run at 0.5.0 reports no violation of any impact. 0.5.1 adds a check of the dialog that now confirms a password reset before the one-time password is shown; the counts above are 0.5.0's. 0.7.0 adds the governance pages: 33 page states, each in both themes, and three of their dialogs (see [The governance pages](#the-governance-pages)) |
| Exclusions from the scan | The insides of the Monaco code editors and React Flow's own canvas, controls and attribution, which the libraries generate. Their names, keyboard behaviour and the alternatives around them are tested instead; colour contrast inside them is **not** verified |
| Keyboard and behaviour tests | In the same suite: the skip link, page titles and the announcement of a navigation, status regions, every dialog (its name, focus moved in and kept in, **Escape**, focus given back), the notice before sign-in, adding and removing a role assignment, unlocking an account, the Promote dialog, leaving the code editors, the data flow designer without a pointer, focus order against visual order on eight pages, reflow at 320 and 640 CSS pixels on seventeen pages and two dialogs, and text spacing on five pages |
| Contrast | Calculated for the palette steps the UI uses for text, field borders and the focus ring: body text 17:1 (light) and 16:1 (dark), secondary text 4.8:1 and 6.7:1, field borders 4.8:1 and 6.7:1, the focus ring 5.3:1 and 6.7:1 |
| Screen-reader and assistive-technology testing | **Not done**: no NVDA, JAWS, VoiceOver, TalkBack or Orca, no magnifier, no speech input, no switch. Names, roles and live regions were checked in the accessibility tree through Playwright and axe, which is not the same as hearing them |
| Browsers | Chromium only (Playwright 1.56). Firefox, Safari, and high-contrast and forced-colours modes were not tried |
| Roles and states | Scanned as an administrator of everything, and as an operator of one engine, a viewer, a designer and an auditor of everything, and an auditor and an administrator of one environment. Not scanned: the governance roles (a person's own pages are scanned as the administrator, not as a `participant`), other roles at the scope of one engine or environment, the roles dialog of an account that signs in through an identity provider, and states the test fixture does not produce (an HA pair, drift findings, quarantined entities, the password synchronisation card, expiring certificates, a FIPS build) |
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
  messages. From 0.5.5 a request the console has not answered within ten minutes is given up,
  and the page says so ("The console did not answer within 10 minutes ...") instead of waiting
  for good.
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
  poppler, **not** with veraPDF, PAC, Acrobat or a screen reader, and make no PDF/UA claim. From
  0.5.5 a PDF report holds at most 20,000 rows: a larger one is refused as PDF (download CSV or
  Excel, which have no such limit), and a scheduled one is cut there, saying so in its mail.
  From 0.7.0 the governance reports, and a campaign's evidence report, come as CSV, Excel or
  PDF too; a campaign report is refused as PDF above 20,000 items and as Excel above 200,000,
  and comes as CSV at any size.
- **Light and dark, and motion**: the console follows the operating system's light or dark setting
  and, from 0.5.0, its reduced-motion setting (spinners stand still, transitions are off).
- **Banners**: the top and bottom [banners](admin-guide.md#banners) are text in landmarks that
  assistive technology reads, and their colours must contrast at least 4.5:1 (WCAG AA for normal
  text). White on yellow, for example, is refused.
- **Notice before sign-in**: with the consent banner on, the notice is shown alone, scrolls from the
  keyboard, and is acknowledged with a button.
- **Confirmations** (from 0.5.1): leaving an editor (the configuration editor, the Studio) with
  unsaved changes opens a **Leave without saving?** dialog (**Stay**, **Leave and discard**), and
  **Disable** and **Reset password** on the **Users** page ask first, in the same native modal
  dialogs as the rest. A sign-out the console did not confirm is said in an alert, **Not signed
  out**.
- **Lists of DNs** (from 0.5.1): fields whose values hold commas, such as **Groups never touched**,
  are text areas with one value per line instead of a comma-separated line.
- **Dashboard** (from 0.5.0): the message of the day is plain text with its line breaks kept, and
  refused sign-in attempts since the previous sign-in are a warning with its count in words.
- **The DSML connector's form** (from 0.6.0; see [Air-gapped synchronisation](air-gap.md)) is
  built from the same labelled fields, hints and drop-downs as the other connectors' forms; its
  SFTP fields appear only once the location starts with `sftp://`, and *Browse the files* is the
  same dialog as for CSV files. No accessibility check opens it (none of the test fixtures has a
  `dsml` connector), so it has not been scanned.

## The governance pages

From 0.7.0, with the Governance pack, the console has two more groups of pages: the
administrators' (`/governance/...`: settings, catalog, access search, reports, risk, access
reviews, requests, workflows, roles, policies, violations, lifecycle, fulfilment,
assignments, workgroups, scopes, the parallel run, connected products) and everyone's own
(`/my/...`: my access, my reviews and a review, requesting access, my requests and a request,
my approvals, while I am away, my team, my policy violations, my tasks, my password). They
are built from the same components as the rest of the console and fall under the same checks.

| Check | Status |
|---|---|
| Automated scan | Every one of these routes, in the light and the dark theme (33 page states, `console/web/e2e/a11y.spec.ts`); the route-list test fails for a new route no check visits. Three of their dialogs are scanned: a new workgroup, a new governance scope, connecting a product |
| Dialogs not scanned | Deciding a review item, delegating items, approving or rejecting a request, asking for access with a reason, creating a campaign, editing a policy or a role, an exception, an emergency termination. They use the dialog component the other dialog checks cover, but their content is not checked |
| As whom | A person's own pages are scanned as the administrator, who is also a person of the test fixture's identity view; not as a `participant` |
| Screen reader | **Not done.** The plan asks for a pass with NVDA and Firefox, and VoiceOver and Safari, of the reviewer's and the requester's pages first (my reviews and a review, delegating items, requesting access, my requests, my approvals), since they have the most users. The protocol is written in the self-audit's "The governance pages"; the pass needs a person with a screen reader and has not happened |

## Known limits

The criteria the self-audit and the draft report give as **partially supported** at 0.5.0:

| Criterion | What falls short |
|---|---|
| 1.3.1 Info and Relationships | The tab lists have `tab` roles and the selected state, but no tab panels tied to them; some explanations are only browser tooltips (`title`), shown on hover |
| 1.4.3 Contrast (Minimum), 1.4.11 Non-text Contrast | Not verified inside the Monaco editors and React Flow's controls, nor for text and lines drawn in SVG (chart labels and lines, the dependency graph), which axe does not measure; the unselected state of tabs and the time-window buttons |
| 1.4.10 Reflow | Data tables scroll inside their own frame, and the flow designer, the dependency graph, the charts and the YAML editor need two dimensions; whether the last three stay usable at 320 CSS pixels was not judged |
| 2.2.1 Timing Adjustable | A session ends after its idle timeout (30 minutes by default) without a warning and without a way to extend it from the page; from 0.5.1 a page that refreshes itself does not count as activity, so an unattended tab reaches the timeout sooner than in 0.5.0. Text not saved in the configuration editor is lost when the session ends (leaving the page on purpose asks first, from 0.5.1). After signing in again the user is back on the same page |
| 2.2.2 Pause, Stop, Hide | An engine's status and the metrics charts update once a second and cannot be paused; from 0.5.1 the **Engines** page refreshes every ten seconds instead |
| 2.4.5 Multiple Ways | No search across pages and no site map; an engine's sub-pages are reached from the engine's page |
| 4.1.3 Status Messages | Not every inline result was audited, and counts that change with the live status are not announced |

Also:

- **No assistive-technology testing**, one browser, and not every role at every scope (see
  [How it is checked](#how-it-is-checked)).
- **Governance** (from 0.7.0): no screen-reader pass of the reviewer's and the requester's
  pages, most governance dialogs not scanned, and a person's own pages not scanned as a
  participant (see [The governance pages](#the-governance-pages)).
- **PDF exports**: characters outside Latin, Greek and Cyrillic (Chinese, Japanese, Korean, Arabic,
  Hebrew, the Indic scripts, Thai, emoji) are drawn and read as U+FFFD; there are no bookmarks; the
  table text is 7 points; and the files were not validated against PDF/UA-1. The CSV, Excel and
  JSON exports carry every character.
- **Data quality**: a check's name is a button that lists its failing entities below the table; it
  does not say whether the list is open.
- **The DSML connector's form** (from 0.6.0) has two fields with the same label, **Files read**:
  the pattern of the files read (a text field with the placeholder `*.xml`), and what is done
  with files once read (a drop-down with a hint). Their kinds tell them apart, but their names do
  not.
- **Target sizes**: native checkboxes are 13 pixels and the tabs of an expression editor about 16;
  WCAG 2.1 AA sets no minimum, but WCAG 2.2's 24-pixel rule (2.5.8) would not be met without
  spacing.
- **English only.**
