# Harness V1 HTTP contract

Status: H1 contract, H2 reference adapter verified 2026-09-29. Request and
response payloads are defined by the named `#/$defs/<shape>` fragments in
`src/main/resources/schema/harness-v1.schema.json`. H3/H4 will consume the
manifest for generic registration and candidate generation. The current
reference manifest declares no safe operation mappings (`operations: []`).

## Scope and authority

The Harness is available only on an isolated `LOCAL` or `TEST` target. Its
endpoints are control and observation paths on the registered target origin.
The default paths below may be replaced by exact relative paths in an approved
Profile. Neither a manifest nor OpenAPI grants execution permission: ARL uses
their intersection with the human-approved Profile method/path and role
allowlist. Missing Harness support never disables unrelated read-only checks.
The adapter supplies observations and cleanup; target developers do not write a
Test Spec JSON or add business APIs for ARL.

Every Harness request includes `X-ARL-Harness-Key`,
`X-ARL-Run-Id` (a UUID), and `X-ARL-Harness-Version: 1`. The manifest
request uses a fresh diagnostic run ID. Responses include
`X-ARL-Harness-Version: 1` and `Content-Type: application/json`.
The key is a local/test secret and is never returned, persisted as evidence,
logged, or sent to a model. The adapter rejects missing or invalid credentials
before revealing run state. `runId` in successful responses must equal the
request header when the response has that field. JSON request bodies cannot
override the header's run scope.

Major version 1 is supported. A missing version header or an unsupported
major version returns HTTP 426 with `UNSUPPORTED_HARNESS_VERSION`; minor
additions must preserve V1 fields and meanings. The response `version` is
`"1.0"`. Unknown fields may be ignored by V1 clients but must not grant
authority. A client must reject an unsupported major version before acting on
any manifest capability.

| Request | Success | Required capability | Behavior |
| --- | --- | --- | --- |
| `GET /api/harness/manifest` | 200 manifest | none | Side effect free; declares `state`, `reset`, readiness kinds, fault types and safe operation mappings. No credentials or live user data. |
| `GET /api/harness/state` | 200 state | state | Side effect free run-scoped scalar observations. Fields named by a manifest mapping are at the top level, as in the current pilot. |
| `POST /api/harness/reset` | 200 reset | reset | Idempotently removes only fixtures, reservations and faults belonging to the header run. A second reset succeeds with zero removed. ARL independently verifies the resulting state before another write. |
| `GET /api/harness/readiness/{kind}/{id}` | 200 readiness | declared kind | Side effect free. `ready:false` with a safe reason is a pending result. ARL polls this GET every 200 ms for at most 10 s, then stops before the next business write. |
| `POST /api/harness/fault` | 201 fault injection | declared fault type | Accepts `runId`, `trialScope`, `faultType`, `scope`, `ttlMs`. Validates an allowed fault and bounded TTL; returns a run-owned `faultId`. |
| `POST /api/harness/fault/release` | 200 fault release | declared fault type | Accepts `runId` and `faultId`; releases only a handle belonging to this run. Repeat release is safe. ARL still verifies no active fault remains. |

For all POST bodies, `runId` must match the header. An absent optional
readiness kind or fault capability returns 404 `CAPABILITY_NOT_SUPPORTED` if
called. A missing resource returns 404 `RESOURCE_NOT_FOUND`. An existing
resource owned by a different run returns 409 `RUN_SCOPE_MISMATCH`, without
revealing that run's values. A malformed run ID, body, or request parameter
returns 400 `INVALID_REQUEST`. Unsupported fault type or TTL returns 422
`UNSUPPORTED_FAULT` or `INVALID_FAULT_TTL`. Invalid credentials return 401
`UNAUTHORIZED`. Reset or release that cannot verify cleanup returns 500
`RESET_FAILED` or `FAULT_RELEASE_FAILED`; it must never return a success
shape. Error bodies have only `version` and `error.code`/`error.message`
and exclude secrets, raw request/response bodies, stack traces and data from
other runs. The corresponding error schema is in the same JSON Schema file.

