# SystmOne RPA Framework — Implementation Guide

This document describes the implementation of the requirements in
[`requirements.md`](requirements.md): how the code is organised, how to run it, how
it maps to the requirements, and what has been verified versus what must be validated
on a Windows host running SystmOne.

## 1. Shape of the system

One Spring Boot artefact runs in one of two **roles**, selected by Spring profile:

| Role | Profile | Responsibilities |
|------|---------|------------------|
| **Control plane** | `controlplane` (default) | Web UI + REST API, dispatch to RabbitMQ, idempotent results endpoint, persistence, registries, security |
| **Worker** | `worker` | Consume jobs from RabbitMQ, run the engine against SystmOne, POST outcomes back |

Transport is asymmetric (as specified): **jobs go down over RabbitMQ**, **results come
up over HTTP POST** (§2.1 of the requirements).

## 2. Package layout

```
com.newbrid.epr_automation.systmone
├── common
│   ├── model            RunStatus, StepStatus, ActionType, LocatorType, NameMatch
│   └── dto              Wire + definition contracts: BotDefinition, StepDefinition,
│                        LocatorDefinition, RetryPolicyDef, RunJobMessage,
│                        StepReport, StatusReport, Heartbeat, WorkerRegistration, ExceptionInfo
├── sdk
│   ├── locator          AutomationBackend (the JAB/screen abstraction, NFR-MNT-01),
│   │                    BackendRegistry, ElementHandle, ActionParams
│   └── engine           BotEngine (fallback chain, retry, fail-safe, timeouts),
│                        ExecutionContext, RunListener, RunResult, ScreenshotSink
├── automation
│   ├── jab              AccessBridge facade; JnaAccessBridge (WindowsAccessBridge-64.dll
│   │                    via JNA) + NullAccessBridge; JabBackend; WindowsAccessBridgeLib
│   └── screen           TemplateMatcher (pure-Java NCC), ScreenBackend, RobotInput,
│                        TemplateStore, MatchResult
├── config               RabbitTopologyConfig (queues, DLX/DLQ)
├── controlplane         domain (JPA), repo, service, amqp, web (api + ui), security
└── worker               WorkerConfig, JobListener, ControlPlaneClient, PostingRunListener,
                         HttpTemplateStore, LocalScreenshotSink, WorkerRegistrationRunner
```

## 3. Requirement → code map (highlights)

| Requirement | Where |
|-------------|-------|
| FR-JAB-01/02/04 Attach & traverse accessibility tree, actions | `automation.jab.JnaAccessBridge`, `WindowsAccessBridgeLib`, `JabBackend` |
| FR-JAB-08 Coordinate-click fallback from accessible bounds | `JabBackend.perform` → `RobotInput` |
| FR-SR-02/03 Template match + confidence threshold | `automation.screen.TemplateMatcher`, `ScreenBackend` |
| FR-SR-06/07 Robot actions, anchor-relative locators | `ScreenBackend`, `RobotInput` |
| NFR-REL-04 Fail-safe on ambiguity/low confidence | `MatchResult.isAmbiguous`, `ScreenBackend`, `JabBackend`, engine |
| FR-ENG-02/03 Fallback chain + retry-with-backoff | `sdk.engine.BotEngine` |
| FR-ENG-08 Global timeout + abort | `BotEngine.run(...deadline, aborted)` |
| FR-CP-01 Dispatch to queue | `controlplane.service.DispatchService`, `amqp.JobPublisher` |
| FR-CP-02 / 02a Idempotent results endpoint | `web.api.ResultsApiController`, `service.ResultService` |
| FR-CP-03 Operator UI | `web.ui.DashboardController`, `templates/index.html`, `run.html` |
| FR-CP-06 Worker health | `service.WorkerService`, `WorkerAgentEntity` |
| FR-CP-08 / FR-REG-* Registries | `service.RegistryService`, `TemplateEntity`, `LocatorEntity` |
| FR-WRK-01/02 Competing consumer, prefetch 1 | `worker.WorkerConfig.workerListenerFactory`, `JobListener` |
| FR-WRK-03/04/05 POST results, ack-after-POST, retry→DLQ | `worker.JobListener`, `PostingRunListener`, `ControlPlaneClient` |
| FR-WRK-08 Self-registration | `worker.WorkerRegistrationRunner` |
| NFR-SEC-01 OAuth2 + RBAC + worker token | `controlplane.security.SecurityConfig`, `WorkerTokenFilter` |
| NFR-SEC-05 Audit trail | `service.AuditService`, `AuditEntity` |

## 4. Running it locally

### Prerequisites
- JDK 25, the bundled Maven wrapper (`./mvnw`).
- A RabbitMQ broker for the dispatch→worker→results loop: `docker compose up -d`
  (see [`docker-compose.yaml`](../docker-compose.yaml) — loopback-only by default; set
  `RABBITMQ_BIND=0.0.0.0` plus real credentials when workers connect from other hosts).
