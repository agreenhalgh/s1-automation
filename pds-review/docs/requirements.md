# Requirements Specification — PDS Match Review

**Project:** `com.newbrid.epr_automation.pdsreview` (`pds-review/`)
**Document status:** Draft v0.7 — see §9.1 for what changed
**Date:** 2026-08-26
**Owner:** EPR Automation Team
**Parent specification:** [SystmOne RPA Framework](../../docs/requirements.md)

---

## 1. Introduction

### 1.1 Purpose

This document specifies the functional and non-functional requirements for the
**PDS Match Review** application: a clinical worklist and review tool for triage
presentations that the calling application (OPTO Health) **could not match to the
NHS Personal Demographics Service**.

The presentationded, the matched demographics. When it did not, nobody has yet
established **who feed is already understood and modelled — see
[`answer_digital-presentation.schema.json`](../../docs/answer_digital-presentation.schema.json)
and the gateway's `Presentation` record. Every presentation carries the patient's
own answers (name, date of birth, postcode, telephone) and, when the sender's own
PDS lookup succee this patient is**, and no patient can be registered on the EPR
until somebody does.

Today that gap is silent. This application makes it a queue, puts a named
clinician in front of it, gives them the search tools to close it, and records
what they decided and why.

### 1.2 Scope

**In scope**
- Admission of unmatched presentations to a review queue, **POSTed by the
  gateway** once it has validated the submission.
- A web application for human review, authenticated with **NHS CIS2**.
- A worklist prioritised by **triage score** and **waiting time**.
- **Non-fuzzy** PDS search, run automatically from the patient's own answers,
  including historic names and addresses (`_history=true`).
- **GP practice** search against the Organisation Data Service, feeding a
  practice-scoped PDS search.
- **Fuzzy** PDS search with reviewer-selected parameters.
- The three searches offered in a **default order the reviewer can depart from**,
  with the path they actually took recorded.
- Five terminal outcomes in two classes — three that **register** a patient
  (matched; accepted-unmatched, not on PDS; accepted-unmatched, patient
  unavailable to confirm) and two that **close without registering** (already
  registered on the EPR by hand; registration no longer required) — plus return
  to queue.
- Posting every decision — with the presentation and the evidence behind it — to
  a Camel route on the gateway, for onward registration or to be recorded as
  closed.
- An immutable audit trail of every view, search and decision, attributable to a
  named CIS2 identity, readable by an **Auditor** role.

**Out of scope**
- Any write to PDS. This application reads PDS; it never updates a patient's
  demographics, adds a related person, or changes a nomination.
- The registration itself. The decision is posted to the control plane, which
  registers the patient by running a bot against SystmOne; that bot is specified
  elsewhere.
- Changing the presentation feed contract, or asking OPTO Health to match
  differently.
- Clinical triage. The triage score is consumed for ordering; it is never
  recalculated, overridden or acted on clinically here.
- Matching automatically. See NFR-SAFE-01 — no automatic acceptance of any
  candidate, at any score, ever.

### 1.3 Definitions, acronyms & abbreviations

| Term | Meaning |
|------|---------|
| **PDS** | NHS Personal Demographics Service — the national demographics register |
| **ODS** | Organisation Data Service — the national register of NHS organisations, including GP practices |
| **CIS2** | NHS Care Identity Service 2 — national OIDC identity provider for NHS staff (smartcard, Windows Hello, passkey) |
| **National RBAC** | The NHS role-based access control model carried in CIS2 tokens: organisation, role code, activity codes |
| **URID** | Unique Role ID — identifies one of a user's national RBAC roles; supplied to PDS as `NHSD-Session-URID` |
| **Activity code** | A national RBAC permission carried in a CIS2 role. **`B0830`** grants access to this application; **`B0011`** grants access to its audit trail |
| **Prescribing Cost Centre** | The ODS organisation role a GP practice holds (role code **`RO177`**), and therefore how a practice is identified in an ODS search |
| **AAL** | Authenticator Assurance Level — CIS2 AAL2 / AAL3 |
| **Presentation** | One completed triage session pushed by OPTO Health; the payload this application reviews |
| **Unmatched presentation** | A presentation carrying no PDS demographics — see FR-INT-01 |
| **Triage score** | The presentation's overall priority, `P0` (most urgent) to `P5`, in `user.pValue` |
| **Non-fuzzy search** | PDS `GET /Patient` without `_fuzzy-match`; deterministic, requires family name, gender and date of birth |
| **Fuzzy search** | PDS `GET /Patient` with `_fuzzy-match=true`; homophone (Soundex) and transposed-name tolerant |
| **Match score** | PDS `search.score` on a candidate: `1.0` is an exact match, below `1.0` a partial one |
| **Review item** | One queue entry: a presentation, its state, its lock, and its decision once made |
| **Decision** | The terminal outcome a reviewer records against a review item — either **registering** (a patient is registered on the EPR) or **closing** (no registration happens). See FR-DEC-01 |
| **Post-back** | The HTTP POST of a decision to the gateway's Camel route |
| **Control plane** | The Spring Boot service that dispatches and monitors bot runs |
| **Gateway** | The Camel service in front of the control plane (`gateway/`) |
| **NHS.UK frontend** | NHS England's component library (`nhsuk-frontend`) implementing the NHS digital service manual's design system |

### 1.4 References

- PDS FHIR API — `GET /Patient`, `GET /Patient/{id}`:
  <https://digital.nhs.uk/developer/api-catalogue/personal-demographics-service-fhir#get-/Patient>
- ODS FHIR API — `GET /Organization`, `GET /Organization/{odsCode}`:
  <https://digital.nhs.uk/developer/api-catalogue/organisation-data-service-fhir#get-/Organization>
- NHS CIS2 Authentication — guidance for developers:
  <https://digital.nhs.uk/services/care-identity-service/applications-and-services/cis2-authentication/guidance-for-developers>
- NHS.UK frontend library (MIT): <https://github.com/nhsuk/nhsuk-frontend>
- NHS digital service manual — design system and patterns:
  <https://service-manual.nhs.uk/design-system>
- Presentation feed contract: [`answer_digital-presentation.openapi.yaml`](../../docs/answer_digital-presentation.openapi.yaml),
  [`answer_digital-presentation.schema.json`](../../docs/answer_digital-presentation.schema.json)
- Parent specification: [`docs/requirements.md`](../../docs/requirements.md) —
  in particular NFR-SEC-01 (RBAC), NFR-SEC-05 (immutable audit), NFR-REL-04 (fail-safe)
- Gateway: [`gateway/README.md`](../../gateway/README.md)
- Existing scaffold: Spring Boot 4.1.0, Java 25, PostgreSQL

---

## 2. Overall description

### 2.1 Product perspective

```
   OPTO Health ED backend
        │  POST /api/v1/presentation   (mTLS)
        ▼
 ┌──────────────────────┐
 │   Gateway (Camel)     │  authenticate · audit · validate · store PDF
 │                       │
 │  matched ─────────────┼──────────────────────────────▶ control plane: register + run bots
 │  unmatched ───────────┼──┐
 └──────────────────────┘  │  POST the presentation (FR-INT-02a)
                            ▼
        ┌────────────────────────────────────────────────────────┐
        │            PDS Match Review (this application)         │
        │                                                        │
        │  Worklist ── prioritised by triage score + wait time   │
        │  Review    ── non-fuzzy ▸ GP practice ▸ fuzzy (default)│
        │  Decision  ── register: matched │ not-on-PDS │ unavail │
        │            ── close: registered by hand │ not required │
        │  Audit     ── every view, search and decision          │
        │                                                        │
        │  PostgreSQL: queue · review state · audit trail        │
        └───┬──────────────┬───────────────┬──────────────┬──────┘
            │              │               │              │
      CIS2  │        PDS   │         ODS   │   post-back  │ POST decision + presentation
      OIDC  │      FHIR R4 │       FHIR R4 │              ▼
            ▼              ▼               ▼      ┌──────────────────────┐
     ┌────────────┐  ┌───────────┐  ┌───────────┐ │  Gateway Camel route │
     │ NHS CIS2   │  │  PDS      │  │  ODS      │ │  /gateway/v1/…       │
     │ (reviewer  │  │ GET       │  │ GET       │ └──────────┬───────────┘
     │  identity) │  │ /Patient  │  │ /Organi…  │            │ dispatch
     └────────────┘  └───────────┘  └───────────┘            ▼
                                                     Control plane → SystmOne
```

Three things about this shape are deliberate.

**The queue sits downstream of validation, and the gateway pushes into it.** A
presentation that fails the gateway's validation is not a patient who needs
identifying; it is a bad request, already rejected with a 422 and an audit row.
Only submissions that are structurally sound and clinically complete reach a
human (FR-INT-03). Those arrive as a **POST from the gateway** (FR-INT-02a) —
chosen over polling because the ordering key here is urgency, and a P0 patient
should be on a reviewer's screen the moment their triage lands, not at the next
poll interval. The cost of that choice is that the gateway now depends on this
service being reachable, which FR-INT-02b is written to contain.

