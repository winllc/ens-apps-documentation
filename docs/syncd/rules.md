# Computed attributes and rules

Every transformation in syncd is an expression in **CEL** (Common Expression Language):
attribute flows from sources, join keys, computed attributes, publication filters and
mappings, DN values and group membership. The engine compiles each expression once, when
a configuration is loaded, into native code that reads attributes directly, so rules run
at pipeline speed.

This page describes the rule language, the function library, computed attributes and
their test cases, and rule packages: functions and lookup tables shared between
configurations. The YAML keys are listed in
[Configuration](configuration.md#views-views); where the values come from is in
[Connectors](connectors.md).

## Where expressions are used

Each expression reads one root variable: `source`, the staged record of one source; `e`,
the merged entity; or, for a group's owner, `group`.

| Where | Reads | Lookup tables | `unique()`, `unique_within()` | Other entities (`ref()` ...) |
|---|---|---|---|---|
| View source `flows` | `source` | Yes | No | No |
| Join rule `source` and `when` | `source` | Yes | No | No |
| Correlation hint `source` (`correlation.suggest`) | `source` | Yes | No | No |
| Computed attribute `steps` and `output` | `e`, step variables | Yes | Yes | Yes |
| Data-quality `check` and `unique` | `e` | Yes | No | Yes |
| Publication `filter`, `attributes`, `dn_attributes`, `external_id` | `e` | Yes | No | No |
| Publication `passwords.initial.from`, `licenses.assign`, and the Active Directory conveniences' expressions | `e` | Yes | No | No |
| Computed group `filter` | `e` | Yes | No | No |
| Computed group `owner_from` | `group` (`group.name`, `group.dn`, `group.members`) | No | No | No |

[Package functions](#rule-packages) can be called in any of them.

```yaml
flows:
  uid: lower(trim(source.USERID))            # source scope
computed:
  - name: displayName
    output: e.givenName + " " + e.sn         # entity scope
publications:
  - filter: e.status == "active"             # entity scope
```

Using the wrong root is a compile error that says which one is available.

## The rule language

syncd implements a subset of standard CEL; the syntax and macros are CEL's, parsed by a
CEL parser.

### Values

| Type | Written as | Notes |
|---|---|---|
| string | `"text"`, `'text'`, `r"raw"` | |
| int | `42`, `-1` | 64-bit, overflow is an error. Unsigned literals are read as ints. |
| bool | `true`, `false` | |
| null | `null` | An absent value. |
| bytes | `b"\xff\xd8"` | Binary data: an `objectGUID`, a photo, a certificate. |
| list | `["a", "b"]` | A multi-valued attribute reads as a list. |
| timestamp | `now()`, `timestamp("2024-01-02")` | An instant, in UTC microseconds. |
| duration | `duration("90d")` | A signed span of time. |
| entity | `ref(e.manager)` | Another entity, in computed attributes only. Read its attributes; it has no value of its own. |

Not supported: floating-point numbers (`1.5` is a compile error), map and message
literals, two-variable comprehensions, optional elements, and nested field access other
than on an entity (`e.a.b`).

### Attributes

- `e.mail` or `source.mail` reads an attribute. A name with characters CEL does not allow
  in an identifier, such as an LDAP attribute with a dash, is read **quoted** (new in
  0.5.0): `e["mail-alias"]`, `source["employee-id"]`, `ref(e.manager)["mail-alias"]`. The
  name must be a literal string: `e[...]` or `source[...]` with anything else is an error. MongoDB
  embedded fields still arrive as `outer_inner` (`source.name_given`).
- **An absent attribute.** Reading one is an "attribute not set" error, not an empty
  string. `has(e.nickname)` tests for it without failing, and
  `coalesce(e.preferredName, e.givenName)` takes the first that has a value. For a quoted
  name, `"mail-alias" in e` is the test (`has()` takes `x.name` only, and its syntax error
  says so).
- **Missing input is not a failure.** When a flow or a computed attribute fails only
  because an attribute it reads is absent (or a referenced entity does not exist), the
  attribute gets no value from it. The error policy is for real errors.
- **One or many values.** An attribute holding one value reads as that value; one holding
  several reads as a list. Attributes listed in `attributes.multi_valued` always read as a
  list: a single value is a one-element list and an absent one an empty list. So list in
  `multi_valued` any attribute a rule iterates over.

### Operators

| Operator | Works on |
|---|---|
| `==`, `!=` | Any values. Values of different types are simply unequal (`4 == "4"` is `false`). |
| `<`, `<=`, `>`, `>=` | Ints, strings, bytes, timestamps, durations. Text compared with a timestamp is read as one (`e.contractEnd > now()`). |
| `+` | Ints; strings (concatenation); bytes; lists; timestamp + duration; duration + duration. Adding a string and an int is an error: use `string()`. |
| `-` | Ints; timestamp − duration; timestamp − timestamp (a duration); duration − duration. |
| `*`, `/`, `%` | Ints; duration × int. Division by zero is an error. |
| `&&`, `||`, `!` | Bools. As in CEL, the deciding side wins even if the other errors: `false && error` is `false`. |
| `c ? a : b` | A bool condition. |
| `x in list` | Membership. A one-valued attribute counts as a one-element list. |
| `"name" in e` | Whether the record or entity (`e`, `source`, `ref(...)`) has the attribute, as `has()` does. |
| `list[i]` | Indexing from 0. Index `0` of a single value is that value. |
| `e["name"]` | The attribute `name`, as `e.name` reads it: for names that are not identifiers. |
| `has(e.x)` | Whether the attribute has a value. |

### Macros

The CEL macros iterate over lists, and over multi-valued attributes:

| Macro | Gives |
|---|---|
| `list.all(x, p)` | Whether `p` holds for every element. |
| `list.exists(x, p)` | Whether `p` holds for at least one. |
| `list.exists_one(x, p)` | Whether `p` holds for exactly one. |
| `list.map(x, f)` | Each element transformed. |
| `list.filter(x, p)` | The elements for which `p` holds. |

```text
e.memberOf.filter(g, g.contains("vpn")).map(g, upper(g))
e.memberOf.exists(g, g.startsWith("cn=admins,"))
```

As in CEL, `exists` is true if any element is true even when others error, and `all` is
false if any is false; an error nothing decides, or one inside `map()`, fails the
expression.

### Methods and calls

Every function can be called either way: `e.sn.lower()` is `lower(e.sn)`, and
`list.join(",")` is `join(list, ",")`. Text functions given a list apply to every value,
so `lower(e.mail)` works whether `mail` holds one address or several.

### Results

- `null` means no value: the attribute is left without one.
- A list becomes several values; nested lists are flattened.
- A timestamp is stored typed and exported to LDAP as GeneralizedTime
  (`20240102030405Z`); a duration is exported as whole seconds (for `pwdMaxAge`, say).
- Bytes are exported as they are.

## Function library

The console's expression editor offers these as completions with the same descriptions.
Arguments ending in `?` are optional. Time zone arguments are IANA names
(`Europe/Paris`), `UTC`, or offsets (`+02:00`), UTC when left out.

### Text

| Function | What it does | Example |
|---|---|---|
| `lower(s)` | Lowercase. On a list, every value. | `lower(e.mail)` |
| `upper(s)` | Uppercase. On a list, every value. | `upper(e.sn)` |
| `lowerAscii(s)` | Lowercases ASCII letters only; other characters stay. | `lowerAscii(e.uid)` |
| `upperAscii(s)` | Uppercases ASCII letters only. | `upperAscii(e.uid)` |
| `trim(s)` | Removes leading and trailing whitespace. | `trim(e.title)` |
| `ascii(s)` | Transliterates to ASCII: `Zoë Ærø` becomes `Zoe AEro`. For logins and mail addresses. | `lower(ascii(e.givenName))` |
| `escapeRdn(s)` | Escapes a value for a DN (RFC 4514): commas, plus signs, quotes and the like get a backslash. For DNs built in an expression, such as `dn_attributes`. | `"ou=" + escapeRdn(e.department) + ",dc=example,dc=com"` |
| `substring(s, start, end?)` | The characters from `start` (from 0) up to `end`, or to the end. | `e.givenName.substring(0, 1)` |
| `replace(s, from, to)` | Replaces every occurrence. | `replace(e.sn, " ", "")` |
| `split(s, separator)` | Splits into a list; an empty separator splits into characters. | `split(e.mail, "@")` |
| `contains(s, part)` | Whether `s` contains `part`. | `e.title.contains("Manager")` |
| `startsWith(s, prefix)` | Whether `s` starts with `prefix`. | `e.uid.startsWith("adm-")` |
| `endsWith(s, suffix)` | Whether `s` ends with `suffix`. | `e.mail.endsWith("@example.com")` |
| `matches(s, regex)` | Whether a regular expression matches somewhere in `s`. | `matches(e.employeeId, "^E[0-9]+$")` |
| `regexReplace(s, regex, replacement)` | Replaces every match. `$1` in a literal replacement is the first group; a replacement read from data is used as it is. | `regexReplace(e.phone, "[^0-9+]", "")` |
| `regexExtract(s, regex)` | The first group of the first match (or the whole match); empty when nothing matches. | `regexExtract(e.mail, "@(.+)$")` |
| `truncate(s, n)` | The first `n` characters of `s`, or all of it when shorter. On a list, each value. A negative `n` is an error. | `truncate(lower(e.sn), 20)` |

A regular expression written as a literal is checked when the rule compiles. One read from
data is compiled at run time, limited to 1 000 characters.

### Lists

| Function | What it does | Example |
|---|---|---|
| `join(list, separator)` | Joins values into one text; absent (null) values are left out. | `join([e.givenName, e.sn], " ")` |
| `size(x)` | Values in a list, characters in a text, or bytes in binary data. | `size(e.mail)` |
| `first(x)` | The first value of a list; a single value as it is. | `first(e.mail)` |

### Conversion and choice

| Function | What it does | Example |
|---|---|---|
| `string(x)` | A number, boolean, timestamp (RFC 3339) or duration (`3600s`) as text, or bytes holding UTF-8 text as that text. `string(null)` is an error: use `coalesce()`. | `string(e.level)` |
| `int(x)` | Text as a whole number; a timestamp as seconds since 1970; a duration as seconds. | `int(e.employeeId.substring(1))` |
| `coalesce(a, b, ...)` | The first argument that has a value; missing attributes and empty lists are skipped. | `coalesce(e.preferredName, e.givenName)` |

### Lookup and generation

| Function | What it does | Example |
|---|---|---|
| `lookup(table, key, default)` | The value for `key` in one of the view's lookup tables, or `default`. | `lookup("dept_codes", e.department, "GEN")` |
| `unique(scope, candidates)` | The first candidate not yet taken in the scope; `{n}` in a candidate counts up. Use with `mode: assign_once`. | `unique("uid", [lower(e.sn), lower(e.sn) + "{n}"])` |
| `unique_within(scope, value, max_length)` | The value cut to `max_length` if nobody holds it in the scope, else with a counter from 2 in place of its last characters. New in 0.5.0. | `unique_within("sAMAccountName", e.uid, 20)` |
| `uuid()` | A new random UUID (version 4), in lower case with hyphens: `0f8fad5b-d9cb-469f-a165-70867728950e`. Different on every call: use with `mode: assign_once`, and `permanent` to keep it for good. | `uuid()` |
| `ulid()` | A new ULID, 26 characters that sort by when they were made: `01ARZ3NDEKTSV4RRFFQ69G5FAV`. Different on every call, like `uuid()`. | `ulid()` |

See [Lookup tables](#lookup-tables), [Unique values](#unique-values) and
[Permanent values](#permanent-values).

### Relations

Computed attributes only. See [Reading other entities](#reading-other-entities).

| Function | What it does | Example |
|---|---|---|
| `ref(e.reference)` | The entity a reference attribute points at, such as the manager. | `ref(e.manager).displayName` |
| `refs(e.reference)` | Every entity a multi-valued reference points at. | `refs(e.manager).map(m, m.mail)` |
| `reports(e)` | The entities whose `manager` reference points at this one. | `size(reports(e))` |
| `referrers(e, "reference")` | The entities whose given reference points at this one. | `size(referrers(e, "manager"))` |

### Binary

| Function | What it does | Example |
|---|---|---|
| `bytes(s)` | Text as its UTF-8 bytes. | `bytes(e.uid)` |
| `base64(b)` | Bytes as base64 text (text is encoded as its UTF-8 bytes). On a list, each value. | `base64(e.jpegPhoto)` |
| `fromBase64(s)` | The bytes base64 text stands for (padding optional). | `fromBase64(e.photoBase64)` |
| `hex(b)` | Bytes as lowercase hex, two digits per byte. | `hex(e.objectGUID)` |
| `fromHex(s)` | The bytes hex text stands for; a `0x` or `\x` prefix is allowed. | `fromHex(e.badgeId)` |
| `guid(b)` | A 16-byte GUID (`objectGUID`, `mS-DS-ConsistencyGuid`) as text, in Microsoft's byte order: what Active Directory tools show. | `guid(e.objectGUID)` |
| `fromGuid(s)` | The 16 bytes of a GUID written as text: the inverse of `guid()`. | `fromGuid(e.immutableId)` |
| `sid(b)` | A security identifier (`objectSid`) as text, `S-1-5-21-...`. | `sid(e.objectSid)` |
| `fromSid(s)` | The bytes of a security identifier written as text: the inverse of `sid()`. | `fromSid(e.sidText)` |

### Active Directory values

| Function | What it does | Example |
|---|---|---|
| `uac(flag, ...)` | A `userAccountControl` value from flag names, ignoring case. A list of names, or an int, is taken too. | `uac("NORMAL_ACCOUNT", "DONT_EXPIRE_PASSWORD")` (66048) |
| `uac_has(value, flag)` | Whether a `userAccountControl` value has the flag. The value may be an int or the text Active Directory sends. | `uac_has(e.userAccountControl, "ACCOUNTDISABLE")` |
| `uac_set(value, flag, on)` | The value with the flag set (`true`) or cleared (`false`); the other flags stay. | `uac_set(e.userAccountControl, "SMARTCARD_REQUIRED", e.smartcard)` |
| `uac_flags(value)` | The names of the flags a value has, as a list. | `uac_flags(e.userAccountControl)` |
| `proxyAddresses(primary, aliases)` | Exchange's `proxyAddresses`: `SMTP:<primary>` first, then `smtp:<alias>` for each alias, sorted ignoring case, with duplicates and the primary itself left out. A list as the primary gives its first value; an empty primary is an error. | `proxyAddresses(e.mail, [e.login + "@example.com"])` |
| `licenses(skus, disabled_plans)` | Entra ID licences for `assignedLicenses`: each SKU part number with the service plans to turn off, as `ENTERPRISEPACK[-YAMMER_ENTERPRISE]`. A publication's `licenses` block writes this for you. | `licenses(["ENTERPRISEPACK"], ["YAMMER_ENTERPRISE"])` |

The flag names are `SCRIPT`, `ACCOUNTDISABLE`, `HOMEDIR_REQUIRED`, `LOCKOUT`,
`PASSWD_NOTREQD`, `PASSWD_CANT_CHANGE`, `ENCRYPTED_TEXT_PWD_ALLOWED`,
`TEMP_DUPLICATE_ACCOUNT`, `NORMAL_ACCOUNT`, `INTERDOMAIN_TRUST_ACCOUNT`,
`WORKSTATION_TRUST_ACCOUNT`, `SERVER_TRUST_ACCOUNT`, `DONT_EXPIRE_PASSWORD`,
`MNS_LOGON_ACCOUNT`, `SMARTCARD_REQUIRED`, `TRUSTED_FOR_DELEGATION`, `NOT_DELEGATED`,
`USE_DES_KEY_ONLY`, `DONT_REQ_PREAUTH`, `PASSWORD_EXPIRED`,
`TRUSTED_TO_AUTH_FOR_DELEGATION` and `PARTIAL_SECRETS_ACCOUNT`. An unknown name is an
error that lists them. A publication's Active Directory conveniences
(`account_control`, `exchange.proxy_addresses`, `sam_account_name`) are written with
these functions; see [Connectors](connectors.md#active-directory-conveniences).

### Time

| Function | What it does | Example |
|---|---|---|
| `now()` | The time of the pass, as a timestamp. Every rule of one pass sees the same `now()`. | `e.contractEnd > now()` |
| `effective(from, value, before)` | `value` once `now()` has reached the timestamp `from`, `before` until then. The engine evaluates the rule again at `from`. | `effective(timestamp(e.startDate) - duration("3d"), "active", "pending")` |
| `effective_until(until, value, after)` | `value` while `now()` is before the timestamp `until`, `after` from then on. The engine evaluates the rule again at `until`. | `effective_until(e.contractEnd, e.mail, null)` |
| `timestamp(x)` | A timestamp from text in any form sources send, or from seconds since 1970. | `timestamp(e.hireDate)` |
| `duration(text)` | A span of time: `"90d"`, `"1h30m"`, `"3600s"`; an int is seconds. | `now() - duration("90d")` |
| `parseTime(s, format, zone?)` | A timestamp from text in a strftime format (`%d/%m/%Y`). Without an offset in the text, a local time in the zone. | `parseTime(e.startDate, "%d/%m/%Y", "Europe/Paris")` |
| `formatTime(t, format, zone?)` | A timestamp as text in a strftime format, in the zone. `%Y%m%d%H%M%SZ` is LDAP GeneralizedTime. | `formatTime(now(), "%Y-%m-%d", "America/New_York")` |
| `getFullYear(t, zone?)` | The year. | `getFullYear(now())` |
| `getMonth(t, zone?)` | The month, from 0 (January) to 11, as in CEL. | `getMonth(now()) + 1` |
| `getDate(t, zone?)` | The day of the month, from 1. | `getDate(now())` |
| `getDayOfMonth(t, zone?)` | The day of the month, from 0, as in CEL. | `getDayOfMonth(now())` |
| `getDayOfWeek(t, zone?)` | The day of the week, from 0 (Sunday) to 6. | `getDayOfWeek(now()) == 1` |
| `getDayOfYear(t, zone?)` | The day of the year, from 0. | `getDayOfYear(now())` |
| `getHours(t, zone?)` | The hour. On a duration, its total in hours. | `getHours(now(), "Europe/Paris")` |
| `getMinutes(t, zone?)` | The minutes. On a duration, its total in minutes. | `getMinutes(now())` |
| `getSeconds(t, zone?)` | The seconds. On a duration, its total in seconds. | `getSeconds(now() - timestamp(e.pwdChangedTime))` |
| `getMilliseconds(t, zone?)` | The milliseconds. On a duration, its total in milliseconds. | `getMilliseconds(now())` |
| `addMonths(t, n, zone?)` | `n` calendar months later (earlier when negative); a day past the end of the month becomes its last day. | `addMonths(timestamp(e.hireDate), 6)` |
| `startOfDay(t, zone?)` | Midnight at the start of the day, in the zone. | `startOfDay(now(), "Europe/Paris")` |
| `daysBetween(a, b, zone?)` | Calendar days from the day of `a` to the day of `b` (negative when `b` is earlier). | `daysBetween(now(), e.contractEnd)` |
| `fromFiletime(x)` | A timestamp from an AD FILETIME (`accountExpires`, `pwdLastSet`, `lastLogonTimestamp`), as an int or text; null for `0` and "never". | `fromFiletime(e.accountExpires)` |
| `filetime(t)` | A timestamp as an AD FILETIME. | `filetime(addMonths(now(), 12))` |

Functions that take a timestamp also take text, read as `timestamp()` reads it. A literal
zone, format or duration is checked when the rule compiles.

!!! note "Not in the library"
    The product plan also lists padding, hashing, DN parsing and reading a named source's
    value (`src.hr_db.title`). None of these is built: the tables above are the whole
    built-in library. Functions of your own go in [rule packages](#rule-packages), written
    in CEL or, since 0.5.0, compiled to WebAssembly
    ([custom functions](#custom-functions-in-webassembly)).

## Computed attributes

A computed attribute is a value the view derives from the merged entity:

```yaml
computed:
  - name: displayName
    output: coalesce(e.preferredName, e.givenName) + " " + e.sn
    tests:
      - input: { givenName: Jane, sn: Doe }
        expect: Jane Doe
```

| Key | Default | What |
|---|---|---|
| `name` | required | The attribute it sets. |
| `mode` | `recompute` | `recompute`, `assign_once` or `only_if_empty`. |
| `steps` | none | `let` and `when` steps. |
| `output` | required | The final value. |
| `on_error` | `skip` | `skip`, `default` or `quarantine`. |
| `default` | | The fallback text for `on_error: default`. |
| `permanent` | | `{ key: <attribute> }`: keep the values for good, by that key; see [Permanent values](#permanent-values). Needs `mode: assign_once`. |
| `tests` | none | Test cases. |

### Evaluation order

- Computed attributes may read each other. They are evaluated in dependency order, and a
  cycle is refused with the path it takes.
- On a change, the engine evaluates again only the attributes whose inputs changed.
  Attributes that call `now()`, `effective()`, `effective_until()`, `unique()`,
  `unique_within()`, `uuid()` or `ulid()`, read other entities, or quarantine on error are always evaluated when their
  entity is recomputed.
- A computed attribute with the same name as a flowed attribute replaces the flowed value,
  unless its mode is `only_if_empty`.
- `now()` is the pass's logical time, not the wall clock, so previews and replays are
  deterministic. All rules of one pass share it: a batch of changes, a reconciliation
  page, a command or a timer firing each take one time for everything they evaluate, so
  two rules of one pass never disagree on whether a date has been reached.
- A rule that uses `now()` alone changes value only when its entity is recomputed (a
  change to the entity, or a full sync). One that uses `effective()` or
  `effective_until()` is also evaluated again at the instant its result changes; see
  [Effective dates](#effective-dates).

### Multi-step transforms

`steps` run before `output`, in order:

- `let: <name>` with `expr: <CEL>` sets a variable that later steps and the output read by
  its bare name. A later `let` of the same name replaces the value, and its expression can
  read the previous one (`base + ".ext"`).
- `when: <CEL>` with `then: [steps]` and optional `else: [steps]` runs one branch.

```yaml
computed:
  - name: mail
    mode: assign_once
    steps:
      - let: base
        expr: regexReplace(lower(ascii(e.givenName + "." + e.sn)), "[^a-z0-9.]", "")
      - let: domain
        expr: lookup("mail_domains", e.company, "example.com")
      - when: e.employeeType == "contractor"
        then:
          - let: base
            expr: base + ".ext"
    output: unique("people.mail", [base + "@" + domain, base + "{n}@" + domain])
    on_error: quarantine
    tests:
      - input: { givenName: "Ann-Marie", sn: "O'Neil", company: ACME, employeeType: staff }
        lookups: { mail_domains: { ACME: acme.com } }
        expect: annmarie.oneil@acme.com
      - input: { givenName: Bo, sn: Ek, company: Other, employeeType: contractor }
        expect: bo.ek.ext@example.com
```

`then` and `else` are always lists. Reading a variable that no step set on the path taken
is an error. The console's preview shows each step's value, numbered as `2` or `3.then.1`.

### Value modes

| `mode` | Behaviour |
|---|---|
| `recompute` | Evaluated again whenever its inputs change. |
| `assign_once` | Keeps the first value ever assigned; never evaluated again once set. For generated logins, mail addresses and ids, so they and their `unique()` claim stay stable when names change. With `permanent`, the values also outlive the entity; see [Permanent values](#permanent-values). |
| `only_if_empty` | Only set when no source gives the attribute a value; a source value wins. |

### Error policies

An error is a real failure: a type error, an unparsable date, `int("abc")`, no `unique()`
value left. An absent input is not an error (the attribute simply gets no value).

| `on_error` | On an error |
|---|---|
| `skip` | The attribute keeps the value it had. |
| `default` | The attribute gets `default`, a literal text. |
| `quarantine` | The attribute keeps the value it had, and the entity's exports are held until the rule succeeds again. |

A quarantine is kept in the store, with the attribute that failed, its error and since
when, so a restart keeps it. It ends by itself once the rule evaluates again for the
entity: fix the source data and the next change releases it, or fix the rule and publish
it, and the full sync that follows releases the entities. Quarantined entities show in:

- `syncd quarantine`, which lists them with the view, the attribute and the error, and
  `syncd status` (`view people: 3 quarantined`);
- the console's engine page, under *Held back by a failing rule*;
- the metric `syncd_view_quarantined` (per view) and the alert `SyncdQuarantined`, which
  fires after 30 minutes with any.

Every rule error counts in the `syncd_rule_errors_total` metric (alert `SyncdRuleErrors`)
and is logged at debug level with the view, attribute and error. A failing flow is
logged as a warning; flows have no error policy.

```yaml
- name: hireYear
  output: string(getFullYear(timestamp(e.hireDate)))
  on_error: default
  default: "unknown"
```

## Lookup tables

A view's `lookups` are inline tables of text keys to text values:

```yaml
views:
  - name: people
    lookups:
      dept_codes:
        Engineering: ENG
        Sales: SLS
      mail_domains:
        ACME: acme.com
    computed:
      - name: departmentNumber
        output: lookup("dept_codes", e.department, "GEN")
```

- `lookup(table, key, default)` takes exactly three arguments. The table name must be a
  literal string. An int key is looked up as its text.
- Keys match exactly, including case: normalise in the key expression
  (`lookup("dept_codes", upper(trim(e.department)), "GEN")`) if the data varies.
- A table the view does not have is a validation warning, and `lookup()` returns its
  default.
- Tables are versioned with the configuration. The console's lookup table manager edits
  them, with search, CSV import (replacing or merging rows) and CSV export. Binding a table
  to a database table is not built.
- A rule package's tables are read as `<namespace>.<table>`
  (`lookup("gov_naming.titles", e.title, "")`); inside the package, by their own names.

## Unique values

`unique(scope, candidates)` returns the first candidate no other entity holds in the
scope, and claims it for this entity.

```yaml
- name: uid
  mode: assign_once
  output: >-
    unique("uid", [
      lower(e.givenName.substring(0, 1) + e.sn),
      lower(e.givenName.substring(0, 2) + e.sn),
      lower(e.givenName.substring(0, 1) + e.sn) + "{n}"
    ])
```

- The scope is a literal name; computed attributes of any view that use the same scope
  share it. One computed attribute may use one scope.
- `{n}` in a candidate is replaced by a counter from 2 up to 9 999: `jdoe2`, `jdoe3`, ...
  A value the entity already holds is kept while it is still a candidate.
- Claims are case-insensitive, as LDAP naming attributes are, and stored by the engine.
  They are released when the entity is deleted, except those of a
  [permanent](#permanent-values) attribute, which are never released. Claims are checked against the engine's
  own index, not against the live target.
- When no candidate is free, the rule fails with "no unique value left", and the error
  policy applies.
- Use `mode: assign_once`. In `recompute` mode a value moves back to an earlier candidate
  once that one is released.
- Claims are recorded only for computed attributes, so `unique()` is refused in flows and
  join rules. Generate values in a computed attribute and map that attribute in
  publications.
- Previews and test cases treat every candidate as free, so the first candidate wins.

### `unique_within()`: a value cut to a length

`unique_within(scope, value, max_length)` (new in 0.5.0) makes one value unique within a
length limit, as an Active Directory `sAMAccountName` (20 characters) needs:

```yaml
- name: samAccountName
  mode: assign_once
  output: unique_within("sAMAccountName", lower(e.givenName.substring(0, 1) + e.sn), 20)
```

- When nobody holds the value cut to `max_length`, that is the result: `jsmith`, or
  `christopher.columb` for `christopher.columbus` at 18.
- When it is taken, a counter from 2 is appended while it fits, else it takes the place of
  the last characters: `jsmith2`, `christopher.colum2`, and `abcdef10` once `abcdefg2` to
  `abcdefg9` are taken at 8. Claims are compared without case, as for `unique()`.
- A value the entity holds is kept while it is the value or the value with a counter, so
  the plain value coming free (its holder left) renames nobody. When the value itself
  changes, the result follows it.
- No value gives no result; a `max_length` under 1 is an error, and so is a scope with no
  counter left ("no unique value left in scope ..."). The scope is a literal, and the
  rules for `unique()` above apply: computed attributes only, one scope per attribute.
- It is what a publication's `sam_account_name: { on_conflict: unique }` uses, in place of
  a dead letter on a duplicate; see [Connectors](connectors.md#active-directory-conveniences).

## Permanent values

A computed attribute can belong to a key, such as an employee number, for good. Its
values are never given to another entity, even once their entity is deleted, and an entity
that comes back with the same key gets them again. That makes a stable person id, or a
login, that survives a leaver being rehired under a new HR record.

```yaml
computed:
  - name: personId
    mode: assign_once
    output: uuid()                  # or ulid(), or unique("people.id", ["P{n}"])
    permanent: { key: employeeNumber }
  - name: uid
    mode: assign_once
    output: unique("uid", [lower(e.givenName.substring(0, 1) + e.sn), lower(e.givenName.substring(0, 1) + e.sn) + "{n}"])
    permanent: { key: employeeNumber }
```

- `permanent` needs `mode: assign_once`. The key is another attribute of the view, from a
  flow or computed; it is read like an input, so a computed key is settled first.
- The engine keeps a record per view and attribute: the key, matched ignoring case, and
  which entity has it with which values. It is written when the entity gets its values,
  kept when the entity is deleted, and is part of the store, so backups and restores carry
  it.

An entity without values gets them by its key:

| The entity's key | What it gets |
|---|---|
| None | No values, since they could not be given back. They come once the key does. |
| Recorded for an entity since deleted | That entity's values: a rehire. |
| Held by another live entity | None, and a rule error under the attribute's `on_error` (`its permanent key "e100" belongs to another entity`). Once that entity is deleted, or its key changes, the waiting one gets the values at once. |
| Several values | None, and a rule error. |
| Not recorded | New values from the rule. |

- **Never reused.** `unique()` claims of a permanent attribute are not released when its
  entity is deleted, so no candidate list hands them out again. `uuid()` and `ulid()` are
  random.
- **A changed key** (a corrected employee number) keeps the entity's values, which move to
  the new key. The old key is forgotten, so someone else who has it gets values of their
  own.
- **Parallel computes** (full syncs, batches of updates) check that no other entity took
  the key meanwhile before a result is applied, as they do for `unique()` claims.
- Two live entities with the same key are reported as a rule error, not merged: joining
  them on the key is the view's join rules' job.

!!! note "Limits"
    Values given before `permanent` was set are recorded when their entity is next
    recomputed after a source change, or when it is deleted, so a leaver's values are still
    given back on rehire. A full sync that changes nothing does not record them, and until
    they are recorded another entity with the same key is not seen as sharing it. Records
    are never removed: turning `permanent` off, or removing the attribute, leaves them in
    the store, and its `unique()` claims stay taken. No console page lists the records or
    releases one by hand.

## Reading other entities

A view's `references` declare attributes whose values identify other entities. Computed
attributes can then follow them:

```yaml
views:
  - name: people
    references:
      manager: { match: employeeId }        # manager holds the manager's employeeId
    computed:
      - name: managerName
        output: ref(e.manager).displayName
      - name: managerMail
        output: ref(e.manager).mail
      - name: isManager
        output: size(reports(e)) > 0
      - name: skipLevelMail
        output: ref(ref(e.manager).manager).mail
  - name: teams
    references:
      member: { view: people, match: employeeId }
    computed:
      - name: activeMembers
        output: refs(e.member).filter(m, m.status == "active").map(m, m.mail)
```

- `ref(x.attr)` gives the one entity `attr` points at; `refs(x.attr)` every entity a
  multi-valued reference points at, as a list to iterate with `map`, `filter` or `exists`.
- `reports(x)` gives the entities whose `manager` reference points at `x`.
  `referrers(x, "attr")` does the same for any reference, named as a literal string.
- Read an entity's attributes with `.attr`. `has(ref(e.manager).mail)` is `false` when
  there is no manager either.
- Paths can go up to 4 references deep (`ref(ref(e.manager).manager)`).
- A reference whose entity does not exist gives no value, not an error.
- When a referenced entity changes an attribute a rule read, the entities whose rules read
  it are evaluated again. The engine tracks which attributes each rule reads at the end of
  each path.
- Cross-entity functions work in computed attributes only, not in flows or publication
  mappings, and cannot be previewed on their own or run in test cases: `syncd test`
  reports such tests as skipped, not failed.

Publications export a reference attribute as the DN its entity has in the same target,
through the publication's own `references` map; see
[Configuration](configuration.md#publications-publications).

## Dates and times

Sources send dates as text by default. Rules read that text as a timestamp in three ways:

- `timestamp(e.hireDate)`;
- implicitly, when text is compared with or subtracted from a timestamp
  (`e.contractEnd > now()`);
- as an argument of a time function (`formatTime(e.hired, "%Y")`).

Listing the attribute under the object's `timestamps` makes it arrive typed instead; see
[Connectors](connectors.md#binary-values-and-dates-from-sources).

`timestamp()` reads these forms; a form without an offset is UTC:

| Form | Example |
|---|---|
| RFC 3339, ISO 8601 | `2024-01-02T03:04:05Z`, `2024-01-02T03:04:05.123+01:00` |
| PostgreSQL | `2024-01-02 03:04:05.123456+00` |
| MySQL, SQL Server | `2024-01-02 03:04:05`, `2024-01-02 03:04:05.1234567 +01:00` |
| A date | `2024-01-02` (midnight) |
| LDAP GeneralizedTime | `20240102030405Z`, `20240102030405.5+0100` |

Any other form needs `parseTime(text, format, zone?)`. Durations take the units `d`,
`h`, `m`, `s`, `ms`, `us` and `ns`, combined (`1h30m`) and optionally negative.

### Effective dates

`effective()` and `effective_until()` make a value apply from, or until, an instant:

```yaml
computed:
  # Accounts appear three days before the start date.
  - name: provisioned
    output: effective(timestamp(e.startDate) - duration("3d"), true, false)
publications:
  - name: contractors
    filter: e.provisioned == true
    attributes:
      # The mail address is removed at the end of the contract.
      mail: effective_until(e.contractEnd, e.mail, null)
```

- `from` and `until` take a timestamp, or text that reads as one (`e.startDate`).
- Only the branch that applies is evaluated, so the other may read an absent attribute or
  be `null`.
- The engine notes the instant a rule's result changes next and keeps a timer for it in
  its store. At that instant the entity is computed again, and every publication of its
  view plans it again, so the value appears in the target on time, whether the engine
  ran all along or was restarted in between. A full recompute at that instant gives the
  same values: the result depends on the engine's time, not on when it ran.
- Pending timers show in `syncd status` (`timers: N pending, next due in ...`), on the
  console's engine page and in the metric `syncd_timers_pending`.
- They work in computed attributes and publication mappings. In a test case `now()` is
  0 (1970), so `before` or `value` is what a test sees.

See [Change safety and lifecycle](change-safety.md#effective-dates) for joiners and
leavers built on them.

```yaml
computed:
  # Contract ends within 30 days.
  - name: expiringSoon
    output: daysBetween(now(), e.contractEnd) <= 30
  # A local date in the HR system's format, exported as GeneralizedTime.
  - name: startDate
    output: parseTime(e.start, "%d/%m/%Y", "Europe/Paris")
  # Active Directory account expiry, a year after hire.
  - name: accountExpires
    output: string(filetime(addMonths(timestamp(e.hireDate), 12)))

publications:
  - name: active_contractors
    filter: e.contractEnd > now()
```

## Binary values

Binary data passes through rules unchanged, so `jpegPhoto: source.jpegPhoto` and
`jpegPhoto: e.jpegPhoto` export the same bytes. `has()`, `coalesce()`, `==`, ordering, `+`
and `size()` work on bytes. Using bytes where text is expected (`lower(e.jpegPhoto)`,
`e.jpegPhoto + "x"`) is an error that suggests `base64()` or `hex()`.

```yaml
flows:
  objectGuidText: guid(source.objectGUID)        # 12345678-1234-5678-9abc-def012345678
  sidText: sid(source.objectSid)                 # S-1-5-21-...
  photo: source.thumbnailPhoto                   # bytes, as they are
publications:
  - attributes:
      employeeNumber: e.objectGuidText
      jpegPhoto: e.photo
      carLicense: hex(e.photo)
```

A binary value cannot go into a DN: a DN is text, so use `guid()` or `hex()` in a
`dn_attributes` entry.

## Test cases

Test cases are kept with the computed attribute and run without any source:

```yaml
computed:
  - name: vpnGroups
    output: e.memberOf.filter(g, g.contains("vpn")).map(g, upper(g))
    tests:
      - input: { memberOf: [cn=staff, cn=vpn-users, cn=vpn-admins] }
        expect: [CN=VPN-ADMINS, CN=VPN-USERS]
  - name: guidText
    output: guid(e.objectGUID)
    tests:
      - input: { objectGUID: { base64: "eFY0EjQSeFaavN7wEjRWeA==" } }
        expect: "12345678-1234-5678-9abc-def012345678"
  - name: departmentNumber
    output: lookup("dept_codes", e.department, "GEN")
    tests:
      - input: { department: Legal }
        lookups: { dept_codes: { Legal: LEG } }
        expect: LEG
```

| Key | What |
|---|---|
| `input` | Entity attributes: name to a string, int, bool, list of strings, or `{ base64: "..." }` bytes. |
| `lookups` | Lookup tables for this test, merged over the view's own tables (the test's entries win). |
| `expect` | The expected value, in the same forms. |

- Values are compared as sorted text, so list order does not matter and `4` equals `"4"`
  (both export as the text `4`). A value repeated in the output counts twice, so it fails
  against a single expected value.
- Timestamps are expected as RFC 3339 text; bytes are shown as `base64:...`.
- Tests compile the rule as the engine does, so attributes in `attributes.multi_valued`
  read as lists.
- `now()` is the start of 1970 (0) in a test, and `unique()` treats every candidate as
  free.
- Rules that read other entities (`ref()`, `referrers()`) cannot be tested this way; their
  tests are reported as skipped.

`syncd test -c config.yaml` runs every test, the [rule packages'](#package-tests) tests
too, and reports failures as `view <v>: computed <c>: test <n>: expected ..., got ...`.
Publishing requires every test to pass: the console will save a version whose tests fail
but not publish it, and the engine refuses to apply it. `syncd test --golden DIR` also
runs golden-file tests of whole publications (the `*.yaml` files in `DIR` and one
directory level down). With Git sync the console runs the golden tests beside the
configuration for every publish, through the editor, the API, Git auto-publish or
promotion, and refuses one that fails; since 0.5.0 it reads them at the version's own Git
commit. See [Golden-file tests](config-as-code.md#golden-file-tests).

## Rule packages

A rule package holds functions and lookup tables written once, versioned and tested, and
shared between configurations: how a display name is formed, how a mail address or a
login is made. A configuration lists the packages it uses under `packages` (keys in
[Configuration](configuration.md#packages-packages)) and its rules call their functions.

### Listing a package

```yaml
packages:
  - name: gov-naming
    version: 1.2.0
    from: packages/gov-naming.yaml   # next to the configuration file
    pin: "sha256:3f2a..."            # optional: the file's SHA-256
views:
  - name: people
    computed:
      - name: displayName
        output: gov_naming.display_name(e)
      - name: mail
        mode: assign_once
        output: gov_naming.mail(e, "example.gov")
```

- `from` is a file relative to the configuration file's directory. Instead of `from`, a
  package can be written inline under the same entry, with `functions`, `lookups`,
  `tests` and `requires` as in a file.
- Reading the file checks the pin (`sha256:` and the file's SHA-256), that the file is
  the package named and that it has the version the configuration expects. Any
  difference is refused.
- A configuration holds **one version of each package**: the same package at two
  versions is refused, so there is nothing to resolve.
- A configuration read from its file carries the package's content and the file's
  `sha256` once loaded, so a loaded configuration travels without the file.

### The package file

```yaml
name: gov-naming
version: 1.2.0
description: Display names, mail addresses and sAMAccountNames by the naming standard.
lookups:
  titles: { dr: Dr., prof: Prof. }
functions:
  translit:
    params: [s: string]
    description: Plain ASCII for a name (Zoë -> Zoe, Ærø -> AEro).
    expr: s.ascii().trim()
  display_name:
    params: [p: entity]
    description: '"Surname, Given name" with an honorific, as the address book shows it.'
    expr: >-
      p.sn.trim() + ", " + (has(p.preferredName) ? p.preferredName : p.givenName).trim()
      + (has(p.honorific) ? " " + lookup("titles", p.honorific.lower(), p.honorific) : "")
  sam:
    params: [p: entity]
    description: An sAMAccountName, first initial and surname, at most 20 characters.
    expr: truncate((translit(p.givenName).substring(0, 1) + translit(p.sn)).lower(), 20)
tests:
  - expr: display_name(e)
    input: { givenName: Zoë, sn: Ærø }
    expect: Ærø, Zoë
  - expr: translit("Ærø Łódź")
    expect: AEro Lodz
```

| Key | What |
|---|---|
| `name` | Letters, digits and `-` (or `_`), starting with a letter. |
| `version` | Semantic versioning: `1.2.0`, `2.0.0-rc.1` (a pre-release sorts before its release). |
| `description` | Shown in the console. |
| `functions` | Name to `{ params, expr, description }`. `params` is a list of one-entry maps, `name: type`, in order. |
| `lookups` | Lookup tables, as a view's. |
| `tests` | Test cases; [below](#package-tests). |
| `requires` | Other packages this one calls, name to a version range: `requires: { gov-common: "^1.0" }`. |
| `wasm` | A module of [custom functions in WebAssembly](#custom-functions-in-webassembly): `{ from: <file>, sha256: <hex> }`. New in 0.5.0. |

Parameter types are `entity`, `string`, `int`, `bool`, `list`, `timestamp` and `any`.

A starter package is `examples/packages/gov-naming.yaml` in the source repository.

### Versions and requirements

A range in `requires` is one or more comparators joined by `,`, all of which must hold:

| Range | Allows |
|---|---|
| `^1.2` (also `1.2`, with no operator) | 1.2.0 up to, not including, 2.0.0 |
| `~1.2.3` | 1.2.3 up to, not including, 1.3.0 |
| `>=1.0, <2` | What the comparators say; `=`, `>`, `>=`, `<` and `<=` are taken |
| `*` | Any version |

Missing minor and patch numbers are zero. A required package must be listed in the
configuration too, at a version the range allows.

### Calling package functions

- A rule calls `<namespace>.<function>(...)`, the namespace being the package name with
  `-` as `_` (`gov-naming` is `gov_naming`).
- The compiler expands the call where it stands: the arguments are evaluated in the
  caller's scope, and the body runs with only its parameters visible. So a package
  function is as fast as the same expression written inline, and **the attributes its
  body reads count as the calling rule's own dependencies**: a computed attribute that
  calls `gov_naming.display_name(e)` is recomputed when `sn` or `preferredName` changes.
- An `entity` parameter takes the rule's root variable (`e`, or `source` in a flow), and
  the body reads attributes through it (`p.sn`); `has()`, `ref()` and the other entity
  functions work on it. Passing anything else is a compile error.
- Inside a package, its own functions are called without the namespace, and its own
  lookup tables by their own names. Everyone else reads them as `<namespace>.<table>`.
- A wrong number of arguments, a function calling itself (directly or through others),
  or an unknown function is a compile error. A value of the wrong type is an evaluation
  error. Absent values pass, a `list` parameter takes a single value as a one-element
  list, and a `timestamp` parameter reads text as `timestamp()` does.

### Package tests

A package's `tests` are expressions evaluated inside the package over `input` as `e`,
with every package's lookup tables and the test's own `lookups`. `now()` is 0 and
`unique()` treats every candidate as free, as in other test cases. They run with the
computed attributes' tests: in `syncd test`, the console's check and the publish gate,
and when the engine applies a configuration (`syncd validate` compiles but runs no
tests). A failure is reported as `package gov-naming 1.2.0: test 3: expected ...,
got ...`, and blocks publishing like any failing test.

### Where packages show

- `syncd status` lists the running configuration's packages:
  `package gov-naming 1.2.0 (packages/gov-naming.yaml sha256:3f2a...): 6 functions, 6 tests`,
  and names a package's module (`module sha256:66671f4139a1 with 3 functions`).
- `syncd config diff` has a `packages` section: a version bump reads
  `~ gov-naming 1.1.0 -> 1.2.0`, with the functions that changed, and for a module its new
  pin, size and every signature that changed (never its bytes). See
  [Configuration as code](config-as-code.md#rule-packages).
- The console's Studio lists package functions in its catalog and its previews call them.
  The configuration editor's **Packages** panel lists a version's packages with their
  version, pin, source and counts, and **Check for update** compares the file at the Git
  branch's head with the version's. Since 0.5.0 the console's **Compare** tab shows the
  functions that changed too, reading each side's packages at its own Git commit.

!!! note "Limits"
    The console stores no package files of its own. A configuration it publishes uses
    inline packages, packages from its Git repository, or files next to the engine's own
    configuration file; its module store holds only the WebAssembly modules of custom
    functions. A version saved before 0.5.0 has no Git commit: its file packages are read
    at the branch head, and the version compare falls back to their name, version, file
    and pin when the file has moved on, with a note saying so.

## Custom functions in WebAssembly

New in 0.5.0. When the function library lacks something (a proprietary identifier
checksum, a transliteration table for a script it does not cover, a legacy hashing
scheme), a rule package can carry a **custom function**: a pure function compiled to a
WebAssembly core module, called from any rule like the package's other functions. The
source repository's guide is `docs/CUSTOM-FUNCTIONS.md`, with a complete example in
`examples/wasm-functions` (a small Rust SDK and a module of check-digit functions).

```yaml
# packages/checksum.yaml
name: checksum
version: 1.0.0
wasm:
  from: ../wasm-functions/checksum.wasm      # relative to this package file
  sha256: 66671f4139a1260cefc07746952e2497b74233539c002b4972d49d257c1258a8
functions:                                    # CEL functions may call the module's
  employee_id: { params: [n: string], expr: n + luhn_digit(n) }
tests:
  - { expr: 'luhn_digit("7992739871")', expect: "3" }
```

```yaml
views:
  - name: people
    computed:
      - name: employeeId
        output: checksum.luhn_digit(e.employeeNumber)
        on_error: quarantine
```

- **What a function is.** A function of syncd's values (strings, integers, booleans,
  bytes, timestamps and durations, lists of those, and null) that answers with one such
  value or with a message saying why it failed. The module declares its functions, their
  parameter and result types and their documentation in a manifest (a `syncd.manifest`
  custom section), and every call is checked against it: the number of arguments and the
  types of literal ones when the rule compiles, the others as they are evaluated. A module
  function takes values, not the entity: pass its attributes (`e.employeeNumber`), not `e`.
- **What a module can never do.** It imports nothing: a module that imports anything is
  refused when it is loaded. So it has no I/O, clock, random numbers, network, files,
  environment or host functions (cryptographic ones included), and gives the same answer
  for the same arguments on every engine, in every preview, test and replay. Pass `now()`
  in from the rule if it needs the time. A start function, threads, SIMD, several memories
  and 64-bit memory are refused, and no state is kept between calls.
- **Pinned.** `wasm` needs `sha256`; a module whose bytes differ is refused when the
  configuration is read (`syncd validate`, `syncd run`, the console's check and publish).
  `from` is relative to the package file (to the configuration for an inline package).
  Without `from`, the console fills the module in from its module store by its hash (see
  below). A module is at most **1 MiB**: it travels inside the configuration, base64, so a
  loaded configuration carries it.
- **Calls.** Rules call `checksum.luhn_digit(...)`; the package's own CEL functions and
  tests call it without the namespace. A CEL function and a module function may not share
  a name. A changed module is a new package version.
- **Failures.** A function that returns an error, traps, runs out of fuel or memory,
  recurses past the call depth, or answers with a result that is too large or of another
  type than declared fails **that one call**, with an error naming the function, the
  module's hash and the cause. The rule's `on_error` decides; the engine carries on.

| Limit | Default | What it bounds |
|---|---|---|
| `engine.wasm_functions.fuel` | 10,000,000 (1,000 to 10^9) | Instructions per call: an endless loop stops there |
| `engine.wasm_functions.memory_mb` | 16 (1 to 1024) | The memory of one instance |
| Call depth | 1,024 | Recursion |
| Result size | 1 MiB | A larger answer is refused |
| Module size | 1 MiB | Larger modules are refused |

The runtime is **wasmi**, an interpreter (the product plan named Wasmtime, whose JIT cannot
run under the units' `MemoryDenyWriteExecute=yes`). A call costs about 5 µs for the
example's check digit on a release build, against about 0.1 µs for a built-in such as
`upper()`: keep the library's functions for what they do.

**FIPS mode.** Modules are refused by default in FIPS mode, since a module's own code runs
outside the validated cryptographic module. `SYNCD_FIPS_ALLOW_WASM_FUNCTIONS=true` (or
`--fips-allow-wasm-functions`), on the engine and on the console, allows them, for modules
that do no security-relevant cryptography. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

**Testing.** Package tests and rule tests call module functions like any other and run the
module as the engine does, under the same limits: `syncd -c syncd.yaml test`. The console's
Studio preview and test runner use the same code, so a test passes in the Studio exactly
when it passes in `syncd test`.

**Through the console.** A designer of the engine uploads the module on the engine's
**Custom functions** page; someone else who edits that engine's configuration reviews it
(size, hash, functions and signatures, the difference from the previous upload of the same
name, the module itself) and approves it, both recorded in the audit trail with the
module's hash. A package then names the module by its hash alone:

```yaml
packages:
  - name: checksum
    version: 1.0.0
    wasm: { sha256: "66671f4139a1260cefc07746952e2497b74233539c002b4972d49d257c1258a8" }
```

The console fills the approved module in for its check, the Studio and publishing, and
publishes each package whole, module bytes included, inside the configuration it signs. A
module not uploaded, or not approved yet, makes the configuration fail its check. With Git
sync, a module may instead sit in the repository beside its package file (`from`) and be
reviewed with the change that adds it. See the
[user guide](user-guide.md#custom-functions-enginesidfunctions).

**License.** Custom functions belong to the Extensibility pack: when the license does not
include it, the Studio marks them as locked, and a configuration that uses them cannot be
saved or published (see [Licensing](licensing.md#packs)).

!!! note "Limits"
    The release notes list these: an interpreter, not Wasmtime; no component model (the
    contract is a core ABI, described in WIT); FIPS mode was not run end to end; a module
    next to a hand-written configuration on the engine's host is not in HA snapshots or
    backups; the limits are the engine's, not per package; and the Studio's preview uses
    the default limits whatever the engine's `engine.wasm_functions` says.

## Authoring in the console

The console's Computed Attribute Studio edits a view's computed attributes in a
configuration version:

- the expression as CEL text, with completion and hover help from the function library,
  or as visual blocks (values, attributes, function calls, `+`, comparisons, conditions,
  lists) for those who would rather not write CEL;
- steps, with each step's value shown in a live preview on a real entity or on
  hand-written input, which can be kept as a test case;
- the test cases, run as you edit;
- what the attribute reads and what reads it;
- for `assign_once` attributes, the `permanent` setting ("Keep for good, by key"), also in
  the visual editor;
- the function catalog, which lists the built-in functions and the functions of the
  configuration's rule packages, grouped by package with their descriptions, and offers
  them as completions; custom functions in WebAssembly are listed with their signatures
  and marked as WebAssembly (or as locked, when the license leaves them out);
- completions of the view's attributes, a name that is not an identifier offered as
  `e["mail-alias"]`;
- a **Quality check** block, which tries a data-quality check on a sample of the view's
  entities from the engine: how many fail, and which.

Compiler errors and warnings (an attribute nothing sets, an unknown lookup table) appear as
you type. See the [user guide](user-guide.md) for the workflow, and simulate a version
before publishing to see how many entities change and what each target would receive.

## Worked examples

These come from the example configurations in the source repository (`examples/*.yaml`).

### Building names and codes

```yaml
views:
  - name: people
    lookups:
      dept_codes: { Engineering: ENG, Sales: SLS }
    computed:
      - name: displayName
        output: e.givenName + " " + e.sn
        tests:
          - input: { givenName: Jane, sn: Doe }
            expect: Jane Doe
      - name: departmentNumber
        output: lookup("dept_codes", e.department, "GEN")
        tests:
          - input: { department: Engineering }
            expect: ENG
          - input: { department: Legal }
            expect: GEN
```

### Cleaning database values in flows

Db2 pads fixed-width columns and reports names in upper case
(`examples/db2-via-jdbc.yaml`):

```yaml
flows:
  uid: lower(trim(source.USERID))
  givenName: trim(source.FIRSTNME)
  sn: trim(source.LASTNAME)
  title: trim(source.JOB)
  department: source.WORKDEPT
```

### A constant, and a filter on a source value

A literal string is quoted twice, once for YAML and once for CEL
(`examples/mysql-to-openldap.yaml`, `examples/oracle-via-debezium.yaml`):

```yaml
flows:
  title: '"Contractor"'
  uid: lower(source.LOGIN)
publications:
  - filter: e.status == "ACTIVE"
```

### Flags from a status

NORMAL_ACCOUNT (512) and SMARTCARD_REQUIRED when the person has a smart card, by name
with `uac()` and `uac_set()`:

```yaml
- name: accountControl
  output: 'uac_set(uac("NORMAL_ACCOUNT"), "SMARTCARD_REQUIRED", e.smartcard == "yes")'
  tests:
    - { input: { smartcard: "no" }, expect: "512" }
    - { input: { smartcard: "yes" }, expect: "262656" }
```

A publication's `account_control` block writes the same without a computed attribute
(`flags: [NORMAL_ACCOUNT]`); see
[Connectors](connectors.md#active-directory-conveniences).

### Multi-valued values from text

A team's members held as comma-separated employee ids, turned into a reference
(`member` must be in `attributes.multi_valued`):

```yaml
views:
  - name: teams
    sources:
      - connector: hr_db
        object: team
        project: true
        flows:
          name: source.name
          member: split(source.members, ",")
    references:
      member: { view: people, match: employeeId }
```

### A container built from data

```yaml
publications:
  - name: people_out
    dn: uid={uid},{dn:container}
    dn_attributes:
      container: >-
        coalesce(e.parentDn, "ou=" + escapeRdn(e.department) + ",l=" + escapeRdn(e.office)
        + ",o=Staff,dc=example,dc=com")
    create_containers: true
```