## Manifest fields

`capabilities.state` and `capabilities.reset` are booleans.
`capabilities.readinessKinds` and `capabilities.faultTypes` are arrays;
empty arrays mean unsupported. `operations` can be empty. Each operation
uses an exact HTTP method/path, optional OpenAPI `operationId`, role name,
an ARL-supported safe fixture recipe, response ID capture via JSON Pointer, observation
field names and expected scalar values, idempotency semantics, and optional
readiness kind. The adapter may publish recipes only for synthetic,
run-tagged fixtures. ARL must still validate those against OpenAPI and the
approved Profile, and must not infer missing request bodies or verdicts.

`fixtureRecipe` is an object with `kind: "SYNTHETIC_JSON_V1"` and a nonempty
`inputs` array. Each input names a unique JSON Pointer to an object leaf in
the business request body. V1 supports these sources:

| Source | Required fields | Value ARL constructs |
| --- | --- | --- |
| `RUN_TAGGED_STRING` | `pointer`, `prefix` | A synthetic string starting with the declared safe prefix and containing the current run ID and trial number. |
| `BOUNDED_INTEGER` | `pointer`, `value` | The declared integer from 0 through 10,000. The approved Profile and OpenAPI must also allow this value. |
| `RUN_CAPTURE` | `pointer`, `operationId`, `capture` | A scalar captured from a successful earlier operation in the same run; the referenced operation must declare that capture name and its response JSON Pointer. |

The adapter maps existing business fields to these sources; it does not supply
raw request bodies or arbitrary string values. A capture dependency must refer
to an operation with a stable `operationId`, must precede its consumer, and
must not form a cycle. ARL rejects duplicate input pointers, unresolved
captures, unsupported recipe kinds or sources, non-scalar captures, and any
required OpenAPI request field that this recipe cannot fill. ARL must also
verify the generated body against OpenAPI and the approved Profile before
offering a write candidate. Such an operation stays `NOT_READY` rather than
using a guessed value. For example, a product fixture can map `/name` to
`RUN_TAGGED_STRING` with prefix `arl-product`, `/stock` to
`BOUNDED_INTEGER` 10, and `/price` to `BOUNDED_INTEGER` 1000. A later order
can map `/productId` to `RUN_CAPTURE` from `createProduct.productId`.

## Candidate capability rules

The existing Eventful Commerce pilot is a compatibility path during H1/H2.
`availability` needs the approved health and catalog GETs and no Harness.
`product-create` needs state/reset and its `productCount` field. The
order, payment, idempotency and concurrency templates also need the exact
readiness GETs used by their setup steps. `payment-failure-recovery` alone
needs the fault inject/release pair and `PAYMENT_FAILURE`. The discovery
response reports each missing dependency on that candidate as `NOT_READY`;
other candidates remain independently eligible. Every write additionally
requires `LOCAL`/`TEST`, an enabled Profile, approved allowlisted calls and
roles, explicit execution confirmation, verified cleanup and failure
blocking. A declared endpoint is only a configuration check; the target's
actual HTTP behavior must pass conformance before being trusted.

## Adapter connection

Implement a local/test-only router with the paths and response shapes above.
Connect the router to three target-specific pieces: run-scoped state
collection, idempotent run cleanup, and (only for asynchronous flows) a
readiness predicate. Add fault injection and release only when fault testing
is wanted. Keep the key in the target's secret configuration, tag every
synthetic fixture with the run ID, bound faults by TTL, and reject cross-run
lookups or cleanup. Put the exact Harness paths and allowed business
operations into a Profile, then check a fresh manifest against OpenAPI and
the approved Profile before enabling writes. H1's local HTTP conformance tests
are protocol fixtures. H2 must run equivalent probes against its isolated
adapter in addition to the local tests. The default pilot Profile remains a
compatibility implementation until H3/H4.