**Nothing here talks to the control plane directly.** The post-back goes to a
Camel route on the gateway, as the brief requires and as every other integration
does. That keeps one audited, authenticated front door to the automation, and it
means a decision made here is dispatched by exactly the same machinery as a
presentation that never needed reviewing.

**This application holds patient-identifiable data, and the gateway does not.**
The gateway pseudonymises the NHS number into its audit trail on purpose. That
option is not open here: the entire job is to show a clinician a patient's name,
date of birth and address next to candidate matches, and to record which one they
chose. Consequently the controls are different in kind, not degree — §5.4.

### 2.2 Why presentations arrive unmatched

Two distinct upstream conditions produce an unmatched presentation, and they are
not the same problem:

| `user.nhs.status` | Meaning | Reviewer implication |
|---|---|---|
| `unsuccessful` | PDS was asked and matched nobody | The patient's own details may be wrong, incomplete, or genuinely absent from PDS |
| `unavailable` | PDS was never reachable, or never asked | Nothing has been established either way; a search here may well match first time |

A reviewer needs to know which they are looking at before they start, because it
changes what a nil return from their own search means (FR-REV-03). Conflating them
would make "not on PDS" — one of the two accept-unmatched decisions — a guess.

> **Sizing note.** In all eight captured sample payloads, `nhsUser` is null and
> `user.nhs` is absent from the JSON entirely. On that evidence *every*
> presentation would be admitted to this queue. Whether that reflects the sender's
> production behaviour or only the capture environment is Open Question 2, and it
> is the single biggest driver of the volume this application must absorb.

### 2.3 User classes

| User class | Description | Primary needs |
|------------|-------------|---------------|
| **Reviewer** (clinical / administrative) | Works the queue: opens an item, searches PDS, records a decision | Fast worklist, searches pre-filled from the patient's answers, unambiguous candidate comparison, a defensible decision record |
| **Auditor / Governance** | Reviews who looked at which patient, what they searched, and what they decided | Immutable, searchable, attributable trail; no ability to alter or decide |
| **Service Manager** | Watches queue health | Queue depth, oldest waiting item, decision throughput, PDS error rates |
| **System Administrator** | Manages CIS2 client registration, API credentials, connectivity | Config, secrets, health, no access to patient data |

Reviewer and Auditor are **mutually exclusive in effect**: the role that reviews
what was decided does not also decide. This is the same separation the control
plane already draws — its audit viewer is `AUDITOR`-only and dispatching is closed
to `AUDITOR` — and FR-AUD-05 restates it here.

### 2.4 Assumptions & dependencies

- The organisation holds a **CIS2 client registration** for this application, in
  each environment, with the assurance level agreed (Open Question 1).
- Reviewers hold a national RBAC role carrying activity **`B0830`**, in an
  organisation with an ODS code; auditors hold **`B0011`** (§3.2, §3.9).
- The organisation is onboarded to **PDS FHIR** in each environment, and its
  rate limits are known (Open Question 4). **ODS FHIR is unauthenticated**, so it
  needs no credential, no onboarding and no per-environment secret.
- The **gateway gains two routes**, neither of which exists today, and both are
  changes to `gateway/` that this application depends on:
  **(a)** an outbound POST of each unmatched presentation to this application
  (§4.1), and **(b)** an inbound route that receives review decisions (§4.5).
- The **control plane can register a patient** from a decision payload — i.e. a
  bot exists for it, and it reports back an MRN or a failure (Open Question 3).
- PostgreSQL is available and this application's schema is separate from the
  control plane's.

---

## 3. Functional requirements

Requirements use **MoSCoW** priority: **M**ust / **S**hould / **C**ould / **W**on't (this release).

### 3.1 Intake & queue admission

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-INT-01 | M | A presentation **shall** be treated as **unmatched** when it carries no PDS demographics — that is, `externalValue.nhsUser` is absent or null, **or** `externalValue.user.nhs.status` is any value other than `successful`. The rule **shall** be expressed in one place and recorded on the review item, so why an item is in the queue is never inferred. |
| FR-INT-02 | M | An unmatched presentation **shall** be admitted to the review queue exactly once, keyed on a stable identity derived from the submission (see FR-INT-05), and **shall not** be dispatched for patient registration until a decision is recorded. |
| FR-INT-02a | M | Presentations **shall** reach this application as an **HTTP POST from the gateway** to an authenticated intake endpoint. This application **shall not** poll the gateway, and **shall not** read the gateway's database. |
| FR-INT-02b | M | The intake endpoint **shall** answer only once the review item is durably committed, and **shall** answer distinguishably for *accepted*, *already held* (FR-INT-05) and *rejected*, so the gateway knows whether to retry. A presentation the gateway cannot deliver **shall** remain the gateway's responsibility to retry — an unmatched presentation **shall not** be lost because this application was restarting, and the sender's own submission **shall not** be failed on that account. |
| FR-INT-03 | M | Admission **shall** occur only **after** the gateway has validated the submission. A submission rejected as malformed or invalid **shall not** create a review item. |
| FR-INT-04 | M | The review item **shall** store the presentation **as the JSON it arrived as**, verbatim, and bind only the fields review needs (§4.1). Re-serialising it through a typed model here would make an upstream model change break the queue, and the post-back must be able to return the presentation unaltered. |
| FR-INT-05 | M | Admission **shall** be **idempotent** on redelivery: a repeated submission of the same presentation **shall not** create a second review item, and **shall not** resurrect one already decided. |
| FR-INT-06 | M | The review item **shall** record, on admission: receipt timestamp, `user.endTimestamp` (triage completion), triage score `user.pValue`, the unmatched reason of FR-INT-01, hospital (`user.hospitalId`), and routing department (`routing.department`). These are the fields the worklist orders and filters on, and copying them out once avoids re-parsing a 60 KB payload per row. |
| FR-INT-07 | S | Where a presentation arrives whose patient details match an item **already decided**, the queue **should** flag the new item as a probable repeat presentation and show the earlier decision to the reviewer. |
| FR-INT-08 | C | Intake **could** accept a manual re-admission: an item decided in error is re-queued by an administrator, with a reason, as a new item that references the original. |

> **Design note — how items arrive.** Settled: the gateway POSTs (FR-INT-02a). A
> push puts the item in front of a clinician immediately, which is the point when
> the queue is ordered by urgency — a poll interval is dead time added to a P0
> patient's wait.
>
> What that buys has to be paid for on the gateway side. The gateway's answer to
> OPTO Health must not depend on this application being up: the presentation has
> already been validated, audited and stored by then, and failing the sender's
> submission because a downstream worklist is restarting would turn our outage
> into their retry problem — and, since the sender's own PDS lookup already
> failed, into a patient waiting in an ED with nobody holding their record. So
> the gateway retries the intake POST from its own durable store, and
> FR-INT-05's idempotency here is what makes that retry safe. The mirror image of
> the decision post-back (FR-DEC-07), in the opposite direction.

### 3.2 Authentication & authorisation (NHS CIS2)

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-AUTH-01 | M | Reviewer access **shall** be authenticated by **NHS CIS2** using the OpenID Connect **authorization-code flow**. No local username/password authentication **shall** exist in any profile that can reach live PDS or live patient data. |
| FR-AUTH-02 | M | The application **shall** authenticate itself to CIS2 by the method agreed at registration — **`private_key_jwt`** preferred over a client secret — with signing keys held in a secrets manager and published via JWKS, never in source or the database. |
| FR-AUTH-03 | M | The application **shall** require the assurance level agreed for the service (**AAL2 or AAL3**) and **shall** reject a session authenticated below it rather than degrading to a read-only mode. |
| FR-AUTH-04 | M | The application **shall** read the user's **national RBAC roles** from the CIS2 token, and where the user holds more than one, **shall** require them to **select the role** they are working under before opening any review item. |
| FR-AUTH-05 | M | The **URID of the selected role shall** be sent as `NHSD-Session-URID` on every PDS request, and the selected role **shall** be recorded on every audit entry for the session. A search made under one role must not be attributable to another. |
| FR-AUTH-06 | M | The application **shall** verify that the selected role carries activity **`B0830`**, and **shall** refuse access — with a plain explanation of what is missing and who can grant it — where it does not. It **shall not** admit the user and let the first PDS call fail, surfacing a 403 as a system error. |
| FR-AUTH-07 | M | Application capability **shall** be derived from the activity codes on the **selected role**, not held locally: **`B0830`** grants **REVIEWER** (work the queue), **`B0011`** grants **AUDITOR** (read the trail). Both codes **shall** be configuration, so a national RBAC change is a config change reviewed by information governance rather than a release. |
| FR-AUTH-07a | M | Capability **shall** follow the **selected role only**, never the union of the user's roles. A user whose roles carry both `B0830` and `B0011` **shall** exercise one at a time, and **shall** re-select their role to switch — which is what keeps FR-AUD-05's separation real for a person who holds both, rather than only for people who hold one. |
| FR-AUTH-08 | M | Sessions **shall** expire after a short configurable idle period (default **15 minutes**) and on CIS2 session termination, because a logged-in unattended session in a clinical area is an open patient record. |
| FR-AUTH-09 | S | The application **should** support CIS2 **single logout**, so ending a smartcard session ends this one. |
| FR-AUTH-10 | S | A reviewer's **organisation ODS code** from the token **should** be sent on PDS/ODS calls where the API requires an end-user organisation, and recorded on the audit entry. |

