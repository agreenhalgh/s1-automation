# The capture tool

A third application beside the control plane and the worker: a web UI for **authoring image
locators** against a live SystmOne workstation (FR-TOOL-02/04, FR-SR-07/08). It pulls a screenshot
from a chosen worker, lets you cut templates out of it, derives anchor-relative locators from where
you click, and tests both against a freshly captured screen.

```
                    ┌──────────────────┐
   browser ────────▶│  capture tool    │──── HTTP (X-Worker-Token) ───▶ control plane :8080
   (developer)      │  :8081           │                                  registry, audit, DB
                    │  authoring       │──── AMQP request/reply ──────▶ worker
                    └──────────────────┘     systmone.control.<workerId>   Robot screenshot
```

It owns **no database**. Templates and locators are written through the control plane's REST API,
so anything authored here is indistinguishable from a hand-uploaded definition and lands in the
same audit trail. Screenshots are held **in memory only** — never written to disk (§5.4).

## Running it

The broker and the control plane come up as usual, then:

```bash
# 1. broker
docker compose up -d rabbitmq

# 2. control plane (registry + dashboard)          :8080
SPRING_PROFILES_ACTIVE=controlplane ./mvnw spring-boot:run

# 3. a worker on the machine running SystmOne
SPRING_PROFILES_ACTIVE=worker WORKER_ID=ward-pc-3 ./mvnw spring-boot:run

# 4. the capture tool                              :8081
SPRING_PROFILES_ACTIVE=authoring ./mvnw spring-boot:run
```

Then open <http://localhost:8081/> and sign in as `developer` / `developer` (dev users only, same
as the dashboard; production asserts DEVELOPER/ADMIN through OAuth2 claims). Configuration lives in
`application-authoring.yaml` — `systmone.authoring.control-plane-url`, `token`,
`capture-timeout-ms`, `max-captures`, `default-threshold`, `s1-version`.

A worker only answers capture requests when it has an **interactive desktop session**. The worker
profile now sets `spring.main.headless: false` for exactly this reason — Spring Boot's default of
`java.awt.headless=true` disables `Robot`, and with it every image locator, failure screenshot and
capture. A worker run as a Windows service has no desktop at all, and the tool says so rather than
timing out.

## The loop

**Capture.** Pick a worker and press *Capture screenshot*; optionally bring the SystmOne window to
the front first. The request goes to `systmone.control.<workerId>` and the reply carries the PNG,
the screen size and the desktop scale factor. The queue is **auto-delete**, so a request to a
stopped worker is unroutable and fails fast with which worker and why, instead of being delivered
stale minutes later.

Capturing *through the worker* is the point of the whole design. A template must be cut from the
pixels that workstation produces — its resolution, its scaling, its theme. Cropping on your own
machine with Snipping Tool is the single most common reason a locator never matches.

**Define templates.** Drag a rectangle over a control and save it with a name and threshold. The
crop is uploaded to `POST /api/templates`, and the region is remembered so the overlay shows it
outlined in green on the screenshot it came from.

**See the definitions on the screenshot.** Tick any registered template to locate it on the current
capture: the tool draws where it was found, in blue, with its score. Blue means it clears the
template's own threshold; red means the best match anywhere is below it; amber means two places
matched almost equally, which the engine refuses to act on. `Locate all` does the lot.

**Define offset locators** (FR-SR-07). For a control with nothing distinctive of its own — a grid
row, an unlabelled field — choose a stable **anchor** template, press *Locate anchor*, then click
the point the bot should act on. The tool derives `offsetX`/`offsetY` and saves the locator through
`POST /api/locators`, showing the JSON to paste into a bot definition.

Two details make the derived numbers correct rather than merely plausible:

- The offset is measured from **where the anchor actually matched**, not from where you cropped it,
  and the anchor must clear its threshold — an offset from an anchor that is not on screen is a
  number with no meaning, so the tool refuses it.
- The engine acts on the **centre** of the resolved region and multiplies stored offsets by the
  match scale. So a sizing template shifts the offset by half its size, and offsets are converted
  into the template's own pixel space. `OffsetLocatorGeometryTest` asserts the round trip: derive
  from a click, resolve with the engine's resolver, land on that same click — at 1:1 and at 200%
  scaling, with and without a sizing template.

**Read JAB attributes** (FR-TOOL-03, FR-JAB-02). Choose *Click a control to read its JAB
attributes* and click a control. The tool asks the worker what accessible element sits under that
screen point, outlines it in purple on the screenshot, and lists its role, name, description,
states, bounds and index — then proposes a JAB locator for it, ready to paste into a bot.

Prefer an attribute locator over a template wherever the control exposes itself. A template is a
picture and breaks when the picture changes: a theme, a resolution, a font, a redesigned icon.
`role` and `name` come from SystmOne itself and survive all four.

Three things about this panel are worth knowing:

- **It reads the live tree, not the screenshot.** The accessibility tree only exists inside the
  running SystmOne process; nothing about it can be recovered from a PNG afterwards. So the answer
  describes the screen *now*. If the element comes back outside the captured screen, the tool says
  the screen has moved on rather than drawing a highlight that lines up with nothing.
- **The match count is the verdict, not the attributes.** The engine refuses to act on a locator
  matching more than one element (NFR-REL-04), so the worker runs the proposed locator back over
  the same tree and reports how many it hits. One is usable. More is reported as ambiguous, with
  the advice to distinguish the control or fall back to an offset locator — never dressed up as a
  working suggestion.
- **Specificity is added only when it buys uniqueness.** Role and name come first because they
  describe the control; `index` is a position among siblings and is exactly what an extra row or a
  redesign breaks, so it is a last resort. If even that leaves the locator ambiguous, you get the
  real count rather than a false green light.

