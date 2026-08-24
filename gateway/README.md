# SystmOne RPA — integration gateway

An Apache Camel instance in front of the control plane. It accepts run requests on a REST API,
**audits**, **validates** and **transforms** them, dispatches them as runs, and answers the
caller **only once the run has definitively succeeded, failed or timed out**.

It exists to absorb one mismatch. The control plane is asynchronous by design — dispatch returns
a run id and the outcome arrives later over a different path (FR-CP-01, FR-WRK-03). Plenty of
calling systems cannot work that way: a clinical system asking "has that been filed?" needs an
answer, not a job id and a callback contract. The gateway waits on their behalf.

## A standalone sub-project, not a reactor module

`gateway/` has its own `pom.xml` and no parent relationship to the root project. It shares **no
code** with the control plane — not even the DTOs — and integrates over the same REST API any
other client would use. That is the point of a gateway: a compile-time dependency on the control
plane's internals would turn every rename there into a build break here.

Making it a reactor module would have meant moving the existing source tree under a module
directory and rewriting every run instruction, IDE configuration and doc path for no gain. If a
reactor is wanted later, that is a deliberate restructure of the root project rather than
something this sub-project should have forced.

Camel 4.22 is the first line built against **Spring Boot 4** — its starter depends on
`spring-boot-starter-restclient`, which only exists in Boot 4 — so this runs on the same Boot
version and the same JDK 25 as the control plane rather than pinning a second toolchain.

```powershell
cd gateway
..\mvnw.cmd spring-boot:run     # port 8083
..\mvnw.cmd test
```

## The pipeline

```
POST /gateway/v1/runs
  ├─ authenticate            X-Gateway-Token, compared in constant time
  ├─ correlate               assign gw-<uuid>, echoed in X-Correlation-Id and every audit row
  ├─ parse                   strict: an unknown field is a 400, not a silent drop
  ├─ audit RECEIVED          before validation, so a rejection has a parent row
  ├─ validate                JSR-380 constraints + the NHS number check digit
  ├─ store document          pdfBase64 → <root>/<yyyy-MM-dd>/<user._id>-<correlationId>.pdf
  │                          (only when one is attached)      → audit DOCUMENT_STORED
  ├─ transform               external contract → control-plane DispatchRequest
  ├─ dispatch                POST /api/runs with X-Worker-Token   → audit DISPATCHED
  ├─ await                   poll GET /api/runs/{id} until terminal or the deadline passes
  └─ audit outcome, answer
```

Five Camel routes: `gateway-submit`, `gateway-store-document`, `gateway-dispatch`,
`gateway-await`, `gateway-poll`.

## The contract

```bash
curl -s -X POST http://localhost:8083/gateway/v1/runs \
  -H 'X-Gateway-Token: changeit' -H 'Content-Type: application/json' -d '{
    "requestId": "caller-ref-1",
    "bot": "get-postcode-jab",
    "subject": { "nhsNumber": "9434765919", "localId": "L-42" },
    "user":    { "_id": "clinician.7" },
    "parameters": { "searchTerm": "Smith" },
    "pdfBase64": "JVBERi0xLjQK...",
    "pool": "ward-a",
    "waitSeconds": 120
  }'
```

`user` and `pdfBase64` are both optional, but `user._id` becomes required the moment a document
is attached — the stored file is named after it, so a document without one has nowhere to go.

```json
{
  "requestId": "caller-ref-1",
  "correlationId": "gw-5baadb38-…",
  "runId": "3f2a91cc-…",
  "outcome": "SUCCEEDED",
  "definitive": true,
  "startedAt": "2026-08-24T09:00:01Z",
  "finishedAt": "2026-08-24T09:00:09Z",
  "waitedMs": 8420,
  "outputs": { "postcode": "LS1 4AP" }
}
```

The external names are deliberately **not** the control plane's. `bot` → `botId`, `pool` →
`capabilityTag`, and the structured `subject` is flattened into the flat `Map<String,String>` that
a bot definition's placeholders resolve against — `${subject.nhsNumber}`, `${subject.localId}`,
and `${document.path}` / `${document.filename}` when a PDF was attached. That mapping is the
transform step, and it is what lets either contract move without the other.

### What the HTTP status means

| Status | Meaning |
| --- | --- |
| **200** | The run reached a terminal state. `outcome` is `SUCCEEDED`, `FAILED`, `ABORTED` or `TIMED_OUT`, and `definitive` is `true`. |
| **400** | Rejected before dispatch. `problems` lists every fault, so three bad fields take one round trip, not three. |
| **401** | Missing or wrong `X-Gateway-Token`. |
| **502** | The control plane refused the request or could not be reached. No run was created; safe to retry. |
| **504** | The gateway stopped waiting. `outcome` is `GATEWAY_TIMEOUT`, `definitive` is `false`. |