### 3.3 The worklist

| ID       | Priority | Requirement                                                                                                                                                                                                                                                                                                                   |
|----------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| FR-WL-01 | M        | The application **shall** show the list of presentations awaiting review, one row per item, with: patient's stated name, stated date of birth, triage score, time waiting, hospital, routing department, unmatched reason, and lock state.                                                                                    |
| FR-WL-02 | M        | The list **shall** be ordered by **triage score first, then waiting time** — most urgent first, and oldest first within a score. Both keys are shown on the row so the order is self-evidencing.                                                                                                                              |
| FR-WL-03 | M        | **Waiting time shall** be measured from `user.endTimestamp` (the moment the patient finished triage) where present, falling back to receipt time, and the row **shall** show which basis was used. A patient who completed triage forty minutes ago has been waiting forty minutes regardless of when the payload reached us. |
| FR-WL-04 | M        | An item with **no triage score** (`user.pValue` absent or null) **shall** be shown in a distinct band **at the top** of the list, ordered by waiting time, and never sorted to the bottom. A missing score is an absence of evidence, not evidence of low urgency (NFR-SAFE-02).                                              |
| FR-WL-05 | M        | The ordering rule, including the treatment of a missing score and the `P0`…`P5` ranking, **shall** be configuration with the defaults above, so a change to it is a reviewed change and not a code edit.                                                                                                                      |
| FR-WL-06 | M        | The list **shall** exclude items already decided, and items currently locked by another reviewer **shall** be shown but not openable (FR-REV-01).                                                                                                                                                                             |
| FR-WL-07 | S        | The list **should** be filterable by hospital, routing department, triage score, unmatched reason and waiting-time threshold, and **should** support paging with a stable order.                                                                                                                                              |
| FR-WL-08 | S        | The list **should** show an ageing indicator against a configurable threshold per triage score, so an item about to breach is visible without arithmetic.                                                                                                                                                                     |
| FR-WL-09 | S        | The list **should** refresh without a manual reload, at a configurable interval, so a reviewer watching an empty queue sees an urgent arrival. The same polled fragment that satisfies FR-WL-11 satisfies this.                                                                                                                                                                                |
| FR-WL-10 | M        | Opening the worklist **shall** be audited (FR-AUD-01), including the filter and ordering in force. What a reviewer was shown is part of what they decided from.                                                                                                                                                               |
| FR-WL-11 | M        | The worklist **shall** update to add and remove items changed by other reviewers, and to show an item newly taken for review, without the reviewer acting or reloading. Satisfied by polling a fragment of the list on the interval of FR-WL-09 — the two are one mechanism, not two. |
| FR-WL-11a | M        | A background refresh that changes nothing **shall** leave the page untouched and **shall not** be audited. Recording a row every few seconds per reviewer would bury the disclosures the trail exists to answer for (FR-AUD-06); a refresh that discloses nothing new is not a disclosure. A refresh that *does* change the list **shall** be audited as such, distinguishably from opening the worklist (FR-WL-10). |
| FR-WL-11b | M        | Background refreshing **shall not** keep an unattended session alive. Polling is request activity, so without this a worklist left open would never reach the idle timeout of FR-AUTH-08 — which exists because a signed-in session in a clinical area is an open patient record. Refreshing **shall** stop after a configurable period with no interaction from the reviewer, and resume when they return. |

### 3.4 The review session

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-REV-01 | M | Opening an item **shall** take an **exclusive lock** on it for the reviewer. Two reviewers **shall not** hold the same item, because two people matching the same patient independently is how one patient becomes two records. |
| FR-REV-02 | M | A lock **shall** expire after a configurable idle period and return the item to the queue, so an abandoned browser tab does not strand a patient. Expiry **shall** be audited. |
| FR-REV-03 | M | The review screen **shall** show the patient's own answers as submitted — from `user.data[]` — alongside the triage score, unmatched reason (§2.2), hospital, routing and the triage timestamps. It **shall** be visibly clear that these are **patient-stated, unverified** details. |
| FR-REV-04 | M | The review screen **shall** allow the reviewer to **return the item to the queue** without deciding, recording a **reason**, releasing the lock, and preserving the item's position under the ordering of FR-WL-02 — the patient's urgency is not reduced by having been looked at. |
| FR-REV-05 | M | An item returned to the queue **shall** retain its full history: every prior review session, search and reason, visible to the next reviewer, so the next person does not repeat the last person's work. |
| FR-REV-07 | S | The review screen **should** show the clinical context a reviewer needs to judge urgency while they work — presenting complaint (`clinicalPath.disease`) and triage score — without exposing the full pathway answer set. |
| FR-REV-08 | M | Every view of a review item, and every panel within it that discloses patient data (including the PDF), **shall** be audited against the user (FR-AUD-01). |
| FR-REV-09 | M | The review screen **shall** offer the three searches in a **default order** — non-fuzzy (§3.5), then GP practice (§3.6), then fuzzy (§3.7) — and **shall not enforce it**. A reviewer **shall** be able to go straight to any search, skip one, or return to an earlier one, at any point before they decide. The order is a sensible starting path, not a gate: it runs cheapest-and-most-certain first, but a reviewer who can already see that the patient's name was transposed should not have to walk through two searches they know will return nothing. |
| FR-REV-10 | M | Because the path is the reviewer's to choose, the path **shall** be recorded: every search in a review session is audited in the order it was run (FR-AUD-02), and the decision carries a reference to the searches that led to it (FR-DEC-05). What was done, and in what order, is evidence rather than an assumption that can be read off the workflow. |

### 3.5 Non-fuzzy PDS search

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-PDS-01 | M | On opening an item, the application **shall** run a **non-fuzzy** PDS `GET /Patient` search built from the patient's submitted answers, without the reviewer having to type them again. |
| FR-PDS-02 | M | That search **shall** include **`_history=true`**, so previous names and addresses are searched. A patient who has married, or moved, is exactly the patient whose current details fail to match. |
| FR-PDS-03 | M | Search parameters **shall** be derived from `user.data[]` by a **configured mapping**, preferring the stable `key` where the sender populates one and falling back to the `question` text. The mapping **shall** be data, not code, and **shall** be versioned. |
| FR-PDS-04 | M | Where the mapping cannot produce the parameters PDS requires for a non-fuzzy search — **`family`**, **`gender`** and **`birthdate`** — the application **shall** say plainly which fields it could not derive, **shall not** issue a partial search, and **shall** offer the reviewer the manual and fuzzy routes instead. Silently searching on two of three fields would present a candidate list that answers a different question. |
| FR-PDS-05 | M | The application **shall** send `birthdate` using PDS's prefixed form (`eq`/`ge`/`le` with `yyyy-mm-dd`). |
| FR-PDS-05a | M | The patient's postcode **shall be normalised** before it is sent as `address-postcode`: upper-cased, all whitespace and punctuation removed, then a **single space re-inserted before the final three characters**. PDS treats postcode whitespace as significant — `LS16AE` does not match `LS1 6AE` — so a patient's free-text entry passed through as typed fails to match a record that is otherwise identical. Normalisation is what makes that significance harmless. It **shall** apply wherever the parameter is sent: the automatic search, a reviewer-edited value, and the fuzzy search (FR-FUZ-02). |
| FR-PDS-05b | M | Where the submitted value **cannot** be normalised to a valid UK postcode — including an outward code alone (`LS1`), a partial entry, or free text — the application **shall not** send a guess. It **shall** show the value as submitted, mark it unusable as a search parameter, and leave correcting it to the reviewer (NFR-SAFE-02). A postcode invented by padding is a search against a different address. |
| FR-PDS-06 | M | Candidates **shall** be listed with their PDS **`score`**, in descending score order as PDS returns them, and the score's meaning (`1.0` = exact) **shall** be shown, not assumed known. |
| FR-PDS-07 | M | Each candidate **shall** be displayed field-by-field **against** the patient's submitted details, with differing fields marked, so the reviewer compares rather than reads two lists. |
| FR-PDS-08 | M | The application **shall not** send `_exact-match` by default. Restricting results to `score = 1.0` would hide precisely the near-misses a human is here to adjudicate. |
| FR-PDS-09 | M | The application **shall** handle PDS's **`TOO_MANY_MATCHES`** condition — returned instead of results when the match count exceeds the maximum — by telling the reviewer the search was too broad and offering to narrow it, not by reporting "no matches". |
| FR-PDS-10 | M | The reviewer **shall** be able to re-run the non-fuzzy search with **edited parameters**, and each execution **shall** be a separately audited search (FR-AUD-02). |
| FR-PDS-11 | S | The application **should** allow retrieval of a single candidate's full record (`GET /Patient/{nhsNumber}`) before deciding, and **should** record its `versionId`/`ETag` with the decision, so the decision names the exact version of the record it was made against. |
| FR-PDS-12 | M | A PDS error or timeout **shall** be reported as such, distinctly from "no candidates found". These lead to opposite decisions, and conflating them could produce an "not on PDS" acceptance for a patient PDS was never asked about. |

