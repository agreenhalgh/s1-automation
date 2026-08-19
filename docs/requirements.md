# Requirements Specification — SystmOne RPA Framework

**Project:** `com.newbrid.epr_automation.systmone`
**Document status:** Draft v0.1
**Date:** 2026-08-14
**Owner:** EPR Automation Team

---

## 1. Introduction

### 1.1 Purpose

This document specifies the functional and non-functional requirements for an
**Robotic Process Automation (RPA) framework** that automates interactions with
**TPP SystmOne**, the Java-based clinical Electronic Patient Record (EPR) system
used widely across NHS primary and community care.

The framework provides two complementary automation strategies:

1. **Accessibility-driven automation** via the **Java Access Bridge (JAB)** —
   the primary, preferred mechanism. Because SystmOne is a Java desktop
   application, its UI exposes a structured accessibility tree that can be
   queried and acted upon programmatically. This gives robust, resolution- and
   layout-independent element location.
2. **Screen-recognition automation** — a fallback / complementary mechanism in
   which a developer captures partial screenshots of screen regions or controls,
   the framework locates those regions on screen (template matching and/or OCR),
   and drives the mouse/keyboard against them. Used where JAB does not expose an
   element (custom-painted widgets, embedded documents, images, third-party
   panes).

### 1.2 Scope

**In scope**
- A reusable Java library/SDK for authoring SystmOne automations ("bots").
- A JAB adapter that connects to a running SystmOne process and exposes its
  accessible element tree.
- A screen-recognition adapter (image template matching + OCR) with a
  developer capture tool for partial screenshots.
- A Spring Boot control plane (web UI + REST API) for scheduling, dispatching,
  monitoring and auditing automation runs.
- Job distribution and back-pressure via RabbitMQ (AMQP).
- Authentication/authorisation of framework operators via OAuth2.
- Persistence of run history, audit logs and element definitions in PostgreSQL.

**Out of scope**
- Any modification of SystmOne itself, TPP APIs, or direct database access to
  the SystmOne clinical database.
- Clinical decision-making logic (the framework executes defined processes; it
  does not make clinical decisions).
- Deployment to end-user clinical workstations without TPP/organisational
  approval.

### 1.3 Definitions, acronyms & abbreviations

| Term | Meaning |
|------|---------|
| **RPA** | Robotic Process Automation — software that drives a GUI as a human would |
| **SystmOne (S1)** | TPP's Java-based clinical EPR application |
| **JAB** | Java Access Bridge — bridges the Java Accessibility API to native Windows assistive-technology clients |
| **Accessibility tree** | Hierarchical model of UI components exposed via `javax.accessibility.AccessibleContext` |
| **`WindowsAccessBridge-64.dll`** | Native Windows library exposing the JAB C API to out-of-process clients |
| **Bot / Automation** | A defined, repeatable sequence of steps against SystmOne |
| **Locator** | A rule that identifies a UI element (by accessibility attributes or by image) |
| **Template matching** | Locating a captured image patch within a live screenshot |
| **OCR** | Optical Character Recognition (reading text from pixels) |
| **Control plane** | The Spring Boot service that schedules, dispatches and monitors runs |
| **Runner / Agent** | The process on a Windows host that executes bot steps against S1 |

### 1.4 References
- Java Accessibility API — `javax.accessibility` (JDK 25).
- Java Access Bridge (`jabswitch`, `WindowsAccessBridge-64.dll`).
- Existing project scaffold: Spring Boot 4.1.0, Java 25, `spring-boot-starter-amqp`,
  `spring-boot-starter-security-oauth2-client`, `spring-boot-starter-thymeleaf`,
  `spring-boot-starter-webmvc`, PostgreSQL driver.

---

## 2. Overall description

### 2.1 Product perspective

The framework is a distributed system with a clear split between a **control
plane** (centralised, headless, Spring Boot) and one or more **worker processes**
(each co-located with a SystmOne client session on a Windows host).

Transport is **asymmetric** by design:

- **Downstream (dispatch): RabbitMQ.** The control plane publishes run jobs to a
  work queue; **many competing worker processes** consume from that same queue.
  RabbitMQ's competing-consumer model gives load-balancing, back-pressure and
  horizontal scale-out for free — add a worker, it starts pulling jobs.