- The control plane defaults to in-memory **H2**; point at PostgreSQL with
  `JDBC_URL`/`JDBC_USER`/`JDBC_PASSWORD` for production (NFR-CMP-03).

Both roles are the same artefact; the active profile picks the role, and `controlplane`
is the default in `application.yaml`.

### Start the control plane
```powershell
.\mvnw.cmd spring-boot:run
# UI:   http://localhost:8080  (dev login: operator/operator, developer/developer, auditor/auditor, admin/admin)
# API:  http://localhost:8080/api/**  (header  X-Worker-Token: changeit)
# Health: http://localhost:8080/actuator/health
```

### Start one or more workers (each on a SystmOne workstation)
```powershell
$env:CONTROL_PLANE_URL = 'http://<control-plane-host>:8080'
$env:SPRING_PROFILES_ACTIVE = 'worker'
.\mvnw.cmd spring-boot:run
# or, packaged:  java -jar target\systmone-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
```
Windows PowerShell splits `-Dspring-boot.run.profiles=worker` at the hyphen in
`spring-boot`, so Maven reports `Unknown lifecycle phase ".run.profiles=worker"`. Quote the
whole argument (`"-Dspring-boot.run.profiles=worker"`), or set the env var as above.
`SPRING_PROFILES_ACTIVE` persists for the rest of the shell session — clear it before
starting a control plane from the same window.

Start the command again on another host to add a second competing consumer — no other
change is needed (NFR-PERF-04). Each worker needs the Access Bridge enabled once per JRE
(`jabswitch -enable`, then restart the JVM) and an **interactive desktop session**:
`java.awt.Robot` is unavailable headless, so a worker run as a service silently loses the
screen-recognition backend. A worker runs no web server, so confirm it through the broker
instead — `docker exec systmone-rabbit rabbitmqctl list_queues name messages consumers`
shows one consumer per live worker.

### Example: capture a template, register a bot, dispatch
Templates come first: registration rejects a definition whose image locators name templates
that are not in the registry (see the validation note below).
```bash
TOKEN='X-Worker-Token: changeit'; B=http://localhost:8080

# 1. Upload a captured partial screenshot as an image locator
curl -X POST $B/api/templates -H "$TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"patient-button","s1Version":"2024","threshold":0.9,"dataBase64":"<base64 PNG>"}'

# 2. Register a bot (JAB-first locator, image fallback)
curl -X POST $B/api/bots -H "$TOKEN" -H 'Content-Type: application/json' -d '{
  "botId":"open-patient","name":"Open patient record","version":1,"s1Version":"2024",
  "globalTimeoutMs":60000,
  "steps":[
    {"index":0,"name":"Click Patient","action":"CLICK","timeoutMs":8000,
     "locatorChain":[
       {"type":"JAB","role":"push button","name":"Patient","nameMatch":"EXACT"},
       {"type":"IMAGE","templateRef":"patient-button","threshold":0.9}
     ]},
    {"index":1,"name":"Type NHS number","action":"SET_TEXT","inputValue":"${nhsNumber}","timeoutMs":8000,
     "locatorChain":[{"type":"JAB","role":"text","name":"NHS Number","nameMatch":"CONTAINS"}]}
  ]}'

# 3. Dispatch a run with an input record
curl -X POST $B/api/runs -H "$TOKEN" -H 'Content-Type: application/json' \
  -d '{"botId":"open-patient","input":{"nhsNumber":"9990000018"},"capabilityTag":null}'
# -> {"runId":"..."}  then watch it at  http://localhost:8080/runs/<runId>
```

**Template-reference validation.** `POST /api/bots` returns `400` and registers nothing if any
image locator names a template that is absent from the registry, holds no image data, or if an
image locator names no template at all. Every offending reference is listed, so one response
covers them all:
```json
{
  "error": "Bot 'open-patient' references 2 unresolved template(s); upload them with POST /api/templates first",
  "problems": [
    "step 0 'Click Patient' locator 1: templateRef 'patient-buton' is not a registered template",
    "step 5 'Select row' locator 0: anchorTemplateRef 'results-grid-header' is not a registered template"
  ]
}
```

**Browsing the registry.** Read access is keyed by template *name* — the same value a bot
cites as `templateRef`:
```bash
# every template, ordered by name (page/size default to 0/50, as on /api/runs)
curl -s $B/api/templates -H "$TOKEN"
# only what was captured for one SystmOne release (FR-REG-02)
curl -s "$B/api/templates?s1Version=2024" -H "$TOKEN"
# one template's metadata; 404 if the name is not registered
curl -s $B/api/templates/patient-button -H "$TOKEN"
# -> {"id":"...","name":"patient-button","s1Version":"2024","threshold":0.9,
#     "screenContext":"patient-record","createdBy":"api","checksum":"9e4b26...","bytes":70,
#     "createdAt":"2026-08-19T15:19:26.732452Z"}
```
The image bytes stay out of these payloads — `checksum` and `bytes` describe them, and
`GET /api/templates/{name}/image` still serves the PNG itself for workers.