### 3.6 GP practice search (ODS)

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-ODS-01 | M | The reviewer **shall** be able to search PDS **scoped to a registered GP practice**, using PDS's **`general-practitioner`** parameter with the practice's ODS code. Offered by default once the non-fuzzy candidates are on screen, but not conditional on having reviewed them (FR-REV-09). |
| FR-ODS-02 | M | The application **shall** provide a **GP practice lookup** against ODS **`GET /Organization`** so the reviewer finds the practice by name or place rather than knowing its ODS code. |
| FR-ODS-03 | M | The lookup **shall** support searching by name (**`name`** for a leading match, **`name:contains`** for any part) and by **`address-postalcode`**, and **shall** page results using **`_count`** and **`_offset`**. |
| FR-ODS-04 | M | The lookup **shall** restrict results to organisations holding the **Prescribing Cost Centre** role — role code **`RO177`** in the ODS role CodeSystem — which is the role a GP practice holds. It **shall** be requested via **`activeRoleCode`**, so a practice whose role has been retired does not surface as a current option. The code **shall** remain configuration: the binding decision is *Prescribing Cost Centre*, and the number is how ODS spells it. |
| FR-ODS-05 | S | The lookup **should** default to **`active=true`** while allowing inactive practices to be found deliberately, because a patient may name a practice that has since closed or merged. |
| FR-ODS-06 | S | Where the patient's submitted answers name a GP practice, the application **should** pre-fill the lookup from them. |
| FR-ODS-07 | M | A practice-scoped PDS search **shall** still satisfy PDS's mandatory non-fuzzy combination (FR-PDS-04): `general-practitioner` narrows a search, it does not replace the required fields. |
| FR-ODS-08 | M | Both the ODS lookup and the resulting practice-scoped PDS search **shall** be audited as searches in their own right (FR-AUD-02). |
| FR-ODS-09 | S | Practice reference data **should** be cached for a configurable period to spare ODS repeated identical lookups, with the cache never serving a result older than the configured maximum. |
| FR-ODS-10 | M | ODS FHIR is **unauthenticated**: the application **shall** hold no ODS credential and **shall not** invent one. It **shall** still bound the calls with its own timeouts, client-side throttle and cache (FR-ODS-09), because an API that asks nothing of us also promises nothing to us. |
| FR-ODS-11 | M | Because the endpoint is unauthenticated, **only organisation search terms shall** be sent to ODS — practice name, town, postcode fragment. Patient name, date of birth and NHS number **shall never** appear in an ODS request. Where the reviewer searches for practices near the patient's home, **only the outward code** of the normalised postcode (`LS1` of `LS1 6AE`) **shall** be sent, which is all ODS's leading-match semantics need, and the narrowest thing that answers the question. |

### 3.7 Fuzzy PDS search

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-FUZ-01 | M | The reviewer **shall** be able to run a **fuzzy** PDS search (**`_fuzzy-match=true`**), which additionally matches homophones (Soundex) and transposed names. |
| FR-FUZ-02 | M | The reviewer **shall** be able to **choose the search parameters** and edit their values, pre-filled from the patient's submitted answers. Edited values **shall** be normalised on the same rules as the automatic search — including the postcode (FR-PDS-05a) — so a reviewer's typing is not held to a stricter standard than the patient's. |
| FR-FUZ-03 | M | The application **shall** enforce, before calling PDS, that the chosen parameters satisfy one of the combinations PDS accepts for a fuzzy search: `given` + `family` + `birthdate`; **or** `family` + `birthdate` + `gender` + `address-postcode`; **or** `given` + `birthdate` + `gender` + `address-postcode`. An unsatisfied combination **shall** be shown as a form validation error, not as a PDS error. |
| FR-FUZ-04 | M | The application **shall not** offer `death-date` or `general-practitioner` as fuzzy parameters, and **shall** state why where a reviewer might expect them: PDS ignores both in a fuzzy search, so offering them would imply a narrowing that does not happen. |
| FR-FUZ-05 | M | Fuzzy candidates **shall** be presented with the same score display and side-by-side comparison as non-fuzzy ones (FR-PDS-06, FR-PDS-07), and **shall** be labelled as fuzzy so a reviewer never mistakes a homophone match for an exact one. |
| FR-FUZ-06 | S | The application **should** support bounded `birthdate` ranges (`ge`/`le`) for the common case of an uncertain year of birth. |
| FR-FUZ-07 | M | Every fuzzy execution **shall** be a separately audited search recording the exact parameters sent (FR-AUD-02). |
| FR-FUZ-08 | C | The application **could** suggest a next fuzzy search after a nil return (for example, swapping given and family names) as a prompt, never as an automatic re-query. |

### 3.8 Decisions & post-back

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-DEC-01 | M | The reviewer **shall** be able to record exactly one terminal decision, drawn from two classes that differ in what happens next.<br><br>**Registering** — a patient is registered on the EPR:<br>**`MATCHED`** — a PDS candidate is this patient;<br>**`ACCEPTED_NOT_ON_PDS`** — accept the submitted details as given, because the patient is not registered on PDS;<br>**`ACCEPTED_PATIENT_UNAVAILABLE`** — accept the submitted details as given, because the patient is not available to confirm them.<br><br>**Closing** — no patient is registered:<br>**`CLOSED_REGISTERED_MANUALLY`** — registration is not necessary, because the patient has already been registered on the EPR by hand;<br>**`CLOSED_NO_LONGER_REQUIRED`** — registration is not necessary, because the patient no longer requires registration. |
| FR-DEC-01a | M | A **closing** decision **shall not** cause a patient registration, and the class **shall** be readable from the reason code alone — which is what the `ACCEPTED_` and `CLOSED_` prefixes are for. Neither this application, nor the gateway route, nor anyone reading the audit trail two years later should have to infer from surrounding context whether a registration was intended. |
| FR-DEC-01b | M | For **`CLOSED_REGISTERED_MANUALLY`**, the application **shall** prompt for the identifier of the record the patient was registered on (MRN, or a local identifier) and **shall** record it where the reviewer has it. An assertion that a patient is already on the EPR is only checkable if it says where. |
| FR-DEC-01c | M | Every **closing** decision **shall** require a free-text note, and the application **shall not** accept one without it. "No longer requires registration" with no reason is a closure nobody can review afterwards — the patient left, they were seen elsewhere, they died, or it was a duplicate presentation, and those are not the same event. |
| FR-DEC-01d | M | An **`ACCEPTED_`** decision **shall** record what PDS was actually asked. Where no search could be run at all — the required parameters were underivable (FR-PDS-04), or PDS was unavailable (FR-PDS-12) — the decision **shall** say so explicitly, and **shall not** be left looking like a search that returned nothing. With the search order no longer enforced (FR-REV-09), "the reviewer must have searched" cannot be inferred from their having reached this screen; it has to be recorded. |
| FR-DEC-02 | M | Decisions within a class **shall** be distinct values, stored and posted back distinctly, never collapsed. The two accept-unmatched decisions are different clinical claims — the first asserts something about PDS, the second about the patient's availability, and only the first should ever be read as "this person has no NHS record". The two closures are different events: one says a record exists, the other that none is needed. |
| FR-DEC-03 | M | Recording a decision **shall** require explicit confirmation on a screen that restates what is about to happen — which patient, which candidate (if any), and **whether the result is a patient registration on the EPR or a closure with no registration**. The consequence, not the label, is what the reviewer must confirm. |
| FR-DEC-04 | M | For **`MATCHED`**, the matched patient's PDS details — **including the NHS number** — **shall** be added to the presentation information, and the whole thing posted back. The presentation **shall** be carried through unaltered apart from that addition. |
| FR-DEC-05 | M | Every terminal decision — registering **or** closing — **shall** be posted to a **Camel route on the gateway** (§4.5), carrying: the presentation, the decision and its reason code, the matched patient (where there is one), the manual-registration identifier and closure note (where there are any), the reviewer's identity and selected role, timestamps, and a reference to the searches that led to it. |
| FR-DEC-05a | M | A **closing** decision **shall** be posted as an outcome the route **records and does not dispatch**. Closures are reported rather than kept local because the gateway holds the presentation and its audit trail, and a presentation left looking unresolved there is how the same patient comes to be registered again later by somebody tidying up. Correspondingly, the route **shall** reject a reason code it does not recognise rather than treating it as a registration — the safe default for an unknown outcome is to do nothing and say so. |
| FR-DEC-06 | M | The post-back **shall** be **idempotent**, keyed on the review item, so a retry cannot register the same patient twice. A duplicate registration in an EPR is a clinical safety incident, not a data-quality one. |
| FR-DEC-07 | M | A decision **shall** be durably recorded **before** the post-back is attempted, and retried until acknowledged (transactional outbox). No decision **shall** be lost because the gateway was briefly unavailable, and no post-back **shall** be attempted that is not first written down. |
| FR-DEC-08 | M | Post-back state **shall** be visible: pending, acknowledged, or failed-after-retries, with failures surfaced to a service manager rather than only logged. A registering decision that was never delivered is worse than one never made, because it looks finished from here. |
| FR-DEC-09 | M | Once a terminal decision is recorded, the item **shall** leave the worklist and **shall not** be re-decided. Correcting a decision is FR-INT-08's re-admission, which leaves both records standing. |
| FR-DEC-10 | S | Where the control plane returns an identifier for the registered patient (an MRN) following a registering decision, it **should** be recorded against the review item, so the trail runs from unmatched presentation to EPR record — as the manually-supplied identifier of FR-DEC-01b does for a closure. |
| FR-DEC-11 | M | A decision **shall** be attributable to one authenticated CIS2 identity and one selected role. The application **shall not** record a decision against a service account, a shared login, or a session whose role selection is unknown. |

