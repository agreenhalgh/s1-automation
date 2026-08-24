# Authoring bot definitions

A bot is a JSON document that deserialises into
[`BotDefinition`](../src/main/java/com/newbrid/epr_automation/systmone/common/dto/BotDefinition.java)
(NFR-MNT-03): a list of steps, each applying one action to the first element that resolves
from an ordered locator chain. Worked examples live in [`bots/`](../bots):

| File | Shows |
|------|-------|
| [`login.json`](../bots/login.json) | The minimal shape: two steps, JAB with an image fallback |
| [`open-patient.json`](../bots/open-patient.json) | `WAIT` gate, optional step (`continueOnFailure`), per-step retry override, `KEYS`, `${var}` input, `GET_TEXT`/`READ_STATE` outputs |
| [`offset-clicks.json`](../bots/offset-clicks.json) | Every offset mechanism, one per step — the reference for clicking at a position relative to an image |
| [`appointment-book.json`](../bots/appointment-book.json) | Offsets used in anger: picking a grid row by pitch arithmetic, then a column by step offset |
| [`activate-window.json`](../bots/activate-window.json) | `ACTIVATE` with no locator at all, targeting a named child window, and gated by an optional chain |

All five deserialise cleanly against the current DTOs (verified with a plain Jackson
`ObjectMapper`, which is stricter about unknown fields than Spring's — so an unrecognised
key here is a hard error, not a silent no-op).

## Step fields

```jsonc
{
  "key": "open-record",        // optional name; only needed if something jumps to this step
  "name": "Click Search",      // label for logs, the run UI and step reports
  "action": "CLICK",           // see ActionType below
  "locatorChain": [ ... ],     // ordered; first to resolve wins (FR-ENG-02)
  "inputValue": "${nhsNumber}",// SET_TEXT / SELECT / KEYS; ${var} from the run input
  "outputKey": "patientName",  // GET_TEXT / READ_STATE store their value here
  "timeoutMs": 8000,           // budget for the whole chain (split across its locators)
  "retry": { "maxAttempts": 2, "initialBackoffMs": 400, "multiplier": 2.0, "maxBackoffMs": 3000 },
  "continueOnFailure": false,  // true → step records SKIPPED and the run continues
  "clickOffsetX": 40,          // click-point offset, see below
  "clickOffsetY": 0,
  "onFound": "handle-dialog",  // jump here when the locator resolves (see Conditional flow)
  "onMissing": "carry-on",     // jump here when nothing in the chain resolves
  "maxExecutions": 10,         // this step may run at most 10 times in one run
  "onLimit": "give-up"         // jump here once maxExecutions is used up
}
```

**Steps run in the order they appear in `steps`.** There is no positional field to keep in
step with that order: an earlier version of this schema had one, and a definition whose numbers
disagreed with its array order produced reports describing a run that never happened. Position
is the engine's business — it is what a step report's `index`, the progress callback and the
failure screenshot are filed under. `key` is yours, and only the steps something jumps to need
one.

`action` is one of `CLICK, DOUBLE_CLICK, RIGHT_CLICK, HOVER, FOCUS, SET_TEXT, GET_TEXT,
SELECT, EXPAND, COLLAPSE, READ_STATE, GET_COUNT, GET_TABLE_SIZE, KEYS, DRAG, ACTIVATE, WAIT`. `WAIT` asserts the
locator resolves and acts on nothing. `GET_TEXT`, `READ_STATE`, `EXPAND` and `COLLAPSE`
are JAB-only — the screen backend throws `UnsupportedOperationException` for them, which
the engine treats as a step failure. `ACTIVATE` is described below.

Omitted `retry` inherits the bot's `defaultRetry` (default 3 attempts, 250 ms → 500 ms →
1 s). `timeoutMs` of 0 or less becomes 10 000 ms; `globalTimeoutMs` of 0 means no ceiling.

### Sending keys

A `KEYS` step takes its keystrokes from `inputValue`. Join a chord with `+`, and separate
successive presses with `,`:

```jsonc
"inputValue": "CTRL+S"            // one chord: hold CTRL, press S, release both
"inputValue": "ALT"               // a modifier on its own is fine — focuses the menu bar
"inputValue": "DOWN,DOWN,ENTER"   // three presses, in order, from one step
```

Recognised names: `CTRL`/`CONTROL`, `ALT`, `SHIFT`, `ENTER`/`RETURN`, `TAB`,
`ESC`/`ESCAPE`, `SPACE`, `COMMA`, `DELETE`/`DEL`, `BACKSPACE`, `HOME`, `END`, `UP`, `DOWN`,
`F1`–`F4`. Any single character stands for itself, so `CTRL+S` and `ALT+F` work without
being listed. An unrecognised name fails the step rather than being skipped — a keystroke
that silently does not happen leaves the rest of the bot acting on a screen it never
reached. Because `,` separates presses, the comma key is named `COMMA`.