**A failed run is a 200.** The HTTP status describes whether the gateway did its job; the body
describes what the run did. That matters more here than it usually would: many integration
clients retry non-2xx automatically, and a clinical action must not be re-run because the first
attempt legitimately failed. Non-2xx is reserved for the cases where the gateway has no answer.

**`TIMED_OUT` and `GATEWAY_TIMEOUT` are different events and must never be conflated.**
`TIMED_OUT` is a *finished* run: the engine hit its own global deadline (FR-ENG-08), gave up and
recorded that. Nothing is still happening. `GATEWAY_TIMEOUT` is the opposite — the run was still
non-terminal when this gateway stopped waiting, and is very likely still typing into a live
patient record. The 504 body says so explicitly and names the run id to follow up.

## Validation

Constraints live as annotations on `RunSubmission`, so the contract and its rules are one
artefact, and are run through Camel's `bean-validator:`. Two choices worth knowing about:

- **Unknown fields are rejected.** An unrecognised property in an inbound clinical request is far
  more likely to be a caller sending something important to the wrong name than harmless padding.
  Dropping it silently would mean running a bot against a patient with a parameter the caller
  believes they set. (Responses *from* the control plane are the opposite — unknown fields there
  are ignored, because that service is entitled to evolve.)
- **The NHS number check digit is verified**, not just its shape. A mistyped number that passes a
  ten-digit regex sends a worker hunting for a patient who cannot exist, and surfaces minutes
  later as "no locator resolved" rather than as "that is not an NHS number".

## The document store

A submitted `pdfBase64` is written by the `gateway-store-document` route before the run is
dispatched — the run is told where the file is, and it cannot be told about a file that does not
exist yet.

```
<root>/2026-08-24/clinician.7-gw-161f5876-75dc-4a3c-a197-df8399cd4880.pdf
       └ dated       └ user._id   └ correlation id
```

**The dated folder is UTC.** A document submitted at 00:30 during British Summer Time would
otherwise land in the previous day's folder relative to the run record that references it — the
sort of off-by-one that makes a retention sweep delete the wrong day.

**The filename is the submitter plus the correlation id, not the submitter alone.** Two documents
from one user on one day would otherwise resolve to one filename and the second would overwrite
the first, silently losing a clinical document — the worst failure this route could have. The
correlation id also appears on every audit row for the request, so a file on disk can be traced
back to who sent it, when, and which run it produced. The write itself uses `fileExist=Fail`, so
even a collision fails loudly rather than replacing something already filed.

**The destination is checked, not just pattern-matched.** `user._id` is caller-supplied and
becomes a path, which is the classic route from "upload endpoint" to "write anywhere on the
filesystem". There are two guards: a `@Pattern` on the record, and a check that the resolved
absolute path sits *exactly* in today's folder. Both are needed — a constraint on a request
record is one edit away from not being enforced, and containment under the storage root alone is
too weak, because `../outside` normalises to a path still inside the root but out of today's
folder, from which a caller could disturb another day's files. A path that does not check out is
**rejected, not sanitised**: quietly rewriting a hostile path tells the sender their request
worked.

**What is stored is checked too.** The payload must decode as base64 (MIME decoder, so
line-wrapped input is fine — plenty of libraries wrap at 76 characters by default), come in under
`maxBytes` *after* decoding, and begin with `%PDF-`. A name is not evidence of content, and this
file is about to be handed to a bot to file into a patient record. It is a check on the wrapper,
not a scan of the contents — see the malware-scanning gap below.

**An orphan is possible and is traceable.** Because the file is written before dispatch, a
control plane that then refuses the request leaves a document on disk with no run against it. The
file is kept rather than deleted — discarding a clinical document because a downstream call
failed is the worse of the two mistakes — and the `DOCUMENT_STORED` row followed by a `REJECTED`
row with no `DISPATCHED` between them is what makes the orphan findable.

If the write itself fails (no space, no permission), the caller gets **500** with an explicit "the
document could not be stored, so no run was dispatched" — never the generic control-plane
message, because they must not conclude the document has been filed.

## Audit

Its own trail, in its own table (`gateway_request_audit`), append-only — one row per stage, all
sharing a correlation id. It is separate from the control plane's `audit_log` because the two
answer different questions: that one records what the automation did, this records **what was
asked of it and by whom, including the requests that never became runs**. A bad token or a
malformed NHS number never reaches the control plane, so those events exist nowhere else, and
they are exactly what an integration owner needs.