### 3.9 Audit

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-AUD-01 | M | Every **view** of patient data **shall** be audited: the worklist (with filters and ordering), each review item opened, each disclosing panel within it, and each candidate record retrieved. |
| FR-AUD-02 | M | Every **search shall** be audited with: which API and endpoint, the **exact parameters sent** (including `_history`, `_fuzzy-match`, `general-practitioner`), the number of candidates returned, their scores, the outcome (results / none / too-many / error), and the elapsed time. Where a value was transformed on the way out — the normalised postcode of FR-PDS-05a — **both** the submitted and the sent value **shall** be recorded, so a nil return can be told apart from a normalisation that changed the question. |
| FR-AUD-03 | M | Every **decision shall** be audited with the decision, its reason code, the matched patient's NHS number where there is one, the confirmation, and the post-back outcome. |
| FR-AUD-04 | M | Every audit entry **shall** carry: timestamp, the reviewer's CIS2 identity, the **selected role (URID)** and organisation, the review item, and a correlation id shared with the presentation's gateway audit rows — so one patient's journey can be followed across both services. |
| FR-AUD-05 | M | The audit trail **shall** be **append-only**: no update, no delete, through any application path. It **shall** be readable only under a selected role carrying activity **`B0011`**, and that role **shall not** be able to open a review item or record a decision — nor `B0830` to read the trail (FR-AUTH-07a). |
| FR-AUD-06 | M | The audit view **shall** be searchable by reviewer, patient (by NHS number where known, and by review item), date range, event type and decision. An audit trail that cannot answer "who looked at this patient?" is a log file, not an audit trail. |
| FR-AUD-07 | M | **Reading the audit trail shall itself be audited.** The audit view discloses which patients were reviewed and by whom, which is precisely the disclosure the trail exists to record. |
| FR-AUD-08 | M | Audit writing **shall** be part of the same transaction as the action it records, so an action cannot succeed unaudited. Where that is impossible (an outbound search), the entry **shall** be written before the call and completed after it, leaving an attempt recorded even if the call never returns. |
| FR-AUD-09 | S | The audit trail **should** be exportable (CSV/JSON) for an information-governance investigation, and the export **should** itself be audited. |
| FR-AUD-10 | S | Retention **should** be configurable per NHS records-management policy, and expiry **should** be a recorded event rather than a silent deletion. |

### 3.10 User interface

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-UI-01 | M | The reviewer interface **shall** be built with the **NHS.UK frontend** library (`nhsuk-frontend`, NHS England, MIT-licensed) and the patterns of the NHS digital service manual, rather than bespoke markup and stylesheets. Reviewers move between this and other NHS systems during a shift; a worklist that looks and behaves like the rest of the estate is one less thing to learn under time pressure, and its components already carry the keyboard and assistive-technology behaviour we would otherwise have to build and test ourselves. |
| FR-UI-02 | M | Library components **shall** be used as published, not restyled into something else. Where a screen needs what the library does not provide — the side-by-side candidate comparison of FR-PDS-07 being the obvious case — it **shall** be composed from existing components and design-system styles; anything genuinely new **shall** be documented as a local extension with its accessibility testing recorded against it. |
| FR-UI-03 | M | Library CSS, JavaScript, fonts and images **shall be self-hosted** by this application and **shall not** be loaded from a public CDN. A clinical worklist must not fail to render because an NHS network blocked egress, and nobody outside this deployment should be able to change what a clinician sees. |
| FR-UI-04 | M | The library version **shall** be pinned in a committed lock file and installed with `npm ci`, which fails rather than resolving something newer. Upgrades **shall** be taken deliberately, against the published release notes and any migration guide, never floated. A component's markup and classes can change between major versions — v9 to v10 needed a migration guide — and a silent bump would change screens nobody reviewed. |
| FR-UI-05 | M | The header, footer and service name **shall** be configured for an **internal, staff-facing clinical tool**. This is not a public nhs.uk service and **shall not** present itself as one. NHS logo and brand usage **shall** be confirmed with the organisation's brand owner before release (Open Question 8). |
| FR-UI-06 | M | Validation **shall** use the design system's error patterns — an error summary at the top of the page, linked to the fields at fault, and each field marked. This is what makes FR-FUZ-03's refused parameter combination and FR-PDS-04's underivable fields legible as *things to fix* rather than as a failure the reviewer must interpret. |
| FR-UI-07 | S | The interface **should** follow the service manual's progressive-enhancement stance: the worklist and every decision path **should** work without client-side JavaScript, so a locked-down browser cannot stand between a patient and being reviewed. |

---

## 4. Interfaces

### 4.1 Inbound — the presentation POST from the gateway

The gateway POSTs each unmatched presentation to an intake endpoint on this
application (FR-INT-02a). The endpoint is authenticated as a service-to-service
call — this is the one route into the application that is not a CIS2 reviewer
session, and it carries no user identity because no user is involved yet.

It is **idempotent on the review item key** (FR-INT-05) and answers
distinguishably for accepted, already-held and rejected, so the gateway can retry
safely and knows when not to (FR-INT-02b). Retry is the gateway's job: it holds
the validated presentation, and this application being unavailable must not
become OPTO Health's problem.

The review item binds only what §3 needs; everything else stays in the stored
JSON (FR-INT-04). The fields that matter, by their wire names:

| Presentation field | Used for |
|---|---|
| `externalValue.nhsUser`, `externalValue.user.nhs.status` | Unmatched determination and reason (FR-INT-01, §2.2) |
| `externalValue.user._id` | Session identity; part of the item key (FR-INT-05) |
| `externalValue.user.pValue` | Triage score — worklist ordering (FR-WL-02) |
| `externalValue.user.endTimestamp` | Waiting time (FR-WL-03) |
| `externalValue.user.data[]` | Patient's stated details → PDS search parameters (FR-PDS-03) |
| `externalValue.user.hospitalId` | Worklist column and filter |
| `externalValue.routing.department` | Worklist column and filter |
| `externalValue.clinicalPath.disease` | Presenting complaint on the review screen (FR-REV-07) |

### 4.2 `user.data[]` → PDS parameters

This mapping is the most fragile thing in the application, and the reason
FR-PDS-03 makes it versioned configuration rather than code. In the captured
samples, `key` is populated for exactly one field (`dob`) and empty for the other
twenty-one; the only other handle is `question`, which is free text set by the
sender's form configuration.

| Patient answer (`key` / `question`) | PDS parameter | Notes |
|---|---|---|
| `dob` / "Date of Birth" | `birthdate` | Submitted as `yyyy-mm-dd`; sent with an `eq` prefix (FR-PDS-05) |
| — / "First Name" | `given` | |
| — / "Last Name", "Surname" | `family` | **Required** for non-fuzzy |
| — / "Sex", "Gender" | `gender` | **Required**; needs mapping onto the PDS value set |
| — / "Postcode" | `address-postcode` | **Normalised** before sending — FR-PDS-05a; unnormalisable values are not sent at all (FR-PDS-05b) |
| — / "GP Practice" | → ODS lookup → `general-practitioner` | FR-ODS-06 |