**A `KEYS` step needs no locator chain.** Keystrokes go to whichever window owns the keyboard
focus, never to the element a locator resolved — both backends discard it — so the chain was only
ever a gate. Omit it and the keystrokes are simply sent:

```jsonc
{ "name": "Dismiss the dialog", "action": "KEYS", "inputValue": "ESCAPE" }
```

Keep a chain when you want the gate: "only send this if the grid is on screen" is a real thing to
say, and a `WAIT`-like gate on a `KEYS` step says it. When you do, point it at something cheap such
as the SystmOne frame rather than an IMAGE locator that costs a full-screen match per step.

A `KEYS` step with no `inputValue` is rejected at registration: it would send nothing and report
success.

## Conditional flow

A step can send the run somewhere else instead of to the next step in the list. `onFound` is
taken when the step's locator chain resolves; `onMissing` when nothing in it does. Both name a
step's `key`.

```jsonc
{
  "name": "Did a duplicate-match dialog open?",
  "action": "WAIT",                   // resolves the chain and acts on nothing: a pure question
  "timeoutMs": 4000,
  "onFound": "pick-first-match",      // the dialog is up — deal with it
  "onMissing": "open-record",         // it is not — carry straight on
  "locatorChain": [
    { "type": "JAB", "role": "dialog", "name": "Multiple patients", "nameMatch": "CONTAINS" }
  ]
}
```

`WAIT` pairs naturally with a branch because it asserts and acts on nothing, but any action can
carry one: `onFound` on a `CLICK` fires when the click landed.

Four rules worth knowing:

- **Declaring `onMissing` makes absence an outcome, not a failure.** The step records `SKIPPED`,
  the run stays healthy, and it continues at the named step. Without `onMissing`, a chain that
  does not resolve fails the step exactly as it always did — so a probe needs the branch, and
  saying nothing still means "this must be here".
- **Only absence branches.** A locator that resolves and whose action then throws is a fault, and
  it fails the step rather than taking the `onMissing` path. Routing a broken `SET_TEXT` down the
  "not there" road would bury the bug.
- **A step with no branch for the outcome it got falls through** to the next step in the list. A
  step with `onMissing` only, that does find its element, simply carries on.
- **Jumps may go backwards**, which is how you loop — "page down, look again". Bound it with
  `maxExecutions`, below. A loop with no bound of its own stops after 1000 step executions with a
  `FlowLoop` error rather than running until the global timeout, which a bot is free not to
  declare.

### Bounding a loop

`maxExecutions` caps how many times one step may run in a single run, which is what turns a
backward jump into "try this a set number of times":

```jsonc
[
  {
    "key": "page-down",
    "name": "Page down the results grid",
    "action": "KEYS",
    "inputValue": "PAGE_DOWN",
    "maxExecutions": 10,        // ten pages is as far as this bot will look
    "onLimit": "give-up",       // ...and then it takes this path
    "locatorChain": [ { "type": "JAB", "role": "table", "name": "Results", "nameMatch": "CONTAINS" } ]
  },
  {
    "name": "Is the row there yet?",
    "action": "WAIT",
    "onMissing": "page-down",   // not yet — go round again
    "locatorChain": [ { "type": "JAB", "role": "table row", "name": "${patientName}", "nameMatch": "CONTAINS" } ]
  },
  { "name": "Select the row", "action": "CLICK", "locatorChain": [ ... ] },
  { "key": "give-up", "name": "Record that the patient was not in the list", "action": "GET_TEXT",
    "outputKey": "notFound", "locatorChain": [ ... ] }
]
```

- The count is **per step, per run**, and it is checked *before* the step runs — `maxExecutions: 3`
  means three executions, not four.
- **Without `onLimit`, running out fails the run** with a `StepLimit` error. That is the right
  default: a loop that was supposed to converge and did not is a failure, and silently carrying on
  would leave the rest of the bot acting on a screen that never arrived. Declare `onLimit` when the
  loop has a legitimate fallback, as above.
- Hitting the limit **records a step row** either way — status `SKIPPED`, error type `StepLimit` —
  so the run history shows where the loop gave up instead of simply ceasing to mention the step.
- Registration rejects `maxExecutions` below 1, and rejects `onLimit` on a step with no
  `maxExecutions`, since there would be no limit to reach.

`maxExecutions` is also useful without a loop at all: on a step that several branches converge on,
it caps how many times those paths may re-enter it.