Each write runs in its own transaction, so a request that ends in a rollback still leaves its
rows behind. An audit trail that disappears precisely when something went wrong is worse than
none, because it reads as "nothing happened".

**No patient identifiers are stored.** The NHS number is passed *onward* — the bot needs it to
find the record — but what lands in the table is a keyed HMAC of it (`nhs-919722d657ef…`). A
plain digest would not do: there are only ten billion possible NHS numbers, so an unkeyed
SHA-256 of one is reversible by exhaustive search in minutes, which would make the table a
register of patient identifiers in all but name (NFR-SEC-03, NFR-SEC-05). The same patient always
yields the same reference, so a trail can be followed without naming anyone. Parameter **keys**
are recorded and values are not, for the same reason: a value can carry clinical context, a key
only names it.

`GATEWAY_PSEUDONYM_KEY` must be set once per environment and kept — rotating it deliberately
severs the link between references written before and after.

The **submitter's** id is recorded in the clear, and that is not an inconsistency with the above.
`user._id` is the principal — "who did this" is the first thing an audit trail exists to answer —
and it is already the name of the file on disk. A patient identifier is data about a third party
who is not making the request; a submitter identifier is the request's provenance. The
`DOCUMENT_STORED` row records the submitter, the filename, the path, the byte count and a
SHA-256 of the content, so a stored file can later be shown to be the one that was received.
It does not record the content.

## Waiting costs a thread

A waiting request holds a request thread for the duration, because the caller is holding a
connection open and there is nothing to hand it to. With the default 120s wait and Tomcat's 200
threads the gateway saturates at roughly 200 concurrent runs. That ceiling is deliberate rather
than accidental, and it is why `maxWaitSeconds` exists: raise `GATEWAY_MAX_CONCURRENT` and the
wait ceiling together, not one without the other.

Bounded elsewhere too: `camel.component.http.*` timeouts cap a single call to the control plane,
because the wait deadline governs the poll loop and not a socket that never answers.

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `GATEWAY_PORT` | 8083 | 8080 control plane, 8081 capture tool, 8082 worker actuator |
| `GATEWAY_TOKEN` | `changeit` | inbound `X-Gateway-Token` |
| `CONTROL_PLANE_URL` | `http://localhost:8080` | |
| `SYSTMONE_WORKER_TOKEN` | `changeit` | presented to `/api/**` |
| `GATEWAY_DEFAULT_WAIT_SECONDS` | 120 | when a submission does not ask |
| `GATEWAY_MAX_WAIT_SECONDS` | 1800 | ceiling on what one may ask for |
| `GATEWAY_POLL_INTERVAL_MS` | 2000 | one control-plane read per poll |
| `GATEWAY_PSEUDONYM_KEY` | `changeit` | keys the audit subject reference |
| `GATEWAY_DOCUMENT_ROOT` | `./gateway-documents` | document store root; dated folders beneath it |
| `GATEWAY_DOCUMENT_MAX_BYTES` | 15728640 | largest PDF accepted, measured after decoding |
| `GATEWAY_JDBC_URL` | the `systmone` database | its own table in it |

## Not included

- **Idempotency.** A client retry of the same `requestId` dispatches a second run. For a
  synchronous API over a clinical action that is the most important remaining gap: the audit
  table already holds the correlation and the run id, so it is the natural store for a
  replay-the-previous-outcome check, but a half-built version of that is worse than none.
- **Rate limiting.** An unauthenticated caller can fill the audit table with `REJECTED` rows.
- **An authenticated metrics endpoint.** `/actuator/prometheus` is open here, unlike on the
  control plane and workers, because this module has no Spring Security on its classpath — its
  API authenticates itself inside the route. Closing the gap means introducing a filter chain in
  front of an API that already does its own auth. Until then, do not expose 8083 off the host.
- **A callback / webhook alternative** for callers that would rather not hold a connection.
- **Malware scanning of submitted documents.** The gateway checks that a payload decodes to
  something beginning `%PDF-` and is under the size limit. That is a check on the wrapper, not on
  the contents: a PDF can carry an exploit, and this one is written to a share and then opened by
  a bot driving a clinical desktop. An AV/sandbox pass belongs between the store and the dispatch.
- **A hard cap on request size at the edge.** `pdfBase64` is bounded by a constraint and by the
  decoded-size check, but both run after the body has been parsed into memory. A real limit needs
  a servlet filter or a reverse proxy in front.
- **Retention and encryption at rest for the document store.** The dated folders make a sweep
  straightforward to write, but nothing writes one, and nothing encrypts what is on the share
  (NFR-SEC-03).