A wording change upstream — "Surname" becoming "Family Name" — silently removes a
required parameter. FR-PDS-04 is what turns that from a wrong candidate list into
a visible failure, and R1 tracks the risk.

### 4.3 Outbound — PDS FHIR

`GET /Patient` (search) and `GET /Patient/{nhsNumber}` (retrieve). User-restricted:
every call is made on behalf of the signed-in reviewer, carrying
`NHSD-Session-URID` for their selected role (FR-AUTH-05). Parameters used:
`family`, `given`, `gender`, `birthdate`, `address-postcode`, `general-practitioner`,
`death-date`, `_history`, `_fuzzy-match`, `_max-results`. `_exact-match` is not
used (FR-PDS-08). `address-postcode` is always the normalised form (FR-PDS-05a).
Correlation headers (`X-Request-ID`, `X-Correlation-ID`) are set per NHS Digital's
platform conventions and logged with the audit entry.

### 4.4 Outbound — ODS FHIR

`GET /Organization` (search) and `GET /Organization/{odsCode}` (retrieve).
**Unauthenticated** (FR-ODS-10), so there is no credential, no onboarding and no
per-environment secret here — only a base URL, a timeout budget, a throttle and a
cache.

Parameters used: `name`, `name:contains`, `address-postalcode`, `activeRoleCode`
(`RO177`, Prescribing Cost Centre — FR-ODS-04), `active`, `_count`, `_offset`.
Note the **spelling differs from PDS** — `address-postalcode` here,
`address-postcode` there — which is a real source of error and the reason each
client owns its own parameter names. Only organisation search terms cross this
boundary, and a patient's postcode only ever as its outward code (FR-ODS-11).

### 4.5 Outbound — the gateway post-back (new)

The gateway has no route for this today. It needs one, and it belongs there
rather than here: this application must not hold a credential for the control
plane, and every dispatch should pass the same audited front door.

The route must: authenticate this application; validate the decision payload;
audit receipt; be **idempotent on the review item key** (FR-DEC-06); and answer
with an acknowledgement this application can record (FR-DEC-08).

It then **branches on the reason code**, and the branch is the whole point of the
`ACCEPTED_`/`CLOSED_` prefixes:

- an **`ACCEPTED_`** or **`MATCHED`** decision becomes a control-plane dispatch
  that registers the patient, ideally answering with the MRN (FR-DEC-10);
- a **`CLOSED_`** decision is recorded and **dispatches nothing** (FR-DEC-05a);
- an **unrecognised** reason code is **rejected**, not defaulted. A route that
  fell back to registering on an outcome it did not understand would turn a
  future reason code — added here, deployed before the gateway — into an
  unintended patient registration.

Specifying it is a change to `gateway/`, tracked as a dependency in §2.4.

### 4.6 Outbound — CIS2

OIDC discovery, authorization-code flow, `private_key_jwt` client authentication
(FR-AUTH-02), JWKS publication, and role selection from the national RBAC claims
(FR-AUTH-04). Exact claim names and the required activity codes are Open
Questions 1 and 2.

---

## 5. Non-functional requirements

### 5.1 Performance & scalability
- NFR-PERF-01 (M): The worklist **shall** render its first page within **≤ 1 s**
  at a queue depth of **10,000** items, which is why FR-INT-06 copies the ordering
  fields out of the payload rather than parsing JSON per row.
- NFR-PERF-02 (M): The automatic non-fuzzy search **shall** be issued on opening
  an item and its result rendered within **≤ 3 s** of the PDS response, so a
  reviewer is not left waiting on a screen with nothing to compare.
- NFR-PERF-03 (S): The application **should** support **25 concurrent reviewers**
  without queue contention beyond the per-item locks of FR-REV-01.
- NFR-PERF-04 (M): PDS and ODS calls **shall** be bounded by a connect and read
  timeout, and **shall** respect the published rate limits with client-side
  throttling, so this application cannot exhaust the organisation's quota and
  deny other services access to PDS.

### 5.2 Reliability & resilience
- NFR-REL-01 (M): No decision **shall** be lost. A decision is durably recorded
  before post-back and retried until acknowledged (FR-DEC-07).
- NFR-REL-02 (M): No unmatched presentation **shall** be silently dropped: every
  admitted item ends either in a recorded terminal decision or is visibly still
  in the queue. There is no third state.
- NFR-REL-03 (M): A PDS or ODS outage **shall not** prevent a reviewer working
  the queue — they can still view items, and still record the two
  accept-unmatched decisions — and **shall** be shown as an outage, never as a
  nil search result (FR-PDS-12).
- NFR-REL-04 (S): Transient failures on PDS, ODS and post-back **should** be
  retried with backoff, with retries visible in the audit trail rather than
  hidden.

### 5.3 Observability
- NFR-OBS-01 (M): Every request **shall** emit a structured, correlated log
  carrying the correlation id shared with the gateway (FR-AUD-04) and **no
  patient identifiers** (NFR-SEC-03).
- NFR-OBS-02 (M): The application **shall** expose Micrometer metrics: queue
  depth by triage score, **age of the oldest waiting item**, intake outcomes
  (accepted / already-held / rejected), decisions by type — with **closures
  without registration broken out by reason** — time-to-decision, searches by
  type and outcome, PDS/ODS error and latency, and post-back backlog.
  Oldest-waiting-item is the one a service manager should be alerted on; the
  closure rate is the one they should be asked about (R13).
- NFR-OBS-03 (S): The application **should** ship a Grafana dashboard alongside
  the existing ones, so this queue is watched where everything else is.

### 5.4 Security, safety & compliance

> Unlike the gateway, this application **cannot** pseudonymise. Its entire
> function is to display patient-identifiable data to a clinician and record what
> they concluded from it. Every control below follows from that.

- NFR-SEC-01 (M): **Human** authentication **shall** be NHS CIS2 only, at the
  agreed assurance level, with capability derived from the activity codes on the
  selected role — `B0830` and `B0011` (§3.2). No local user account **shall**
  exist in any profile with access to live data.
- NFR-SEC-01a (M): The gateway's intake endpoint (§4.1) is the **one** route into
  this application that is not a CIS2 reviewer session. It **shall** be
  authenticated as a service, and it **shall** expose no read path over patient
  data: it accepts a presentation and returns an acknowledgement. A machine
  credential must not become a way to read the queue without being a named
  clinician.
- NFR-SEC-02 (M): Patient data at rest — the stored presentations, the review
  state and the audit trail — **shall** be encrypted, access-controlled, and
  retained only per a configurable policy (FR-AUD-10).
- NFR-SEC-03 (M): Patient identifiers **shall not** appear in application logs,
  metric labels, or error pages. Identifiers belong in the audit trail, which is
  access-controlled for the purpose; a log file is not.
- NFR-SEC-04 (M): All traffic — browser, CIS2, PDS, ODS, post-back — **shall** be
  TLS. PDS and ODS credentials and CIS2 signing keys **shall** be held in a
  secrets manager.
- NFR-SEC-05 (M): The audit trail **shall** be immutable and attributable, per the
  parent specification's NFR-SEC-05, and readable only by AUDITOR (FR-AUD-05).
- NFR-SEC-06 (M): Handling **shall** comply with **UK GDPR / DPA 2018** and NHS
  Information Governance (DSPT). A **DPIA shall** be completed before production
  use: this application creates a new store of patient data and a new pattern of
  staff access to PDS, both of which are material changes.
- NFR-SEC-07 (M): The application **shall** support a **synthetic-data mode**
  against the PDS and ODS sandboxes, with no route to live patient data, for
  development and training. Reviewers **shall** be trained on synthetic data —
  the first time someone matches a patient must not be against a real one.
- NFR-SAFE-01 (M): The application **shall never** accept a match automatically,
  at any score, including `1.0`. Every match is a human decision, explicitly
  confirmed (FR-DEC-03). This is the parent specification's NFR-REL-04 fail-safe
  applied to identity: acting on the wrong patient is the critical risk here, and
  a score is evidence for a decision, not the decision.
- NFR-SAFE-02 (M): Where data is missing or ambiguous, the application **shall**
  present the ambiguity rather than resolve it — a missing triage score sorts to
  the top (FR-WL-04), an underivable required parameter blocks the search
  (FR-PDS-04), an error is never rendered as an empty result (FR-PDS-12).

### 5.5 Compatibility & portability
- NFR-CMP-01 (M): The application **shall** run on **Java 25 / Spring Boot 4.1**
  and PostgreSQL, per the existing scaffold, and **shall not** share a schema with
  the control plane.
- NFR-CMP-02 (M): It **shall** work in the browsers the target NHS estate
  supports, and be usable at the screen sizes actually on ED desks.
