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
  "index": 0,                  // zero-based; steps run in list order
  "name": "Click Search",      // label for logs, the run UI and step reports
  "action": "CLICK",           // see ActionType below
  "locatorChain": [ ... ],     // ordered; first to resolve wins (FR-ENG-02)
  "inputValue": "${nhsNumber}",// SET_TEXT / SELECT / KEYS; ${var} from the run input
  "outputKey": "patientName",  // GET_TEXT / READ_STATE store their value here
  "timeoutMs": 8000,           // budget for the whole chain (split across its locators)
  "retry": { "maxAttempts": 2, "initialBackoffMs": 400, "multiplier": 2.0, "maxBackoffMs": 3000 },
  "continueOnFailure": false,  // true → step records SKIPPED and the run continues
  "clickOffsetX": 40,          // click-point offset, see below
  "clickOffsetY": 0
}
```

`action` is one of `CLICK, DOUBLE_CLICK, RIGHT_CLICK, HOVER, FOCUS, SET_TEXT, GET_TEXT,
SELECT, EXPAND, COLLAPSE, READ_STATE, KEYS, DRAG, ACTIVATE, WAIT`. `WAIT` asserts the
locator resolves and acts on nothing. `GET_TEXT`, `READ_STATE`, `EXPAND` and `COLLAPSE`
are JAB-only — the screen backend throws `UnsupportedOperationException` for them, which
the engine treats as a step failure. `ACTIVATE` is described below.

Omitted `retry` inherits the bot's `defaultRetry` (default 3 attempts, 250 ms → 500 ms →
1 s). `timeoutMs` of 0 or less becomes 10 000 ms; `globalTimeoutMs` of 0 means no ceiling.

## Activating the application

`KEYS` and `SET_TEXT` ultimately go to whichever window owns the keyboard focus, so a bot
that types before SystmOne is in front types into someone else's window. `ACTIVATE` brings
it to the foreground first — and it is the one action that needs **no locator chain**:

```jsonc
{
  "index": 0,
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
`description`, `value`, `index`, `ancestorPath`.

**IMAGE** (fallback): `templateRef` (a name registered via `POST /api/templates`, and
listed by `GET /api/templates`), `threshold`, and for anchor-relative locating
`anchorTemplateRef` + `offsetX`/`offsetY`.
Threshold defaults to 0.90. A match whose runner-up scores within 0.03 of it is treated as
ambiguous and resolves to *empty* — the engine tries the next locator rather than clicking
a guess (NFR-REL-04, FR-SR-03).

### Capturing templates on a scaled display

This is the single most common reason a template never matches, and it looks nothing like
a scaling problem from the outside — the step just reports that no locator resolved.

The worker captures the screen with `java.awt.Robot`, which returns the **logical**
desktop. Snipping Tool, Print Screen and every other native tool crop **physical** pixels.
On a display at 200% scaling those differ by 2x: a 2880x1800 panel is captured as
1440x900, so a control cropped natively is twice the size of the same control in the
capture. Normalised cross-correlation has no scale invariance, so the score collapses to
near zero and no threshold will rescue it.

The worker handles this: a template is matched as stored, and if that misses on a scaled
desktop it is retried shrunk into the capture's pixel space. It logs the display geometry
at startup and logs `Template matched at x0.50` when the correction fires. Re-cropping
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
  "index": 0,
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
  "index": 1,
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
  "index": 2,
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
  "index": 4,
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