Registration rejects a jump to a key no step declares, and rejects two steps sharing a key,
naming the step and the field. Both are the kind of typo that otherwise surfaces as a failed step
on a worker long after the job was dispatched.

Every execution is recorded, not every step. A step report carries a `sequence` — which
execution of the run it is, counting from 1 — and results are stored per `(runId, sequence)`, so a
step visited four times by a loop leaves four rows, each with its own status, duration and
screenshot. The run page lists them in execution order and labels the repeats "pass 2 of 4", which
is what makes a loop legible instead of looking like a step that ran once.

Redelivery is still idempotent: the same execution reported twice updates its own row, because the
sequence identifies the execution rather than the attempt.

[`conditional-flow.json`](../bots/conditional-flow.json) is a worked example: probe for a dialog,
handle it if present, and rejoin the main path either way.

## Activating the application

`KEYS` and `SET_TEXT` ultimately go to whichever window owns the keyboard focus, so a bot
that types before SystmOne is in front types into someone else's window. `ACTIVATE` brings
it to the foreground first — and it needs **no locator chain** (nor does `KEYS`, which acts on
the focused window rather than an element):

```jsonc
{
  "name": "Activate SystmOne",
  "action": "ACTIVATE",
  "outputKey": "activatedWindow"   // optional: records the full window title
}
```

`ACTIVATE` acts on a **window**, not on an element, so the engine performs it directly
through its `WindowControl` rather than through a backend. That is deliberate: a step
whose job is to repair focus must keep working on a host where the Access Bridge is
disabled or the JVM is headless — exactly the situations where the backends are
unavailable. No backend implements `ACTIVATE`; both throw if asked.

Which window it targets:

| `inputValue` | Target |
|---|---|
| omitted | the worker's `systmone.worker.window-title` (default `SystmOne`) |
| set | that title instead — e.g. `"Appointment Book"` for a child window. `${var}` works |

The match is a case-insensitive substring against visible top-level windows. If **more
than one** window matches, the step fails rather than guessing (NFR-REL-04) — activating
the wrong window would silently redirect every keystroke that follows. Narrow the title
until it is unique.

A minimised window is restored first; a maximised one is left maximised. Because Windows
refuses `SetForegroundWindow` from a background process, the worker attaches its input
queue to the current foreground thread to lift that restriction, then confirms the result
against `GetForegroundWindow` before reporting success — a modal dialog, a locked screen
or an elevated window can still block it, and the step then fails with the title of
whatever is actually in front. On a non-Windows host `ACTIVATE` always fails; it never
silently does nothing.

### Gating it with an optional chain

A `locatorChain` is still *allowed* on an `ACTIVATE` step, and then it runs first as a
gate — proving the application is really there before the window is raised. The match is
discarded either way; the window is always what gets activated.

```jsonc
{
  "name": "Activate only once the main window exists",
  "action": "ACTIVATE",
  "locatorChain": [
    { "type": "JAB", "role": "frame", "name": "SystmOne", "nameMatch": "CONTAINS" }
  ]
}
```

Use a gate when you want the step to fail fast if SystmOne is not running; leave it off
— the usual case — when you just want focus moved. Note the gate can only *add* failure
modes: a chain that cannot resolve, including one whose backend is unavailable, fails the
step before any activation is attempted.

Every step other than `ACTIVATE` still requires a non-empty `locatorChain`.

## Locators

**JAB** (preferred): `role`, `name` + `nameMatch` (`EXACT` | `CONTAINS` | `REGEX`),
`description`, `index`, `ancestorPath`, `parent`, `previousSibling`, `followingSibling`, `row`,
`column`. (`value` is carried on the record but not matched on.)

`index` is the element's position among **its own parent's children**, counting siblings of every
role — labels, buttons, panels and all. It is not "the second text field", and it shifts the day
the form gains a label, so prefer anything else that distinguishes the control.

A locator resolving to more than one element is **ambiguous**, and the engine refuses to act on
it rather than guess (NFR-REL-04). That is the usual reason a JAB step fails on a screen where
the control is plainly visible.

**Only what is on screen is searched.** A container whose accessible state says it is not showing
is skipped along with everything inside it, so a control on an unselected tab is neither matched
nor counted towards ambiguity — it cannot be clicked, and its coordinates belong to whatever is
drawn over it. This also means an unselected tab's fields no longer collide with the identical
fields on the tab that is showing, which is a common cause of an ambiguous JAB locator. Select the
tab first (a `CLICK` on the tab, then the field) rather than trying to reach through it.

An application that reports no accessible states at all is traversed in full, exactly as before:
silence is not evidence of being hidden.