- NFR-CMP-03 (S): The candidate comparison view **should** meet **WCAG 2.2 AA**.
  It is a public-sector service, and a reviewer comparing two nearly identical
  patient records needs contrast and structure to do it safely. Building on the
  NHS.UK frontend (FR-UI-01) is what makes that reachable for the rest of the
  interface — which is why the comparison view, the one screen the library does
  not supply (FR-UI-02), is the one named here for explicit testing.

### 5.6 Maintainability
- NFR-MNT-01 (M): The PDS and ODS clients **shall** each sit behind their own
  interface with their own parameter names (§4.4), so neither API's naming leaks
  into the other or into the domain.
- NFR-MNT-02 (M): The application **shall** share no code with the gateway or the
  control plane, integrating over their REST surfaces — the same rule `gateway/`
  already follows.
- NFR-MNT-03 (M): The `user.data[]` mapping (§4.2) and the ordering rule
  (FR-WL-05) **shall** be versioned configuration with tests over the captured
  sample payloads, so a change upstream is caught by a build rather than by a
  reviewer.

---

## 6. Proposed technical architecture (informative)

| Concern | Proposed technology |
|---------|--------------------|
| Application | Spring Boot 4.1, Java 25, Spring Web MVC |
| Reviewer UI | Thymeleaf + Spring Security, matching the control plane; **NHS.UK frontend** components (§3.10) |
| Live worklist | The list is a Thymeleaf fragment served on its own path. A few lines of progressive-enhancement JavaScript re-fetch it on the configured interval, sending the signature of what is on screen; the server answers `204 No Content` when nothing has changed, so an idle queue costs one cheap query and no DOM churn. Polling stops while the reviewer is away, so an unattended page cannot hold a session open (FR-WL-11a/11b). With JavaScript off the list is simply static (FR-UI-07) |
| Frontend build | `frontend-maven-plugin` installs a pinned Node into `target/`, runs `npm ci` against a committed `package-lock.json`, compiles `src/main/frontend/application.scss` and copies the library's JavaScript and assets. Output goes to `target/frontend`, mapped into the jar as `static/` — nothing generated is committed, and `mvn clean` really cleans. `-Dfrontend.skip=true` builds the Java alone, for an offline or air-gapped build |
| AuthN/Z | CIS2 OIDC authorization-code login via `spring-boot-starter-security-oauth2-client`; `private_key_jwt` via `spring-security-oauth2-jose`; method/URL RBAC granted by the selected role's activity codes — `B0830` → REVIEWER, `B0011` → AUDITOR |
| Intake | Authenticated service-to-service POST endpoint (Spring Web MVC), idempotent on the review item key, committing before it answers; the gateway retries from its own durable store |
| PDS client | `RestClient`, own parameter names, own throttle and timeout budget, `NHSD-Session-URID` per reviewer session; FHIR library only if a spike shows it earns its weight (§6.1) |
| ODS client | `RestClient`, unauthenticated, own parameter names, cached practice lookups; no credential to hold |
| Persistence | PostgreSQL — review items (with the presentation JSON verbatim), review sessions, locks, searches, decisions, audit, post-back outbox |
| Post-back | `RestClient` to the gateway's Camel route, driven by a transactional outbox with backoff |
| Locking | Row-level lock with an expiry timestamp, reclaimed by a scheduled sweep (FR-REV-02) |
| Audit | Append-only table, insert-only repository, no update/delete path (FR-AUD-05) |
| Metrics/logging | Micrometer + Prometheus, structured JSON logging with no PID |
| Secrets | External secrets manager, as the rest of the estate |

### 6.1 Key architectural decisions to validate early

*Settled: intake is a POST from the gateway (FR-INT-02a), and the NHS.UK frontend
is compiled into the Maven build by `frontend-maven-plugin` from `package.json`
(§3.10). What remains:*

1. **FHIR client** — HAPI FHIR R4 structures versus `RestClient` + Jackson. These
   are read-only searches whose results are displayed and forwarded, so the
   heavyweight option may buy little. Spike against the PDS sandbox.
2. **CIS2 role selection** — how a multi-role reviewer selects a role, how the
   URID is carried into every PDS call for the session, and how re-selection
   switches capability between `B0830` and `B0011` (FR-AUTH-04/05, FR-AUTH-07a).
3. **Input normalisation against real data** — the `user.data[]` mapping (§4.2)
   and the postcode normaliser (FR-PDS-05a/b) both turn free text a patient typed
   into a search parameter, and both must be validated against real production
   payloads — partial postcodes, non-UK addresses, unexpected question wording —
   not only the eight captured samples.
4. **Where the queue lives** — this application's own schema (assumed) versus a
   table the gateway also writes. Separate schemas are preferred, which is what
   makes the intake POST the contract rather than a shared table.
5. **Intake retry on the gateway side** — the gateway needs a durable store and
   backoff for undelivered presentations (FR-INT-02b). Specified here because
   this application depends on it, but built in `gateway/`.

---

## 7. Risks & mitigations

| # | Risk | Impact | Mitigation |
|---|------|--------|-----------|
| R1 | The `user.data[]` mapping breaks silently when upstream question wording changes, so searches run on fewer fields than intended | High | Prefer `key` over `question`; versioned mapping config; FR-PDS-04 blocks a partial search; tests over captured samples (NFR-MNT-03); monitor the rate of underivable searches |
| R2 | **The wrong patient is matched**, and a presentation is registered against another person's record | Critical | No automatic acceptance at any score (NFR-SAFE-01); side-by-side field comparison (FR-PDS-07); explicit confirmation (FR-DEC-03); full audit; synthetic-data training (NFR-SEC-07) |
| R3 | Every presentation arrives unmatched (as in all eight samples), and the queue exceeds any realistic review capacity | High | Confirm the sender's production matching behaviour (OQ2) before go-live; queue-depth and oldest-item alerting (NFR-OBS-02); triage-score ordering means the urgent are seen first even under backlog |
| R4 | A duplicate patient is registered on the EPR from a retried post-back | Critical | Idempotency on the review item key (FR-DEC-06) at both ends; outbox with acknowledgement (FR-DEC-07); no re-decide (FR-DEC-09) |
| R5 | Intended reviewers do not hold `B0830` — or auditors `B0011` — and it is discovered at go-live, when the people who need the queue cannot open it | High | Confirm role profiles against the Registration Authority before build, not before release; FR-AUTH-06 refuses clearly and names what is missing rather than failing as a 403; test with real role profiles in the integration environment |
| R6 | PDS rate limits are hit, denying other services access to PDS | Medium | Client-side throttling (NFR-PERF-04); ODS caching (FR-ODS-09); no automatic re-querying (FR-FUZ-08); establish the actual limits (OQ4) |
| R7 | ODS is unauthenticated, so nothing is reserved for us: no quota, no SLA, and an egress path with no credential gating what leaves | Medium | Timeouts, throttle and cache regardless (FR-ODS-10); degrade gracefully — a reviewer can still work the non-fuzzy and fuzzy routes without the practice lookup (NFR-REL-03); patient identifiers never sent, postcode only as its outward code (FR-ODS-11) |
| R8 | This application becomes a new, attractive store of patient data | High | Encryption at rest, retention limits, CIS2-only access, no PID in logs, DPIA (§5.4); store only the presentation already held upstream, nothing derived |
| R9 | A decision is recorded but never registered, and looks finished | High | Post-back state surfaced to a service manager (FR-DEC-08); backlog metric and alert; no terminal state that is not either acknowledged or visibly failed |
| R10 | An "accepted — not on PDS" decision is recorded when PDS was simply unreachable | High | Distinguish `unsuccessful` from `unavailable` on the review screen (§2.2, FR-REV-03); errors never render as nil results (FR-PDS-12) |
| R11 | The intake POST fails and nobody notices, so unmatched presentations never reach the queue — the worst failure here, because an empty worklist looks like a cleared one | Critical | Distinguishable intake responses so the gateway knows to retry (FR-INT-02b); gateway-side durable retry with backoff (§6.1); intake outcome metrics and alerting on delivery failure (NFR-OBS-02); periodic reconciliation of gateway-side unmatched presentations against admitted review items |
| R12 | Postcode normalisation changes the search rather than fixing it — a partial or non-UK value padded into something plausible | Medium | Normalise only what parses as a UK postcode, and send nothing where it does not (FR-PDS-05b); record submitted and sent values side by side in the audit (FR-AUD-02); validate the normaliser against real submitted values (§6.1) |
| R13 | The two closing decisions become a **backlog escape hatch**: under queue pressure "not necessary" is the fastest way to make the worklist shorter, and a patient who presented is closed with nobody registering them and nobody now looking for them | High | Mandatory note on every closure and a record of *where* a manual registration landed (FR-DEC-01b/01c); confirmation states that no registration will happen (FR-DEC-03); closure rate broken out by reason and reviewed with the service manager (NFR-OBS-02); a wrongly closed item is re-admissible (FR-INT-08); the pressure itself is R3, and treating the symptom here would be the wrong fix |
| R14 | A closing decision reaches a gateway route that does not understand it and is registered anyway — most likely when a new reason code is deployed here first | Critical | Class is readable from the reason-code prefix (FR-DEC-01a); the route dispatches nothing for `CLOSED_` and **rejects** an unrecognised code rather than defaulting to registration (FR-DEC-05a, §4.5); acceptance criterion 12 exercises exactly this |

