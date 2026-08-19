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
reproduces the matcher's real behaviour rather than an idealised version — including that on a
full-screen capture the coarse-to-fine search can settle on a *different* location than the one a
template was cropped from when the screen has repetitive content. Seeing that box land in the wrong
place is exactly the signal that the template needs more distinguishing context, and it is what the
engine would have done at run time.

## Patient data

A screenshot of a live SystmOne session is patient data. This tool therefore:

- holds captures in memory only, capped at `max-captures` and evicted oldest-first;
- serves them `Cache-Control: no-store`, so they are not left in the browser cache;
- offers *Discard* per capture and *Discard all*, and keeps nothing across a restart.

Templates you save are, by design, cropped fragments of that screen stored in the registry. Crop
controls and chrome — never a region containing patient identifiers.