### Addressing a table cell

`row` and `column` place an element within a grid:

```jsonc
{
  "type": "JAB",
  "role": "label",
  "ancestorPath": "table[Appointments]",
  "row": 3,
  "column": 1
}
```

Both are **zero-based**, and a header row normally occupies row 0 — so the first row of data is
usually row 1. `row` on its own addresses the whole row (`role: "table row"`, no `column`), which
is what you want for a click that selects it.

Read from the tree rather than from a table API: `row` is the position of the nearest enclosing
row among its siblings, and `column` is the position of whatever sits *directly* under that row —
not of the element itself, since a cell that wraps its contents puts the element a level deeper.
No extra calls into the application; the information is already in hand.

Where the grid keeps its cells out of the tree entirely — a Swing `JTable` exposes no row or cell
children at all, so the tree shows a table with nothing in it — the cell is fetched through the
Access Bridge **table API** instead, by row and column. That is a direct lookup rather than a
search, so addressing row 3,000 costs the same as row 0. Nothing changes in the definition: the
same `row`/`column` locator works whether the cells are walkable or not, and a grid that does
expose its rows is still walked.

One limit worth knowing before you rely on it:
- **A virtualised grid only exposes the rows it is showing.** Row numbers are positions among the
  rows currently in the tree, so scrolling changes them.

A cell that reports its bounds relative to its table — which Swing's synthetic cell objects commonly
do — is corrected before the mouse is driven to it, so a resolved cell is clicked where it actually
is rather than near the corner of the screen.

### Reading data out of a table

A worked grid, as SystmOne's appointment book might expose it — row 0 the header, three
appointments below it, three columns:

| row | column 0 | column 1 | column 2 |
|-----|----------|----------|----------|
| 0   | Time     | Patient  | Status   |
| 1   | 09:00    | Ada Lovelace | Booked |
| 2   | 09:20    | Alan Turing  | Arrived |
| 3   | 09:40    | Grace Hopper | Cancelled |

**One cell.** `GET_TEXT` on a cell locator puts the cell's text into an output:

```jsonc
{
  "name": "Read the patient in row 2",
  "action": "GET_TEXT",
  "outputKey": "patient",
  "locatorChain": [
    { "type": "JAB", "role": "label", "ancestorPath": "table[Appointments]", "row": 2, "column": 1 }
  ]
}
```

`${patient}` is then `Alan Turing`. This works whether the cells are in the tree or reachable only
through the table API — the definition is the same either way.

**A whole row** is one step per column, each with its own `outputKey`:

```jsonc
[
  { "name": "Time",    "action": "GET_TEXT", "outputKey": "time",
    "locatorChain": [ { "type": "JAB", "role": "label", "ancestorPath": "table[Appointments]", "row": 3, "column": 0 } ] },
  { "name": "Patient", "action": "GET_TEXT", "outputKey": "patient",
    "locatorChain": [ { "type": "JAB", "role": "label", "ancestorPath": "table[Appointments]", "row": 3, "column": 1 } ] },
  { "name": "Status",  "action": "GET_TEXT", "outputKey": "status",
    "locatorChain": [ { "type": "JAB", "role": "label", "ancestorPath": "table[Appointments]", "row": 3, "column": 2 } ] }
]
```

Those three outputs come back as `09:40`, `Grace Hopper` and `Cancelled`.

**The headings** are row 0, which is how a bot can check the grid is the one it expects before
trusting a column number:

```jsonc
{ "name": "Confirm column 1 is the patient column", "action": "GET_TEXT", "outputKey": "heading1",
  "locatorChain": [ { "type": "JAB", "role": "label", "ancestorPath": "table[Appointments]", "row": 0, "column": 1 } ] }
```

**How big the grid is**, from the application rather than from the tree:

```jsonc
{
  "name": "How big is the appointments grid?",
  "action": "GET_TABLE_SIZE",
  "outputKey": "grid",
  "locatorChain": [ { "type": "JAB", "role": "table", "name": "Appointments", "nameMatch": "CONTAINS" } ]
}
```

`${grid.rows}` is `4` and `${grid.columns}` is `3` — the header counts as a row, because the
application counts it as one. Both are ordinary outputs, so they work anywhere a placeholder does.

For the same grid, `GET_COUNT` on `role: "table row"` returns **0**: the cells are not children, so
a walk of the tree finds no rows at all. That difference is the whole reason `GET_TABLE_SIZE`
exists, and it is worth checking both when a count surprises you.

#### Reading every row

`row` and `column` accept a `${placeholder}` as well as a number, and the engine publishes a
counter for the step it is running — so a bounded loop can walk a grid:

```jsonc
[
  {
    "name": "How big is the appointments grid?",
    "action": "GET_TABLE_SIZE",
    "outputKey": "grid",
    "locatorChain": [ { "type": "JAB", "role": "table", "name": "Appointments", "nameMatch": "CONTAINS" } ]
  },
  {
    "key": "read-row",
    "name": "Read the patient in the next row",
    "action": "GET_TEXT",
    "outputKey": "patient",
    "maxExecutions": 50,
    "onLimit": "finished",
    "onFound": "read-row",
    "locatorChain": [
      { "type": "JAB", "role": "label", "ancestorPath": "table[Appointments]",
        "row": "${step.iteration}", "column": 1 }
    ]
  },
  { "key": "finished", "name": "Done", "action": "WAIT",
    "locatorChain": [ { "type": "JAB", "role": "table", "name": "Appointments", "nameMatch": "CONTAINS" } ] }
]
```

The step reads row 0 on its first pass, row 1 on its second, and so on. It stops on the pass where
the row does not exist — `onFound` is not taken when nothing resolves, so the flow falls through —
or at `maxExecutions`, whichever comes first. Each pass overwrites `${patient}`, so a bot that needs
every value must act on each one inside the loop rather than collecting them.

**The counters.** Set before each step runs, and describing *that step's* own executions:

| variable | meaning |
|----------|---------|
| `${step.iteration}`  | how many times this step has run before this one — 0 on the first pass |
| `${step.executions}` | how many times it has run including this one — 1 on the first pass |
| `${run.sequence}`    | which execution this is in the whole run, counting from 1 |

Both forms of the step counter exist because placeholders do no arithmetic: a zero-based row number
and a one-based "attempt 3 of 5" are both wanted, and neither can be derived from the other in a
definition.

The counter belongs to the step, not to a loop — there is no loop construct to belong to. In a loop
whose body is several steps each runs the same number of times, so they agree; a step reached by only
one branch has its own lower count, which is what it means.

**Placeholders now work throughout a locator**, not only in `row` and `column`: `name`,
`description`, `ancestorPath`, `parent`, the sibling selectors and `templateRef` all substitute.

```jsonc
{ "type": "JAB", "role": "table row", "name": "${patientName}", "nameMatch": "CONTAINS" }
```

Before this, only a step's `inputValue` was substituted, so a placeholder in a locator silently
looked for a control literally named `${patientName}`.

Values resolve from an earlier step's `outputKey` first, then the run input, then the engine's
counters — so a bot's own output wins over a counter of the same name rather than being quietly
replaced by it.

**A row that resolves to nothing fails the step**, naming the field and what was left unresolved:

```
Step 'Read the patient' asks for row '${missing}', which is not a number.
```

That is deliberate. A locator whose `row` could not be resolved would otherwise be a locator with no
row constraint at all, and would act on whichever cell happened to match first. Registration also
rejects a `row` or `column` that is neither a number nor a `${placeholder}`.

Still true: a bot that needs *all* the values out of a grid must do something with each one as it
goes. There is no list-valued output and no way to accumulate across passes.

[`read-appointments.json`](../bots/read-appointments.json) reads fixed rows;
[`walk-appointments.json`](../bots/walk-appointments.json) walks the grid with the counter.

### Counting what is on screen

`GET_COUNT` records how many elements a locator matches, into `outputKey`:

```jsonc
{
  "name": "How many appointments are listed?",
  "action": "GET_COUNT",
  "outputKey": "rowCount",
  "locatorChain": [
    { "type": "JAB", "role": "table row", "ancestorPath": "table[Appointments]" }
  ]
}
```

Columns are the cells of the header row — the same action with `"role": "label", "row": 0`.

It counts matches of a locator rather than rows of a table, so the same action counts search
results, list items or open tabs. `${rowCount}` is then available to later steps, including as the
bound on a loop.

- **It is the one action for which several matches are the answer**, so it never resolves an
  element and never meets the ambiguity fail-safe. A locator that `CLICK` would refuse to act on is
  exactly the one `GET_COUNT` is for.
- **Zero is an answer**: the step succeeds recording `0`. To branch on an empty grid, use a `WAIT`
  with `onMissing`.
- **Only JAB locators can be counted.** An image match reports a best score and a runner-up, not a
  set, so an image locator declines rather than inventing a number.
- The chain is "the first locator that can be counted", the counting analogue of "the first that
  resolves". A count with no `outputKey` is rejected at registration: it would read the screen and
  throw the answer away.

`GET_COUNT` describes **what the accessibility tree currently exposes**: for a virtualised grid
that is the rows on screen, and for a table that exposes no cell children it is zero however many
rows the grid has.