---

## 8. Acceptance criteria (release 1)

The application is accepted for release 1 when:

1. A validated presentation carrying no PDS demographics, **POSTed by the
    gateway**, is admitted to the queue exactly once; the same POST repeated
    creates no second item and is answered as already-held; and a presentation
    carrying PDS demographics is not admitted at all
    (FR-INT-01/02/02a/02b/03/05).
2. Stopping this application, submitting a presentation to the gateway, and
    restarting results in the presentation appearing in the queue with no manual
    intervention and no failure returned to the sender (FR-INT-02b).
3. A reviewer signs in with **CIS2**, selects a national RBAC role, and cannot
    reach any review screen without doing both (FR-AUTH-01/04). A role without
    **`B0830`** is refused with an explanation of what is missing, not with a
    downstream 403 (FR-AUTH-06).
4. The worklist shows waiting items ordered by triage score then waiting time,
    with unscored items banded at the top, and both keys visible on the row
    (FR-WL-01/02/03/04).
5. With the worklist open and untouched, an item admitted by the gateway appears, and an item
    another reviewer opens changes to "being reviewed" — both without the reviewer acting. A
    refresh that changes nothing writes no audit row and does not disturb the page, and refreshing
    stops once the reviewer has been away long enough that their session should be timing out
    (FR-WL-09/11/11a/11b).
6. Opening an item locks it, runs a **non-fuzzy PDS search with `_history=true`**
    built from the patient's own answers, and shows candidates with scores
    side-by-side against the submitted details (FR-REV-01, FR-PDS-01/02/06/07).
7. A postcode submitted as `ls16ae` is searched as `LS1 6AE`, and the audit entry
    shows both the submitted and the sent value. A postcode submitted as `LS1` is
    not sent at all, and is shown to the reviewer as unusable
    (FR-PDS-05a/05b, FR-AUD-02).
8. Where the required parameters cannot be derived, the reviewer is told which
    ones and no partial search is issued (FR-PDS-04).
9. The reviewer finds a GP practice through **ODS `GET /Organization`** —
    unauthenticated, restricted to **Prescribing Cost Centre (`RO177`)** via
    `activeRoleCode` — and runs a practice-scoped PDS search from it
    (FR-ODS-01/02/03/04/10).
10. The reviewer runs a **fuzzy** search with parameters they selected, and an
    invalid combination is refused as a form error before PDS is called
    (FR-FUZ-01/02/03). Reaching it **without** having run the GP practice search
    is possible, and the audit shows the path actually taken (FR-REV-09/10).
11. Selecting a candidate and confirming produces a `MATCHED` decision whose
    post-back carries the presentation plus the matched NHS number, is
    acknowledged by the gateway route, and is not duplicated when retried
    (FR-DEC-01/04/05/06).
12. Both accept-unmatched decisions are recorded and posted back as **distinct**
    reason codes (FR-DEC-01/02).
13. Each **closing** decision — already registered on the EPR by hand, and
    registration no longer required — is refused without its note, and once given
    is recorded, leaves the worklist, is posted back under a **`CLOSED_`** reason
    code, and results in **no patient registration**. `CLOSED_REGISTERED_MANUALLY`
    captures where the manual registration landed. A payload carrying a reason
    code the route does not recognise is **rejected**, not registered
    (FR-DEC-01/01a/01b/01c/03/05a).
14. An item is returned to the queue with a reason, keeps its priority, and shows
    the previous session's history to the next reviewer (FR-REV-04/05).
15. Killing the gateway, recording a decision, and restarting it results in the
    decision being delivered exactly once with no reviewer action
    (FR-DEC-07, NFR-REL-01).
16. A user whose selected role carries **`B0011`** can see every view, search and
    decision from the run-through above — attributed to the reviewer and the role
    they had selected — can search it, cannot alter it, cannot open a review item,
    and their own access to the trail appears in it (FR-AUD-01…07). A user holding
    both `B0830` and `B0011` can exercise only the one they selected
    (FR-AUTH-07a).
17. No patient identifier appears in any application log or metric label from the
    whole run-through, and no patient identifier appears in any ODS request
    (NFR-SEC-03, FR-ODS-11).
18. Every reviewer screen is built from NHS.UK frontend components; each page
    loads with **no request to any host outside this application**; and the
    fuzzy-search form reports an invalid parameter combination through the design
    system's error summary, linked to the fields at fault
    (FR-UI-01/03/06, FR-FUZ-03).

---

## 9. Open questions

1. **CIS2 registration and assurance.** Is the client registered in each
   environment, is it `private_key_jwt` or client secret, and is **AAL2 or AAL3**
   required for this service? Which exact ID-token claims carry the national RBAC
   roles and the URID?
2. **Unmatched volume.** In production, what proportion of presentations arrive
   unmatched? All eight captured samples are unmatched and none carries
   `user.nhs` at all — if that is representative, the review capacity required is
   very different from an exception-handling queue. (Blocking for sizing; R3.)
3. **Registration semantics.** What does the control plane do with a decision —
   which bot registers the patient, what does it do differently for a `MATCHED`
   decision versus each accept-unmatched reason, and does it return an MRN?
4. **PDS rate limits and environments.** What are the published rate limits and
   the per-environment base URLs, so NFR-PERF-04's throttle is set to a real
   number rather than a guess? (R6.)
5. **Who reviews.** `B0830` settles what the application checks, not who holds
   it. Is a Reviewer a clinician, a member of ED reception staff, or both, and
   does the decision require a clinical registration? This affects who the
   Registration Authority grants `B0830` to, and the training in NFR-SEC-07.
6. **Retention.** How long are review items, candidate lists and the audit trail
   kept, and who authorises expiry (FR-AUD-10)?
7. **Escalation.** Is there a time limit after which an unreviewed presentation
   must be escalated or registered unmatched by default? Nothing in the brief
   says what happens to an item nobody picks up.
8. **NHS branding on an internal tool.** The NHS.UK frontend is NHS England's
   library for NHS services, and its header carries the NHS logo. This is a
   staff-facing internal clinical tool, not a public nhs.uk service. Who owns
   that call locally — communications, or the brand owner — and what service
   name and header treatment do they want (FR-UI-05)?

### 9.1 Decisions taken since v0.1

| Question | Decision | Where it landed |
|---|---|---|
| How do presentations arrive? | **POST from the gateway** | FR-INT-02a/02b, §2.1, §4.1, §6.1 |
| Which ODS role identifies a GP practice? | **Prescribing Cost Centre** (`RO177`) | FR-ODS-04, §4.4 |
| What authentication does ODS FHIR need? | **None — unauthenticated** | FR-ODS-10/11, §4.4, R7 |
| Which activity grants access to the application? | **`B0830`** | FR-AUTH-06/07, NFR-SEC-01 |
| Which activity grants access to the audit trail? | **`B0011`** | FR-AUD-05, FR-AUTH-07/07a |
| How is the patient's postcode handled? | **Normalised before search**; unnormalisable values not sent | FR-PDS-05a/05b, FR-AUD-02, R12 |
| Can an item be closed without registering anybody? | **Yes — two closing outcomes**: already registered on the EPR by hand, and registration no longer required. Reported to the gateway, dispatching nothing | FR-DEC-01/01a/01b/01c/05a, §4.5, R13, R14 |
| Does a locked item leave the worklist? | **No — it stays, marked as being reviewed** (FR-WL-06). The former FR-WL-12 asked for removal and was withdrawn: it contradicted FR-WL-06, and removal cannot prevent concurrent review anyway — the pessimistic lock of FR-REV-01 does that. Hiding an urgent patient a reviewer can see is being handled beats leaving them wondering where the row went | FR-WL-06, FR-REV-01, §9.1 |
| Is the search order enforced? | **No — a default path the reviewer can depart from**; the path taken is recorded instead | FR-REV-09/10, FR-DEC-01d, FR-ODS-01 |
| What is the interface built from? | **NHS.UK frontend** (`nhsuk-frontend`), self-hosted and version-pinned | §3.10, NFR-CMP-03, §6 |
| How does it get into the build? | **`frontend-maven-plugin`** — pinned Node in `target/`, `npm ci`, Sass compiled to `target/frontend`, packaged as `static/` | §6, FR-UI-03/04, `pom.xml`, `package.json` |