- **Upstream (results): HTTP POST.** A worker **POSTs** outcomes — status
  transitions, step results, outputs, and **exceptions/stack traces** — back to
  the control plane's REST API. Results are not published back over AMQP.

This split keeps the broker as a pure work distributor and makes the result path
a first-class, request/response, individually-authenticated API that any number
of workers can call concurrently.

```
                        ┌──────────────────────────────────────────┐
                        │            Control Plane (Spring Boot)     │
                        │  Web UI (Thymeleaf) · REST API (results)   │
   Operator ──OAuth2──▶ │  Scheduler · Run orchestration             │
                        │  Element/Locator registry                  │
                        │  Audit & run history  ── PostgreSQL        │
                        └───────┬───────────────────────▲────────────┘
                                │ AMQP publish           │ HTTPS POST
                    job dispatch│ (jobs queue)           │ outcomes / outputs /
                                ▼                        │ exceptions / heartbeats
                        ┌───────────────┐                │
                        │  RabbitMQ     │                │  (competing consumers;
                        │  jobs queue   │                │   N workers, each POSTs
                        │  + DLQ        │                │   its own results)
                        └──┬────┬────┬──┘                │
                  consume  │    │    │  consume          │
              ┌────────────┘    │    └───────────┐       │
              ▼                 ▼                ▼        │
        ┌──────────┐      ┌──────────┐     ┌──────────┐  │
        │ Worker 1 │      │ Worker 2 │ ... │ Worker N │──┘
        └────┬─────┘      └────┬─────┘     └────┬─────┘
             │  zoom into one worker (each = one runner agent on a Windows host):
             ▼
             ┌────────────────────────────────────────────────────────┐
             │                    Runner Agent (Windows host)         │
             │  ┌───────────────┐        ┌──────────────────────────┐ │
             │  │ Automation    │  uses  │ Automation Engine (SDK)   │ │
             │  │ (Bot) def     │──────▶ │  · Step executor          │ │
             │  └───────────────┘        │  · Locator resolution     │ │
             │                           └───────┬──────────┬────────┘ │
             │                     ┌─────────────▼──┐   ┌───▼────────┐  │
             │                     │ JAB Adapter     │   │ Screen-rec │  │
             │                     │ (WindowsAccess  │   │ Adapter    │  │
             │                     │  Bridge-64.dll) │   │ (OpenCV/   │  │
             │                     └────────┬────────┘   │  OCR/Robot)│  │
             │                              │            └─────┬──────┘  │
             └──────────────────────────────┼──────────────────┼────────┘
                                            ▼                  ▼
                                   ┌───────────────────────────────────┐
                                   │        TPP SystmOne (Java desktop)  │
                                   └───────────────────────────────────┘
```

### 2.2 Automation strategy priority

For any given step, the engine resolves the target element using this priority:

1. **JAB locator** — preferred; deterministic and layout-independent.
2. **Screen-recognition locator** — used when a JAB locator is absent or fails
   to resolve within its timeout.
3. **Explicit fallback chain** — a bot may declare an ordered list of locators;
   the engine tries each in turn and records which one succeeded.

### 2.3 User classes

| User class | Description | Primary needs |
|------------|-------------|---------------|
| **Automation Developer** | Authors bots, captures locators/screenshots, tests runs | SDK, capture tooling, locator registry, debug/replay |
| **Operator** | Schedules, launches and monitors runs in production | Web UI, dashboards, alerts, retry/abort controls |
| **Auditor / Governance** | Reviews what the automation did and when | Immutable audit trail, run history, screenshots-on-failure |
| **System Administrator** | Manages runner agents, credentials, connectivity | Agent health, config, secrets, RBAC |

