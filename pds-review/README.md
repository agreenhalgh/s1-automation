# PDS match review

A clinical worklist for triage presentations whose patient the calling application **could not
match to NHS PDS**. A reviewer signs in with **NHS CIS2**, searches PDS and the Organisation Data
Service, and records a match decision — which is posted back to the RPA control plane through a
Camel route on the gateway, to register the patient.

**Status: specification only.** This sub-project currently contains its requirements, its build
and a Spring Boot entry point. Nothing in §3 of the requirements is implemented yet.

📄 **[docs/requirements.md](docs/requirements.md)** — the specification.

## Why it exists

A presentation arrives carrying the patient's own answers — name, date of birth, postcode,
telephone — and, where the sender's own PDS lookup succeeded, the matched NHS demographics. When
it did not, nobody has yet established *who this patient is*, and no patient can be registered on
the EPR until somebody does.

Today that gap is silent. In all eight captured sample payloads, `nhsUser` is null and
`user.nhs` is absent entirely, which on that evidence means every presentation needs a human. This
application makes the gap a queue, ordered so the most urgent patient is seen first.

## The review path

```
unmatched presentation             POSTed by the gateway once it has validated the submission
  └─ admitted to the queue          once, idempotently, with the presentation stored verbatim
       │
       ▼
     worklist                        triage score first, then waiting time
       │                             unscored items banded at the top, never the bottom
       ▼
     open an item                    exclusive lock; released if the reviewer walks away
       │                             the three searches are a default order, not a gate —
       │                             skip one, go straight to fuzzy, come back; the path is audited
       ├─ non-fuzzy PDS search       automatic, from the patient's own answers, _history=true
       │                             postcode normalised — LS16AE does not match LS1 6AE
       ├─ GP practice search         ODS GET /Organization, unauthenticated, RO177 practices
       │                             → PDS general-practitioner
       ├─ fuzzy PDS search           _fuzzy-match=true, reviewer-selected parameters
       │
       ▼
     decide  ── register           MATCHED
                                     ACCEPTED_NOT_ON_PDS
                                     ACCEPTED_PATIENT_UNAVAILABLE
             ── close, no registration
                                     CLOSED_REGISTERED_MANUALLY
                                     CLOSED_NO_LONGER_REQUIRED
                                     …or return to queue, with a reason and no loss of priority
       │
       ▼
     post-back                       written down first, retried until acknowledged
       └─ gateway Camel route ─────▶ ACCEPTED_/MATCHED → control plane → register
                                     CLOSED_          → recorded, dispatches nothing
                                     anything else    → rejected, never registered
```

Every view, every search and every decision is audited against the CIS2 identity and the national
RBAC role the reviewer selected. Access follows the activity code on that selected role —
**`B0830`** to work the queue, **`B0011`** to read the trail — and only the one selected, so
someone holding both exercises one at a time. The trail is append-only, and an auditor cannot open
a review item or decide anything: the same separation the control plane already draws between
reviewing automation and running it.

## Three rules worth reading before the code

**No match is ever accepted automatically.** Not at any score, including `1.0`. A PDS score is
evidence for a human decision, not the decision. Registering a presentation against the wrong
person's record is the critical risk here, so where data is missing or ambiguous this application
presents the ambiguity rather than resolving it: a missing triage score sorts to the top, an
underivable search parameter blocks the search instead of narrowing it, and a PDS error is never
rendered as "no matches found".

**The two accept-unmatched decisions stay distinct.** "Not registered on PDS" and "patient not
available to confirm their details" are different clinical claims, and only the first should ever
be read as *this person has no NHS record*. They are stored and posted back as separate reason
codes, never collapsed.

**A decision either registers a patient or it does not, and the reason code says which.** Three
outcomes register; two close the item with nobody registered — the patient was already put on the
EPR by hand, or no longer needs to be. The `MATCHED`/`ACCEPTED_` and `CLOSED_` prefixes carry that
difference so nobody has to infer it from context, the gateway route branches on it, and a reason
code the route does not recognise is **rejected** rather than defaulted to registering. Otherwise
a new outcome added here and deployed before the gateway becomes an unintended patient
registration. Closures also carry a mandatory note, because "no longer required" without a reason
is a closure nobody can review — and because under a backlog "not necessary" is the quickest way
to shorten a queue.

## A standalone sub-project, not a reactor module

`pds-review/` has its own `pom.xml` and no parent relationship to the root project — the same call
as `gateway/`, for the same reasons. It shares no code with the control plane and reaches it only
through the gateway's REST surface.

It shares no code with the **gateway** either, including the `Presentation` DTO. A review item
keeps its presentation as the JSON it arrived as and binds only the handful of fields review
actually needs, so a change to OPTO Health's Mongoose models cannot break the review queue — and
the post-back can hand the presentation onward unaltered.

```powershell
cd pds-review
..\mvnw.cmd spring-boot:run     # port 8084
..\mvnw.cmd test
```

8080 control plane, 8081 capture tool, 8082 worker actuator, 8083 gateway, 8084 here.

The interface is built with the [NHS.UK frontend](https://github.com/nhsuk/nhsuk-frontend) and the
patterns of the NHS digital service manual (§3.10), with its CSS and JavaScript **self-hosted**
rather than pulled from a CDN — a clinical worklist should not fail to render because a network
blocked egress, and nobody outside the deployment should be able to change what a clinician sees.

`frontend-maven-plugin` makes that one Maven build. It installs a pinned Node into `target/`, runs
`npm ci` against the committed `package-lock.json`, compiles `src/main/frontend/application.scss`
and copies the library's JavaScript and assets to `target/frontend`, which is packaged into the jar
as `static/`. Nothing generated is committed and `mvn clean` really cleans, so there is no stale
stylesheet in the source tree to mislead anyone.

```powershell
..\mvnw.cmd package                          # Java + frontend
..\mvnw.cmd -o -Dfrontend.skip=true test     # Java only — offline, no Node, no npm registry
```

The skip switch is for compiling and testing server-side code without network. The jar it produces
has no stylesheet, so it is never a release build.

Node is pinned to v24 and must not go below **22.12**: the Sass CLI `require()`s chokidar, which is
now ESM-only, and `require()` of an ES module is rejected before that. On 22.11 the stylesheet
build fails with `ERR_REQUIRE_ESM`.

## What it needs that does not exist yet

- **Two routes in `gateway/`**, neither of which exists today: one that **POSTs** each unmatched
  presentation here and retries from its own durable store until we accept it (§4.1), and one
  that **receives** review decisions and dispatches the registration (§4.5).
- **A CIS2 client registration** per environment, with the assurance level agreed — and
  `B0830` / `B0011` granted by the Registration Authority to the people who actually need them.
  Discovering at go-live that reviewers lack `B0830` is R5.
- **A control-plane bot** that registers a patient from a decision, and answers with an MRN.

Eight open questions remain in §9 of the requirements; §9.1 records the six now settled. The
blocking one is **unmatched volume**: all eight captured samples are unmatched, and if that holds
in production this is the main path rather than an exception queue, which changes the review
capacity needed by an order of magnitude.