For the count in the model, ask the table itself:

```jsonc
{
  "name": "How big is the appointments grid?",
  "action": "GET_TABLE_SIZE",
  "outputKey": "grid",
  "locatorChain": [ { "type": "JAB", "role": "table", "name": "Appointments", "nameMatch": "CONTAINS" } ]
}
```

That writes two outputs — `${grid.rows}` and `${grid.columns}` — from one call, because a bot that
needs the row count usually needs the column count in the same breath. The locator must resolve to
a table, and only JAB can answer: an image match knows nothing about rows. A `GET_TABLE_SIZE` with
no `outputKey` is rejected at registration, since its two outputs are named after it.

### Identifying a control by what sits next to it

An unlabelled field has nothing to say about itself. `previousSibling` and `followingSibling`
identify it by the neighbour that does:

```jsonc
{
  "type": "JAB",
  "role": "text",
  "previousSibling": "label[Surname]"    // the field immediately after the Surname label
}
```

Both take a single `role[name]` segment — the same grammar `ancestorPath` uses, so `label`,
`[Surname]` and `label[Surname]` are all valid: **the bracketed name is optional**, a role on its
own is a selector, and the name on its own works too. The bracketed name is a case-insensitive
contains. Set both to pin a control between two known neighbours.

`parent` takes the same segment and matches the element's **immediate** parent:

```jsonc
{ "type": "JAB", "role": "text", "parent": "panel[Address]" }
```

That is the narrower claim than a one-segment `ancestorPath`, which any ancestor at any depth
satisfies. Use whichever is true of the screen: a path survives the layout gaining a wrapper, while
a parent pins the level and stops matching if the structure moves — which is the honest outcome
when it does. All four scoping fields combine, and every one set has to hold.

**Immediately** adjacent, and this is where they differ from `ancestorPath`. A path skips
intervening containers because panels and viewports are incidental; "the field after this label"
is a claim about adjacency and nothing else. Searching further for a match could quietly pin the
locator to a label two fields up and click the wrong control, which is worse than not resolving —
so a layout strut between label and field defeats the selector, visibly, rather than sliding onto
the wrong element. `index` or an `ancestorPath` is the fallback there.

An element whose siblings are unknown does not match a sibling-scoped locator, the same fail-safe
that applies to an unknown ancestor chain. Registration rejects a `>` in either field, since that
is the chain syntax and no single element has a role containing an angle bracket.

This is usually the better answer where an anchored image locator used to be needed: it survives a
theme change, a resolution change and a re-skinned icon, none of which a template does.

### Targeting a modal dialog

A Java modal is its own top-level window with its own title — "Confirm", "Warning", the name of
the thing it is asking about — and almost never the application's. Every window belonging to
SystmOne's JVM is searched, whatever it calls itself, so a dialog's contents are reachable
without configuring anything.

The catch is that the window *behind* the modal is still there: still in the tree, still
rendered, still matched. Its buttons are routinely named the same as the dialog's, so a bare
`{"role": "push button", "name": "Cancel"}` matches both and the step fails as ambiguous. Use
`ancestorPath` to say which window you mean:

```jsonc
{
  "type": "JAB",
  "role": "push button",
  "name": "Cancel",
  "ancestorPath": "dialog[Confirm deletion]"   // the Cancel in the dialog, not the one behind it
}
```