**Authoring by hand is not the only way in.** The [capture tool](CAPTURE-TOOL.md) is a third
application (`--spring.profiles.active=authoring`, port 8081) that pulls a screenshot from a chosen
worker, cuts templates out of it, derives anchor-relative locators from a click, and tests both
against a freshly captured screen. It writes through the same `/api/templates` and `/api/locators`
endpoints used above, so nothing about the registry changes.

Worked bot definitions live in [`bots/`](../bots) — including
[`offset-clicks.json`](../bots/offset-clicks.json), which demonstrates every way to click
at a position relative to a matched image. [`BOT-DEFINITIONS.md`](BOT-DEFINITIONS.md)
documents the step/locator fields and the offset geometry.

## 5. What has been verified here

Automated tests (`./mvnw test`, all green) and a live HTTP smoke test confirmed:
- Engine fallback JAB→image, retry-then-succeed, fail-safe-on-no-match, output capture
  (`BotEngineTest`).
- Template matching locates a patch, scores ~1.0, and flags ambiguous / below-threshold
  matches (`TemplateMatcherTest`).
- Results endpoint applies a terminal outcome once and ignores duplicates
  (`ResultServiceIdempotencyTest`, FR-CP-02a).
- Control plane boots on H2 with all 7 repositories; UI redirects unauthenticated users
  to login; `/api/**` rejects requests without a worker token (403); bot registration,
  template upload + image fetch, and worker registration all work; unknown-run POST → 404.
- Template registry read/list: `GET /api/templates` lists metadata ordered by name and
  honours `s1Version`, `page` and `size`; `GET /api/templates/{name}` returns one template's
  metadata and 404s on an unknown name; both stay behind the worker token
  (`TemplateApiControllerTest` plus a live HTTP check).
- The **capture tool** end to end, with all three profiles and RabbitMQ 4 running on this host:
  a screenshot pulled from a worker over the control queue (1440x900 at 2.0x scaling), a template
  cut from it and matched back at score 1.000, an offset locator derived from a click and resolving
  to that exact point — with and without a sizing template — and a live test that captured a fresh
  screen and resolved against it in ~670 ms. An under-threshold anchor was refused with its score,
  a re-authored locator became version 2, `/api/**` still rejected an untokened call, the tool's own
  JSON surface rejected a request without its CSRF token (403), and unauthenticated browsing
  redirected to login. Offset arithmetic is pinned by `OffsetLocatorGeometryTest`
  (`AuthoringServiceTest`, `CaptureStoreTest`, `ControlQueueTest` cover the rest).
- The **worker profile boots**, the **Windows Access Bridge loads and initialises** on
  this Windows host, the worker **registers with the control plane over HTTP**, and its
  listener waits on RabbitMQ as a competing consumer. (Screen adapter reports unavailable
  in a headless session — the intended graceful degradation.)

## 6. What still needs on-host validation (Risk R1)

- **JAB tree traversal and actions against real SystmOne.** Partially closed. Against a live
  SystmOne (32-bit JRE 11.0.9.1) the bridge now resolves the top-level frame, decodes
  `AccessibleContextInfo` correctly (`role=frame`, accessible name, `children=1`, bounds
  `130,122 1024x738`) and traverses to the child `root pane` — so the struct layouts and
  calling conventions hold. Still unproven on real widgets: `doAccessibleActions`,
  `setTextContents` and `getAccessibleTextRange` (the session under test was locked), and
  deep traversal to find named controls.

  Two findings from that spike, both fixed in `JnaAccessBridge`: the Access Bridge only
  discovers JVMs while the **calling thread pumps Windows messages** — without a pump
  `isJavaWindow` answers FALSE for every window, including a target whose JVM has the
  bridge loaded — and its state is **thread-affine**, so calls must run on the thread that
  called `Windows_run()`, not on a worker's AMQP listener thread. Note also that bitness is
  not a constraint on the *target*: the 64-bit client DLL resolves contexts inside the
  32-bit SystmOne JVM.
- **Screen recognition on a real display** (DPI scaling, multi-monitor) — the matcher and
  Robot input are exercisable only in an interactive session.
- **The full dispatch→worker→results loop and multi-worker failover** require a running
  RabbitMQ broker (acceptance criteria 4 and 5).

## 7. Production hardening not included in this build

Called out so they are not mistaken as done: OAuth2 provider wiring (the hook is present —
`SecurityConfig` activates `oauth2Login` when a client registration is configured);
secrets manager integration; TLS for AMQP/HTTP (NFR-SEC-04); PID redaction/encryption and
retention for screenshots and JAB snapshots (NFR-SEC-03); OCR (FR-SR-05); a Postgres
schema migration (Flyway/Liquibase) in place of `ddl-auto`; and reclaiming runs from dead
workers via heartbeat timeout.