An element that reports no role, name or description has nothing to write a locator from — that is
a control painted rather than built from accessible components, and it needs a template.

A click selects the **deepest** control that covers the point, not the panel, tab or table it sits
in.
That is worth stating because the tree makes it harder than it sounds: a `page tab` reports the
bounds of its tab *button* rather than its content, a viewport reports a clipped rectangle, and some
layout nodes leave `showing` out of their state set. A container's own attributes are therefore not
evidence about what is inside it, and the walk does not treat them as such. A control that is
itself unbounded or not showing is still ruled out — the container is then the honest answer.

Table cells that are not in the tree at all — which is what a Swing `JTable` produces — are found
through the Access Bridge table API instead of by walking: the row containing the click is located
by bisecting the table's rows, then the cells of that row are checked. A click in a grid of thousands
of rows costs about a dozen probes rather than one per row. If the bisection lands nowhere, because
a grid does not lay its rows out in order or its cells report no bounds, the table itself remains
the answer as before.

Table cells get one further correction. A Swing table exposes each cell through a synthetic
accessible object, and those routinely report a rectangle measured from the table's own top-left
rather than from the screen; read literally, a cell appears to be near the top of the desktop. Where
a node's rectangle cannot be inside its parent as reported but can be once shifted by the parent's
origin, it is read that way — so a click lands on the cell, the highlight is drawn over the cell,
and a run drives the mouse to the cell rather than to the corner of the screen. A node that fits
neither reading is left exactly as reported, since an overlay or a stale rectangle is not a
coordinate-space problem and translating it would only move the error.

**The neighbours are shown too.** Under the attributes, the panel names the elements immediately
before and after — `before: label[Surname] · after: label[Forename]` — written exactly as a
`previousSibling` or `followingSibling` selector would name them, so writing one by hand needs no
guessing about whether the label beside a field calls itself `label`.

The suggestion uses them where they help. For a control with nothing of its own — an unlabelled
text field, the usual case — the ladder tries the neighbour before it, then the one after, then
both, and only falls back to `index` when neither can be named. A neighbour describes the form the
way an author reads it; a position among siblings describes the layout as it happens to be today
and shifts the moment a label is added. A control that names itself still gets the simpler locator.

**Or read the focused element.** *Read the focused element* asks the worker which control
SystmOne itself considers focused — no clicking on the screenshot, and no coordinates involved.
Tab or click to a field in SystmOne, come back to the tool, press the button. It reads the
`focused` state in the accessibility tree rather than the operating system's foreground window,
which is why it still works from a browser that has taken the desktop's focus. A focused container
and the focused control inside it both carry the state, so the deepest one is reported: the thing
with the caret in it.

A window with nothing focused has nothing to report, and says so rather than guessing.

**Save it.** Once an element is inspected, *Save locator* stores the JSON in the registry under a
name a bot can reference, through the same `POST /api/locators` the offset locators use. The name
box is pre-filled from what the control calls itself — "Search patient" becomes
`search-patient` — and anything you type is left alone. What is saved is what is on screen, so
editing the JSON to drop a brittle `index` before saving does what you would expect.

Saving an ambiguous locator is allowed, and the confirmation says so: the count is on screen, and
an author who wants it anyway is usually about to add something to the definition by hand. What is
refused is a locator with no `role`, `name` or `description` at all, because it would match on
nothing.

**Modal dialogs work.** Every window of SystmOne's JVM is searched, so clicking into a dialog
reads the dialog, and the panel names it in the *Inside:* chain. Because the window behind stays
matchable and tends to hold identically named buttons, a suggestion made while a dialog is up
comes back scoped with `ancestorPath` — but only when that scope is what makes it unique, since
pinning a locator to a window title costs something the day the title changes.

**Test** (FR-TOOL-04). *Test* on any template or locator captures a **new** screenshot from the
same worker and resolves against it, then displays that screenshot with the matched template
outlined and the action point crosshaired, plus score, runner-up, match scale and resolution time.
When it fails, the panel carries the engine's own diagnosis — under threshold, ambiguous, or the
wrong scale — and the screen it failed on.

## Why matching is not reimplemented here

`ImageLocatorResolver` holds the matching semantics — threshold, ambiguity margin, DPI rescaling,
anchor offsets — and both `ScreenBackend` (the engine) and this tool call it. The engine adds time
and input on top: polling captures until the step's timeout, then driving the mouse.

That shared resolver is deliberate. A tool that judged matches by rules of its own would be worse
than no tool: it would certify locators the engine then fails to resolve. It also means the tool
reproduces the matcher's real behaviour rather than an idealised version. When a template genuinely
appears twice on screen, the box lands on one of them and the panel reports the runner-up scoring
just as well — that amber verdict is the signal the template needs more distinguishing context, and
it is exactly what the engine would refuse to act on at run time.

The same argument applies to `JabMatching`, which decides whether one accessible node satisfies one
JAB locator. `JabBackend` uses it to resolve locators during a run and `JabInspector` uses it to
count how many elements a suggestion matches, so the tool's "matches one element" means the engine's
"resolves".

## Patient data

A screenshot of a live SystmOne session is patient data. This tool therefore:

- holds captures in memory only, capped at `max-captures` and evicted oldest-first;
- serves them `Cache-Control: no-store`, so they are not left in the browser cache;
- offers *Discard* per capture and *Discard all*, and keeps nothing across a restart.

Templates you save are, by design, cropped fragments of that screen stored in the registry. Crop
controls and chrome — never a region containing patient identifiers.