`ancestorPath` is a `>`-separated chain of `role`, `role[name]` or `[name]` segments, outermost
first, matched against the element's containers as a **subsequence** — `"dialog > table[Slots]"`
means "inside a dialog, inside a table named Slots", not "a table whose parent is a dialog".
Swing inserts panels, scroll panes and viewports that nobody should have to enumerate and that a
SystmOne release can add without changing anything visible. The bracketed name is a
case-insensitive *contains*, because a dialog title carries what it is asking about ("Confirm
deletion of Mr Smith") and a locator pinned to the whole string would work for one patient only.

The capture tool writes this for you: click a control while a dialog is up and the suggested
locator arrives scoped to that dialog, but only when the scope is what makes it unique.

**IMAGE** (fallback): `templateRef` (a name registered via `POST /api/templates`, and
listed by `GET /api/templates`), `threshold`, and for anchor-relative locating
`anchorTemplateRef` + `offsetX`/`offsetY`.
Threshold defaults to 0.90. A match whose runner-up scores within 0.03 of it is treated as
ambiguous and resolves to *empty* — the engine tries the next locator rather than clicking
a guess (NFR-REL-04, FR-SR-03).

### Choosing what to crop

The matcher searches the whole capture and reports the best location together with the best
*competing* location elsewhere. Both numbers decide the step, so a template is only as good
as its margin over the rest of the screen:

- **Crop distinguishing context, not the control alone.** SystmOne's toolbar buttons and
  grid rows share their chrome; a crop that is mostly border and background correlates
  nearly as well with every sibling. Include the label, icon or neighbouring edge that makes
  this one different.
- **A repeated control cannot be found by its own picture.** Rows in a list are identical by
  design, and the correct outcome is the ambiguity refusal, not a lucky pick. Use
  `anchorTemplateRef` with an offset from something unique — a column header, a field label —
  or fall back to a JAB locator.
- **Raising the threshold does not fix a wrong match, and lowering it makes one likelier.**
  If the step resolves to the wrong element, the score is not the problem; the crop is.
  Check the runner-up score in the failure log before touching `threshold`.
- **Bigger, plainer crops are weaker than smaller, busier ones.** What matters is detail per
  pixel, not area: a flat expanse contributes nothing to the score but drags the runner-up up.

### Capturing templates on a scaled display

This is the single most common reason a template never matches, and it looks nothing like
a scaling problem from the outside — the step just reports that no locator resolved.

The worker captures the screen with `java.awt.Robot`, which returns the **logical**
desktop. Snipping Tool, Print Screen and every other native tool crop **physical** pixels.
On a display at 200% scaling those differ by 2x: a 2880x1800 panel is captured as
1440x900, so a control cropped natively is twice the size of the same control in the
capture. Normalised cross-correlation has no scale invariance, so the score collapses to
near zero and no threshold will rescue it.

The worker handles this: on a scaled desktop the template is matched both as stored and
shrunk into the capture's pixel space, and the better score wins. It logs the display
geometry at startup and logs `Template matched at x0.50` when the correction fires. Re-cropping
from a worker failure screenshot is still sharper, because rescaling costs a little
accuracy — budget a lower threshold (~0.85) for templates that rely on the correction.

Because offsets are measured on the same screenshot the template was cropped from, both
`offsetX`/`offsetY` and the step's `clickOffsetX`/`clickOffsetY` are interpreted in the
**template's** pixel space and scale with it. An offset of 150 measured on a 2x screenshot
lands 75 capture pixels away, so one bot definition works across workstations whose
scaling differs.

To diagnose a template that will not match, score it against a real capture:

```
java -cp target/classes \
  com.newbrid.epr_automation.systmone.automation.screen.TemplateProbe \
  worker-screenshots/<run>-step0.png bots/patient-registration/search.png
```

A peak at scale 1.00 means the threshold is too high; a peak at 0.50 means the template
was cropped at 200%; no peak anywhere means it is not of this screen.

## Clicking at an offset

There are two independent offset mechanisms. They compose, and they measure from
different origins — that is the thing to get right.

### 1. Step offset — from the element's **centre**

`clickOffsetX` / `clickOffsetY` on the step shift the action point away from the centre of
whatever the chain resolved:

```
point = (match.x + match.width / 2  + clickOffsetX,
         match.y + match.height / 2 + clickOffsetY)
```

Negative values move left/up. Use this when the thing you can reliably *recognise* is not
the thing you need to *click* — a label beside a field, an icon beside its dropdown arrow:

```json
{
  "name": "Click 40px right of the Save icon",
  "action": "CLICK",
  "timeoutMs": 8000,
  "clickOffsetX": 40,
  "clickOffsetY": 0,
  "locatorChain": [
    { "type": "IMAGE", "templateRef": "toolbar-save", "threshold": 0.95 }
  ]
}
```

### 2. Anchor-relative locator — from the anchor's **top-left**

Set `anchorTemplateRef` and the locator matches the *anchor* and then constructs a target
region at a fixed offset from the anchor's top-left corner (FR-SR-07):

```
region = (anchor.x + offsetX, anchor.y + offsetY, w, h)
w, h   = the size of templateRef's image if templateRef is set, else 1 x 1
```

With `templateRef` omitted the region is 1×1, so the click lands exactly on
`anchor top-left + (offsetX, offsetY)` — a pure coordinate click hung off a stable
landmark:

```json
{
  "name": "Type into the box right of the 'NHS Number' label",
  "action": "SET_TEXT",
  "inputValue": "${nhsNumber}",
  "timeoutMs": 8000,
  "locatorChain": [
    { "type": "IMAGE", "anchorTemplateRef": "label-nhs-number", "offsetX": 130, "offsetY": 8, "threshold": 0.94 }
  ]
}
```

With `templateRef` set, the offset positions the region's *top-left* and the click still
lands at its centre, i.e. `anchor + offset + (w/2, h/2)`. That is what you want when the
offset is measured to the corner of a control you have a template for:

```json
{
  "name": "Tick the consent checkbox",
  "action": "CLICK",
  "timeoutMs": 8000,
  "locatorChain": [
    {
      "type": "IMAGE",
      "anchorTemplateRef": "consent-panel-header",
      "templateRef": "checkbox-unticked",
      "offsetX": 12,
      "offsetY": 44,
      "threshold": 0.95
    }
  ]
}
```

Two things to know about the anchored form: `templateRef` is **not searched for** — it is
only read for its width and height, and if the registry has no such image the region
silently stays 1×1. And `threshold` applies to the *anchor* match. A missing
`anchorTemplateRef` image throws (`Template not found in registry`), so the step fails
loudly rather than clicking at a bare offset from nothing.

### 3. Composing both — row from the anchor, column from the step

Grids are the natural case: the anchor offset walks down to the row (gutter + row pitch ×
row number), the step offset walks across to the column.

```json
{
  "name": "Right-click row 4, Status column",
  "action": "RIGHT_CLICK",
  "timeoutMs": 10000,
  "clickOffsetX": 260,
  "clickOffsetY": 0,
  "locatorChain": [
    {
      "type": "IMAGE",
      "anchorTemplateRef": "appt-grid-header",
      "templateRef": "appt-row-strip",
      "offsetX": 0,
      "offsetY": 106,
      "threshold": 0.93
    }
  ]
}
```

`106` there is `18` (header-to-first-row gutter) `+ 4 × 22` (row pitch) — keep that
arithmetic in the step `name`, because the raw number tells a later reader nothing.

### Gotchas

- **Offsets do nothing on a JAB-resolved element.** `JabBackend` clicks the accessible
  bounds' centre and ignores `clickOffsetX/Y` (except as the `DRAG` delta). A step with a
  JAB locator first and an offset will therefore click the centre whenever JAB resolves,
  and honour the offset only on fallback. If the offset is the point of the step, give it
  an IMAGE-only chain.
- **`DRAG` treats the offset as a drag delta, not a click point.** Both backends drag
  from the element's centre to centre + offset, so `clickOffsetY: 60` drags the element
  60 px down. (The screen backend used to apply the offset twice; fixed, and pinned by
  `ScreenBackendActionTest`.)
- **Offsets are raw screen pixels.** Nothing rescales them, so a bot authored at 100 % DPI
  misses at 125 %. Capture templates and measure offsets at the DPI the workers run at
  (this is part of the on-host validation in `IMPLEMENTATION.md` §6).
- **Prefer a nearer anchor to a bigger offset.** Match confidence is unaffected by
  distance, but layout drift is not: the further the click point from the anchor, the more
  a version change or a resized panel moves it.

## Registering and running an example

```bash
TOKEN='X-Worker-Token: changeit'; B=http://localhost:8080

# templates the bot refers to, one per templateRef / anchorTemplateRef
curl -X POST $B/api/templates -H "$TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"appt-grid-header","s1Version":"2024","threshold":0.93,"dataBase64":"<base64 PNG>"}'

curl -X POST $B/api/bots -H "$TOKEN" -H 'Content-Type: application/json' \
  --data-binary @bots/appointment-book.json

curl -X POST $B/api/runs -H "$TOKEN" -H 'Content-Type: application/json' \
  -d '{"botId":"appointment-book","input":{"clinicName":"Dr Smith AM","reason":"Medication review"}}'
```

The [capture tool](CAPTURE-TOOL.md) authors both templates and anchor-relative locators against a
live worker screen, which is the least error-prone way to get the offsets below right.

`GET /api/templates` shows what is already registered — add `?s1Version=2024` to see one
release, or `GET /api/templates/{name}` to check a single `templateRef` before you cite it.

Upload the templates **before** registering the bot. Registration validates every
`templateRef` and `anchorTemplateRef` against the registry and returns `400` — storing
nothing — if any of them is unknown, holds no image data, or if an image locator names no
template at all. The response lists every offending reference, naming the step and the field:

```json
{
  "error": "Bot 'appointment-book' references 1 unresolved template(s); upload them with POST /api/templates first",
  "problems": ["step 3 'Select row 4' locator 0: templateRef 'appt-row-strip' is not a registered template"]
}
```

The same failure is caught again at run time, in case a template stops resolving on a worker
(bytes that are not a readable image, say): the step fails with
`templateRef 'x' did not resolve to a template image` rather than acting on a guessed region.

Re-`POST`ing a bot upserts it, so bump `version` when you change a definition — the run
history then says which version produced a given outcome.