### 2.4 Assumptions & dependencies
- Runner agents run on **Windows** (SystmOne's supported platform) with a Java
  runtime that has the Access Bridge available (`jabswitch -enable`).
- SystmOne is installed and a user session can be established on the runner host.
- The Access Bridge is enabled system-wide or per-JVM on the runner host.
- Network connectivity exists from each worker to the RabbitMQ broker (to consume
  jobs) **and** to the control plane's HTTPS REST endpoint (to POST results).
- Automation credentials for SystmOne (smartcard/login) are provisioned per
  organisational and TPP policy — see §5.4 (Security & compliance).

---

## 3. Functional requirements

Requirements use **MoSCoW** priority: **M**ust / **S**hould / **C**ould / **W**on't (this release).

### 3.1 Java Access Bridge automation

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-JAB-01 | M | The framework **shall** attach to a running SystmOne process by window handle / process id and obtain the root `AccessibleContext` via the Windows Access Bridge (`WindowsAccessBridge-64.dll`). |
| FR-JAB-02 | M | The framework **shall** traverse the accessibility tree, exposing for each node: role, name, description, states (enabled, focused, selected, visible, checked), bounds (screen x/y/w/h), value, and parent/child/index relationships. |
| FR-JAB-03 | M | The framework **shall** locate elements by a **locator expression** combining any of: role, name (exact/contains/regex), description, value, index-within-parent, and ancestor path. |
| FR-JAB-04 | M | The framework **shall** support the actions: click, double-click, focus, set-text/value, get-text/value, select (list/combo/tree item), expand/collapse, and read state. |
| FR-JAB-05 | M | The framework **shall** wait for an element to reach a condition (exists / visible / enabled / text-equals) with a configurable timeout and poll interval. |
| FR-JAB-06 | S | The framework **should** support keyboard input routed to a focused accessible component (including modifier chords and SystmOne keyboard shortcuts). |
| FR-JAB-07 | S | The framework **should** subscribe to Access Bridge events (focus change, property change, menu/popup open) to synchronise steps rather than fixed sleeps. |
| FR-JAB-08 | M | Where JAB action APIs are unavailable for a node, the framework **shall** derive the element's screen coordinates from its accessible bounds and drive an OS-level click via `java.awt.Robot`. |
| FR-JAB-09 | C | The framework **could** cache resolved locator paths per SystmOne version to speed up repeat resolution, invalidating on version change. |
| FR-JAB-10 | M | The framework **shall** fail a step with a structured, diagnosable error (locator, tree snapshot, screenshot) when an element cannot be resolved within its timeout. |

> **Design note — out-of-process access.** SystmOne runs as a separate JVM.
> Because the Java Accessibility API is only directly reachable in-process, the
> JAB adapter communicates with SystmOne's JVM through the native Windows Access
> Bridge C API. The recommended binding approach is **JNA** (Java Native Access)
> over `WindowsAccessBridge-64.dll`, wrapped behind a clean Java interface so bot
> authors never touch native code. This is an architecturally significant
> constraint and must be prototyped early (see §7 Risks).

### 3.2 Screen-recognition automation

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-SR-01 | M | The framework **shall** provide a **capture tool** that lets a developer select a rectangular region of the screen and save it as a named image locator ("template"). |
| FR-SR-02 | M | The framework **shall** locate a saved template within a live screenshot using image template matching, returning match location, size and a confidence score. |
| FR-SR-03 | M | The framework **shall** support a configurable **match confidence threshold** per locator, and **shall** fail the step when no match meets the threshold within its timeout. |
| FR-SR-04 | S | The framework **should** support **multi-scale / tolerance** matching to handle DPI scaling and minor rendering differences. |
| FR-SR-05 | S | The framework **should** provide **OCR** to read text from a region and to locate an element by its rendered text. |
| FR-SR-06 | M | On a match, the framework **shall** compute the click point (default: match centre; configurable offset) and drive click / double-click / right-click / hover / drag via `java.awt.Robot`. |
| FR-SR-07 | S | The framework **should** support **anchor-relative** locators: locate a stable template, then act on a region at a defined offset from it. |
| FR-SR-08 | M | The framework **shall** let the developer preview a match (highlight the located region) during authoring and store the template with metadata (name, expected screen, threshold, created-by, S1 version). |
| FR-SR-09 | C | The framework **could** support masking regions of a template (ignore volatile pixels such as timestamps) to improve match stability. |
| FR-SR-10 | M | The framework **shall** capture a full-screen (or window) screenshot on match failure for diagnosis, subject to the redaction rules in §5.4. |

### 3.3 Bot authoring & execution engine (SDK)

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-ENG-01 | M | The framework **shall** provide a Java SDK for defining a bot as an ordered set of steps, each step referencing a locator (JAB or screen) and an action. |
| FR-ENG-02 | M | The framework **shall** support an **ordered fallback chain** of locators per step (JAB first, then screen recognition) with per-locator timeouts. |
| FR-ENG-03 | M | The framework **shall** support control flow: sequential steps, conditional branches, loops over input datasets, and retry-with-backoff on transient failure. |
| FR-ENG-04 | M | The framework **shall** support parameterised bots driven by an input record (e.g. a patient/task dataset) supplied at dispatch time. |
| FR-ENG-05 | S | The framework **should** support reusable **sub-flows** (e.g. "log in", "open patient record", "navigate to consultation") composable across bots. |
| FR-ENG-06 | M | The framework **shall** produce a structured, step-by-step execution log with timings, resolved locator, outcome, and error detail. |
| FR-ENG-07 | S | The framework **should** support a **dry-run / step-through** mode for developers that pauses between steps and shows the resolved element. |
| FR-ENG-08 | M | The framework **shall** enforce a global run timeout and a graceful **abort** that returns SystmOne to a known-safe state where defined. |
| FR-ENG-09 | S | The framework **should** support **idempotency / checkpointing** so an interrupted run can be safely resumed or safely re-run. |

### 3.4 Control plane — orchestration & UI

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-CP-01 | M | The control plane **shall** dispatch bot runs by **publishing job messages to a RabbitMQ queue**, from which **multiple worker processes consume competitively** (competing-consumer pattern), with a work queue per capability/worker pool. |
| FR-CP-02 | M | The control plane **shall** expose an authenticated **REST endpoint that receives outcome reports POSTed by workers** — run/step status transitions, results/outputs, telemetry, and **exceptions (message + stack trace + failure context)** — and **shall** persist them against the correct run. Results are **not** returned over AMQP. |
| FR-CP-02a | M | The results endpoint **shall** accept concurrent POSTs from many workers, correlate each to its run/step by run id, and be **idempotent** (a redelivered job re-reporting the same terminal outcome must not create duplicate records). |
| FR-CP-03 | M | The control plane **shall** provide a Thymeleaf web UI to: register bots, launch runs (ad-hoc), view live run status, view history, and download logs/screenshots. |
| FR-CP-04 | S | The control plane **should** support **scheduling** (cron-style) and queued batch runs over an input dataset. |
| FR-CP-05 | M | The control plane **shall** expose a REST API for the same operations (list bots, dispatch run, query status/results). |
| FR-CP-06 | M | The control plane **shall** track runner agent registration and health (heartbeat, last-seen, current load, S1 version). |
| FR-CP-07 | S | The control plane **should** support **retry** of failed runs (whole run or from checkpoint) and **abort** of in-flight runs. |
| FR-CP-08 | M | The control plane **shall** manage the **locator/element registry** (JAB locators and image templates) versioned per SystmOne release. |
| FR-CP-09 | S | The control plane **should** provide dashboards: success rate, mean run time, failure reasons, throughput per agent. |
| FR-CP-10 | C | The control plane **could** raise alerts (email/webhook) on failure-rate thresholds or agent outages. |

### 3.5 Locator / element registry

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-REG-01 | M | The registry **shall** store JAB locator definitions and image templates with metadata (name, owner, SystmOne version, screen context, created/updated timestamps). |
| FR-REG-02 | M | The registry **shall** version locators so a bot can pin to, or be tested against, a specific SystmOne release. |
| FR-REG-03 | S | The registry **should** flag locators as "unverified" against a new S1 version and support a re-verification workflow. |
| FR-REG-04 | S | Image templates **should** be stored as binary blobs with checksums; large blobs **may** be stored in object storage referenced from PostgreSQL. |

### 3.6 Worker process (queue consumer + result reporter)

A worker process is the runtime that pulls jobs from RabbitMQ, drives SystmOne
via the automation engine, and reports results back to the control plane over
HTTP. Any number of workers may run concurrently against the same queue.

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-WRK-01 | M | A worker **shall** consume job messages from the RabbitMQ work queue. Multiple workers **shall** be able to consume from the same queue simultaneously; the broker distributes each message to exactly one worker. |
| FR-WRK-02 | M | A worker **shall** process **one run at a time per SystmOne session** (an S1 session is a single-user interactive resource) and set a **prefetch/QoS of 1** so an idle worker, not a busy one, receives the next job. |
| FR-WRK-03 | M | On completion of each step and on run termination, a worker **shall POST** an outcome report to the control plane's results endpoint (FR-CP-02), including run id, step index, status, outputs, timings, and — on failure — the **exception type, message, stack trace, resolved locator, and a failure screenshot reference**. |
| FR-WRK-04 | M | A worker **shall not acknowledge (ack)** a job message until the run has reached a terminal state **and** its terminal outcome has been **successfully POSTed** (and accepted) to the control plane. This guarantees no run is lost if a worker dies mid-run — the message is redelivered to another worker. |
| FR-WRK-05 | M | If a POST to the control plane fails transiently, the worker **shall** retry with backoff. If results cannot be delivered after the retry budget, the worker **shall nack** the message to a **dead-letter queue** rather than silently dropping it. |
| FR-WRK-06 | M | A worker **shall** send periodic **heartbeats / progress POSTs** for long-running runs so the control plane can distinguish a slow run from a dead worker and reclaim/redispatch abandoned runs. |
| FR-WRK-07 | S | A worker **should** authenticate its POSTs to the control plane (e.g. OAuth2 client-credentials / mTLS / signed worker token) so only registered workers can report outcomes. |
| FR-WRK-08 | S | A worker **should** register itself and its capabilities (S1 version, screen resolution, enabled adapters) on startup and deregister/timeout on shutdown, feeding control-plane agent health (FR-CP-06). |
| FR-WRK-09 | S | A worker **should** honour an **abort/cancel** signal for an in-flight run — delivered on next heartbeat response or via a per-worker control queue — and report the aborted outcome. |
| FR-WRK-10 | C | Workers **could** be pooled by **capability tag** (queue-per-pool) so jobs needing a specific S1 version or capability are only consumed by matching workers. |

> **Delivery-semantics note.** The combination of *manual ack after confirmed
> POST* (FR-WRK-04) and an *idempotent results endpoint* (FR-CP-02a) gives
> **at-least-once delivery with exactly-once effect**: a worker crash causes
> redelivery and a re-run, but duplicate terminal reports for the same run are
> de-duplicated by the control plane. Bots should therefore be authored to be
> **idempotent / safely re-runnable** (see FR-ENG-09) — important in a clinical
> system where a duplicated action could create a duplicate record.

---

## 4. Developer capture & tooling requirements

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-TOOL-01 | M | Provide an **element inspector** that overlays the live SystmOne window and, on hover/click, shows the JAB accessibility attributes of the element under the cursor. |
| FR-TOOL-02 | M | Provide a **partial-screenshot capture** mode: drag a rectangle over the screen to capture a template, name it, set a match threshold, and save it to the registry. |
| FR-TOOL-03 | S | Allow the developer to generate a suggested locator from an inspected element (best-effort role/name/path) and copy it into a bot definition. |
| FR-TOOL-04 | S | Provide a **locator test** action: given a locator, highlight the resolved element (or matched region) and report resolution time and confidence. |
| FR-TOOL-05 | C | Provide a **recorder** that observes developer interactions and emits a draft bot (steps + locators) for editing. |

---

## 5. Non-functional requirements

### 5.1 Performance & scalability
- NFR-PERF-01 (M): A single JAB locator resolution **shall** complete within
  **≤ 500 ms** under normal conditions (excluding intentional waits).
- NFR-PERF-02 (S): A screen-recognition template match against a single monitor
  **should** complete within **≤ 1 s**.
- NFR-PERF-03 (M): The system **shall** support at least **50 concurrent worker
  processes** consuming from the queue and a queue depth of **10,000** pending
  runs without loss (bounded by RabbitMQ capacity). The results endpoint
  **shall** sustain the aggregate POST rate of all active workers.
- NFR-PERF-04 (M): **Horizontal scale-out** of throughput **shall** be achieved by
  starting additional worker processes against the same queue, with **no code or
  control-plane change** — new workers begin consuming automatically. Likewise,
  stopping workers **shall** reduce throughput gracefully without lost jobs.

### 5.2 Reliability & resilience
- NFR-REL-01 (M): A crash of a single worker **shall not** lose queued or
  in-flight work; because a job is only acked after its terminal outcome is
  confirmed to the control plane (FR-WRK-04), an unacknowledged AMQP message
  **shall** be redelivered to another worker.
- NFR-REL-02 (M): No run **shall** be silently lost: every dispatched run ends in
  a terminal state (succeeded / failed / aborted / timed-out) recorded in
  PostgreSQL.
- NFR-REL-03 (S): Transient failures (element not yet rendered) **should** be
  retried with backoff before a step is failed.
- NFR-REL-04 (M): The engine **shall** default to **fail-safe**: on ambiguity or
  low confidence it stops rather than acting on the wrong element (critical in a
  clinical context).

### 5.3 Observability
- NFR-OBS-01 (M): Every run **shall** emit a structured, correlated log
  (run id, bot id, agent id, step index).
- NFR-OBS-02 (M): On step failure the agent **shall** capture a screenshot and a
  JAB tree snapshot (subject to redaction) attached to the run record.
- NFR-OBS-03 (S): The system **should** expose metrics (Micrometer) for run
  counts, durations, success/failure, locator-fallback rates, and agent health.

### 5.4 Security, safety & compliance

> SystmOne holds patient-identifiable clinical data. Security and information
> governance are first-class requirements, not add-ons.

- NFR-SEC-01 (M): Operator/developer access to the control plane **shall** be
  authenticated via **OAuth2** (per the existing `oauth2-client` starter) and
  authorised by **role** (Developer / Operator / Auditor / Admin — RBAC).
- NFR-SEC-02 (M): SystmOne credentials / smartcard handling **shall** follow TPP
  and organisational policy; automation **shall not** store plaintext clinical
  system passwords. Secrets **shall** be held in a secrets manager, not source
  or the database.
- NFR-SEC-03 (M): Screenshots and JAB tree snapshots may contain patient data
  and **shall** be treated as PID: encrypted at rest, access-controlled, retained
  only per a configurable retention policy, and **redactable/maskable** where
  practical.
- NFR-SEC-04 (M): All AMQP and control-plane/agent traffic **shall** be encrypted
  in transit (TLS).
- NFR-SEC-05 (M): The system **shall** maintain an **immutable audit trail**: who
  ran what bot, when, against which patient/task context (by reference, not by
  copying clinical data), and the outcome.
- NFR-SEC-06 (M): Data **shall** be handled in line with **UK GDPR / DPA 2018**
  and NHS Information Governance (DSPT); a DPIA **shall** be completed before
  production use against live data.
- NFR-SEC-07 (S): The framework **should** support a **non-production / synthetic
  data** mode for developing and testing bots without live PID.

### 5.5 Compatibility & portability
- NFR-CMP-01 (M): Runner agents **shall** run on the Windows versions and Java
  runtime supported by the target SystmOne deployment, with Access Bridge enabled.
- NFR-CMP-02 (S): The framework **should** detect the running SystmOne version
  and warn when a bot's pinned locator version differs.
- NFR-CMP-03 (M): The control plane **shall** run on Java 25 / Spring Boot 4.1
  and PostgreSQL, as per the existing project scaffold.

### 5.6 Maintainability
- NFR-MNT-01 (M): JAB and screen-recognition adapters **shall** sit behind a
  common `Locator`/`Action` abstraction so a bot is agnostic to which strategy
  resolves it.
- NFR-MNT-02 (S): Native (JNA) interop with the Access Bridge **should** be
  isolated in a single module with its own integration tests.
- NFR-MNT-03 (S): Bot definitions **should** be expressible in a declarative form
  (e.g. code-based DSL and/or serialised config) and version-controlled.

---

## 6. Proposed technical architecture (informative)

This section is guidance, not a binding requirement, aligned to the existing
Spring Boot scaffold.

| Concern | Proposed technology |
|---------|--------------------|
| Control plane | Spring Boot 4.1, Java 25, Spring Web MVC |
| Operator UI | Thymeleaf + Spring Security (OAuth2 login) |
| AuthN/Z | OAuth2 client (SSO) for operators; OAuth2 client-credentials / signed token or mTLS for worker→control-plane POSTs; method/URL RBAC |
| Job dispatch (down) | RabbitMQ via `spring-boot-starter-amqp` — competing-consumer work queue + DLQ; per-worker prefetch (QoS) = 1; manual ack after confirmed result POST |
| Result reporting (up) | Workers **POST** outcomes/outputs/exceptions to a Spring Web MVC REST endpoint; idempotent, correlated by run id |
| Worker process | Spring Boot AMQP `@RabbitListener` consumer + HTTP client (e.g. `RestClient`/`WebClient`) reporting back; hosts the automation engine + JAB/screen adapters |
| Persistence | PostgreSQL (runs, step results, exceptions, audit, locator/template registry) |
| JAB adapter | JNA binding to `WindowsAccessBridge-64.dll`, behind a Java facade |
| Screen recognition | OpenCV (e.g. via JavaCV/bytedeco) for template matching; Tesseract (Tess4J) for OCR |
| Input driving | `java.awt.Robot` (mouse/keyboard) + JAB actions |
| Metrics/logging | Micrometer + structured JSON logging |
| Secrets | External secrets manager (e.g. Vault / cloud KMS) |

### 6.1 Key architectural decisions to validate early
1. **Out-of-process JAB access** via `WindowsAccessBridge-64.dll` + JNA — feasibility spike.
2. Whether the **worker** is the same Spring Boot artefact in a "worker" profile
   (AMQP listener + result-POST client) or a separate lightweight desktop-side
   process. Either way it must run in an interactive Windows session.
3. Template storage (DB blob vs object store) and PID redaction pipeline.
4. Session/desktop management on the runner host (interactive session required
   for `Robot` and on-screen matching).

---

## 7. Risks & mitigations

| # | Risk | Impact | Mitigation |
|---|------|--------|-----------|
| R1 | Out-of-process JAB access is harder than in-process; native interop is brittle | High | Early spike with JNA against `WindowsAccessBridge-64.dll`; isolate in one module; fall back to screen recognition |
| R2 | SystmOne UI changes between versions break locators | High | Version-pinned locator registry; re-verification workflow; JAB (attribute-based) preferred over pixel matching |
| R3 | Screen recognition is fragile to DPI/theme/resolution | Medium | Multi-scale matching, masks, anchor-relative locators, confidence thresholds; prefer JAB |
| R4 | Acting on the wrong element in a clinical system | Critical | Fail-safe on ambiguity/low confidence; mandatory verification step; audit trail; synthetic-data testing |
| R5 | Handling patient-identifiable data in logs/screenshots | Critical | Redaction, encryption, retention limits, RBAC, DPIA, IG sign-off |
| R6 | `Robot`/screen matching requires an unlocked interactive session | Medium | Dedicated runner hosts/sessions; session-keep-alive; monitoring |
| R7 | TPP terms / support posture on automating SystmOne | High | Confirm authorisation with TPP and information governance before production |

---

## 8. Acceptance criteria (release 1)

The framework is accepted for release 1 when:

1. A developer can inspect a live SystmOne screen and see JAB attributes for
   elements under the cursor (FR-TOOL-01).
2. A developer can capture a partial screenshot as a named template locator and
   test that it resolves on screen (FR-TOOL-02, FR-SR-02).
3. A bot can be authored with a JAB-first locator and a screen-recognition
   fallback, and executes end-to-end against a **non-production** SystmOne with
   synthetic data (FR-ENG-01/02, FR-JAB, FR-SR).
4. An operator can dispatch that bot from the web UI, and the run is picked up by
   a worker consuming the RabbitMQ queue; the worker **POSTs** step results and
   the terminal outcome (with a failure screenshot and exception detail on error)
   back to the control plane, visible as live status and a full run log
   (FR-CP-01/02, FR-WRK-01/03, NFR-OBS-02).
5. With **two or more workers** running against the same queue, a batch of runs is
   distributed across them (each run handled by exactly one worker), and killing
   a worker mid-run causes that run to be redelivered and completed by another
   worker with no duplicate terminal record (FR-WRK-01/02/04, FR-CP-02a,
   NFR-PERF-04, NFR-REL-01).
6. All runs terminate in a recorded terminal state with an immutable audit entry
   (NFR-REL-02, NFR-SEC-05).
7. Control-plane operator access is OAuth2-authenticated and role-restricted, and
   worker result POSTs are authenticated (NFR-SEC-01, FR-WRK-07).

---

## 9. Open questions

1. Is direct automation of SystmOne **authorised by TPP** and the hosting NHS
   organisation? (Blocking for production.)
2. Will runner agents run on physical clinical workstations, virtual desktops,
   or dedicated automation hosts?
3. How is SystmOne authentication performed under automation (smartcard, service
   account, delegated session)?
4. What is the retention policy for failure screenshots / JAB snapshots that may
   contain PID?
5. Is a code-based bot DSL sufficient for release 1, or is a low-code/visual
   authoring UI required?
6. Which SystmOne modules/workflows are the first automation targets (to prioritise
   locator coverage)?
