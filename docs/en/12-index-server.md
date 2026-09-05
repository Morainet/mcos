# MCOS Public Index Server — REST Contract & Operations

> **Status:** P3 leftover closure — index hosting (10-roadmap §6, 09-marketplace §11.5).
> Implementation-status note (kept current as code lands): the machine-checkable contract is the
> executable interop suite in `mcos-index-server/src/test` (live server + the **real**
> `MarketplaceIndex`/`JdkMarketplaceHttpTransport` client) plus the `mcos-conformance` "market"
> suite that drives the shared review gate engine ([11-implementation-status.md](./11-implementation-status.md) item 52).
> An OpenAPI 3.1 `api/openapi.yaml` mirror (09 §11.5) is deferred until the endpoint surface
> stabilises past the first operational release — the JVM interop tests are the live contract.

## 1. Purpose

The public index server is the server half of the P3 marketplace. It hosts the
**index** (searchable package/recipe metadata), the **publisher review pipeline**
(submission → CI gates 1–11 → human decision), the **key registry** and the
**signed blocklist** that every client fetches.

This document is the contract that `mcos-index-server` (this repo, zero third-party
runtime dependencies) implements and that `MarketplaceIndex` (the shipped client,
`mcos-marketplace`) talks to. It mirrors the normative surface in
[09-marketplace.md](./09-marketplace.md) §5, §6, §11, §14 and fills the server-only
gaps (registry persistence, management API, key rotation runbook) that the client
document never needed to specify.

> **Where the blob sync server fits:** `mcos-server` implements the *memory sync*
> endpoint (07 §11). The index server is a separate process/role; they share only the
> self-hosted deployment posture (zero third-party deps, `com.sun.net.httpserver`,
> token auth, TLS at the reverse proxy). Run them as separate services.

## 2. Scope & honest boundaries (MVP)

In scope:

- Read-only discovery index (`/v1/plugins*`, `/v1/recipes*`, `/v1/publishers/{id}`).
- Publisher key registration / rotation / emergency revoke (09 §6.1, §6.3).
- Submission review pipeline: upload → CI gates 1–11 (09 §5.1) → decision.
- Signed blocklist distribution (09 §14.3) and revoked-key distribution (`/v1/keys/revoked`).
- Anonymous, opt-in install telemetry aggregation (09 §11.3) and abuse reports (09 §14.1).
- Management API for the marketplace operator (decisions, blocklist, unlist).

Out of scope (V1+, honest boundary — no placeholder endpoints that fake them):

- Transparency log (`/v1/transparency/sth`, 09 §6.4) — the MVP trusts the operator.
- Full human-review workbench UI; the MVP exposes the *decision* API for an external
  review tool. A submission that requires human review parks in `HUMAN_REVIEW` until an
  operator decision.
- Paid plugins, appeals self-service (`POST /v1/appeals` returns 405 in the MVP).
- Automatic AV scanning *engine*: the server ships a **pluggable scanner seam** plus a
  hash-denylist scanner. Wiring a real engine (e.g. ClamAV) is a deployment step
  (ops manual §8.3); an operator who runs without one sees gate 9 report `UNSCANNED`
  (warning, submissions route to human review — see §5.3).

## 3. Registry data model & persistence

The registry is a set of JSON documents under `--data-dir` (default `data/index`).
All writes are atomic (tmp file + rename) under a single in-process writer lock.
The server never in-memory-only mutates: every mutation first durably writes the
document, then serves the new state.

```
{data-dir}/
  registry.json          # publishers, keys, packages, submissions (full facts)
  recipes.json           # published workflow recipes (signed envelopes)
  blocklist.json         # entries + signature + document version
  revoked-keys.json      # PublisherKey list served at /v1/keys/revoked
  telemetry.ndjson       # append-only opt-in install events
  reports.ndjson         # append-only abuse reports
  audit.ndjson           # append-only operator/submission audit trail
```

Registry facts are richer than the public `PackageMetadata` (which is the **rendered
view**): approved submissions keep the full `PluginManifest` facts (command ids +
versions, locales, side-effect classes, namespace roots) so gates 5/10/11 have
first-published history, SemVer coupling and monotonicity inputs.

Seeding: on first boot with an empty registry the server initialises publishers whose
keys are listed in the `keys/` seed directory (see ops §8.2 — this is how the first
Ed25519 anchor from `TrustAnchors` is hosted: the server operator copies the same public
key the clients already pin).

## 4. Authentication model

Three credential classes. All comparisons are constant-time.

| Class | Credential | Serves | Header |
|-------|-----------|--------|--------|
| Public | none | All `GET` index endpoints, `/v1/reports` | — |
| Publisher | publisher token (server-issued, stored as HMAC-SHA256) | Its own key + submission endpoints | `Authorization: Bearer <publisherToken>` |
| Operator | admin token (`MCOS_INDEX_ADMIN_TOKEN`) | `/v1/admin/*` | `Authorization: Bearer <adminToken>` |

Token issuance is an operator action (ops §8.1). The server never stores plaintext
publisher tokens; it stores `sha256(token)` and compares hashes. A publisher can be
suspended by deleting its registry record (keys become REVOKED in `revoked-keys.json`).

## 5. Normative endpoints

All responses are JSON (UTF-8). Errors use the §11.4 envelope of
[09-marketplace.md](./09-marketplace.md); HTTP codes and `code` strings below map to
that table (`SCHEMA_VIOLATION`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`,
`ALREADY_EXISTS`, `RATE_LIMITED`, `INTERNAL`).

### 5.1 Read side (public)

| Method | Path | Behaviour |
|--------|------|-----------|
| `GET` | `/v1/plugins` | Search/list. Query params per 09 §11.1: `query`, `category` (`iot`,`media`,`productivity`,`developer`,`mcp`,`home`,`system`), `sort` (`relevance`/`safety`/`popularity`/`newest`), `page` ≥1, `pageSize` 1–100, `minRuntimeVersion` (SemVer filter). Returns `SearchResponse`. `relevance` uses the composite ranking of 09 §9.1 (server-side mirror of `SearchRanking`). |
| `GET` | `/v1/plugins/{packageId}` | Latest approved `PackageMetadata`, or 404 `NOT_FOUND`. |
| `GET` | `/v1/plugins/{packageId}/versions` | `List<PackageMetadata>`, newest first. |
| `GET` | `/v1/plugins/{packageId}/versions/{version}` | A specific version's metadata, or 404. |
| `GET` | `/v1/plugins/{packageId}/artifact` | 302 redirect to the artifact CDN URL recorded at approval. |
| `GET` | `/v1/plugins/by-command/{commandId}` | `List<PackageMetadata>` providing that command id (recommendations, 09 §9.2); empty list when none, **not** 404 (client treats 404 as empty too). |
| `GET` | `/v1/recipes` | Search recipes (`query`,`category`,`page`,`pageSize`) → `RecipeSearchResponse`. Only LISTED recipes. |
| `GET` | `/v1/recipes/{recipeId}` | Signed `RecipeEnvelope` or 404. |
| `GET` | `/v1/blocklist` | Signed blocklist (below). `Cache-Control` TTL 1h. |
| `GET` | `/v1/keys/revoked` | `List<PublisherKey>` with `status: REVOKED`. |
| `GET` | `/v1/publishers/{id}` | Publisher profile: id, name, published packages (latest metadata each), no keys. |

Only packages in `LISTED` (or its historical term, "published") state appear on the read
side. `CI_REJECTED`/`HUMAN_REVIEW`/`UNLISTED`/`REVOKED` states never surface here.

**`sort=safety` / `sort=popularity` fields** (`safetyScore`, `downloadCount`) are
server-computed at approval time and are not part of a submission (09 §11.2).

### 5.2 Write side — publisher

Publisher endpoints are scoped to `{id}`; the presented token must belong to that
publisher, else `PERMISSION_DENIED`.

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/v1/publishers/{id}/keys` | Register a signing key. Body: `PublisherKey` without `status` (server sets `ACTIVE`). Validates: keyId uniqueness, algorithm ∈ {`Ed25519`, `RSA-PSS-4096`}, fingerprint consistency (server recomputes SHA-256 of `publicKeyEncoded` and rejects a mismatch — the fingerprint is not self-declared). `409 ALREADY_EXISTS` on duplicate keyId. |
| `DELETE` | `/v1/publishers/{id}/keys/{keyId}` | **Routine rotation**: publisher marks its key replaced → key moves to `REVOKED`, recorded in `revoked-keys.json` with `rotatedFrom` history, and gate 8 checks against `ACTIVE` keys only. Old-key grace policy is a client-side TTL concern (09 §6.3). |
| `POST` | `/v1/publishers/{id}/plugins` | Submit a new plugin version. `multipart/form-data`: `artifact` (binary `.mcos`/`.aar`), `metadata` (JSON `PackageMetadata` without `downloadCount`/`safetyScore`), `signature` (JSON `{ "signingKeyId", "algorithm", "signature" }`, base64). Runs the CI gates synchronously (09 §5.1 SLA ceiling 15 min; MVP well under 1 min) and returns `{ submissionId, state, submittedAt, reviewReport }` where `reviewReport` is the §5.4-shaped gate report (see §5.4 below). States: `CI_REJECTED` (any gate error), `HUMAN_REVIEW` (warnings/trigger only), `APPROVED` (all green, no human trigger). |
| `GET` | `/v1/publishers/{id}/plugins/{packageId}/submissions/{submissionId}` | Poll a submission: current state + review report + decision timestamps. |
| `POST` | `/v1/publishers/{id}/plugins/{packageId}/submissions/{submissionId}/publish` | Publish an `APPROVED` submission → `LISTED` (09 §5.0, "publisher publishes"). `409` when the state is not `APPROVED`. |

**Submission collision rules (02 §4.4 / 09 §5.1 gates 3 + 10):**

- A submission for a `packageId` that already has a **newer** approved version is
  `ALREADY_EXISTS` (409) unless it carries a higher SemVer (update path).
- Duplicate `commandId` within one package: rejected by the reader (gate 3).
- Command id already claimed by another approved package: gate 10 error, first-published
  wins (unless the package declares a dependency on the claiming package — owned-command
  arbitration, see §5.3 gate 10).

**Approval semantics.** `APPROVED` is not yet visible on the read side: a `publish` call moves
state `APPROVED → LISTED` (09 §5.0). `HUMAN_REVIEW` waits for an operator *approve* (or
*reject*) decision (§5.4); an operator may also publish on the publisher's behalf.

### 5.3 Client / device write side

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/v1/reports` | Abuse report (09 §14.1). Body per `PluginReportRequest`. Returns `ReportAck { reportId }`. Stored append-only; ≥3 same-package reports in 7 days flag the package (audit line + submission visibility to operator). Reports are confidential to the publisher. |
| `POST` | `/v1/telemetry/install` | Opt-in install event (09 §11.3), body per `InstallTelemetryEvent`. Appended to `telemetry.ndjson`; used only to recompute `downloadCount`/popularity. The server logs and discards any event whose `anonymizedClientId` does not look like a SHA-256 hex digest (privacy hardening). |

### 5.4 Management side — operator (`/v1/admin/*`)

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/v1/admin/plugins/{packageId}/submissions/{submissionId}/approve` | `HUMAN_REVIEW` → `APPROVED` (reviewer decision; reviewer + note recorded). Idempotent no-op on `APPROVED`. |
| `POST` | `/v1/admin/plugins/{packageId}/submissions/{submissionId}/reject` | → `REJECTED` (human rejection, appealable). |
| `POST` | `/v1/admin/plugins/{packageId}/publish` | Operator publish on the publisher's behalf: latest `APPROVED` submission → `LISTED`. |
| `POST` | `/v1/admin/plugins/{packageId}/unlist` | → `UNLISTED` (abuse investigation); reversible by publishing the latest approved submission again. |
| `POST` | `/v1/admin/plugins/{packageId}/revoke` | → `REVOKED`, terminal; appends `BlocklistEntry(packageId, "*", PUBLISHER_BANNED | POLICY_VIOLATION | SECURITY_VULNERABILITY, …)` (reason chosen by operator), re-signs and bumps the blocklist. |
| `POST` | `/v1/admin/blocklist/entries` | Add/remove a `BlocklistEntry` (`MALWARE`, `LEGAL_TAKEDOWN`, etc.), bumps document `version` (monotonic), re-signs, writes `blocklist.json`. |
| `POST` | `/v1/admin/keys/{keyId}/emergency-revoke` | Emergency key revocation (compromise, 09 §6.3): key → `REVOKED` + appended to `revoked-keys.json`. |
| `GET` | `/v1/admin/registry` | Full registry snapshot (packages with facts, submissions, publishers) — for backup and the (future) review workbench. |
| `GET` | `/v1/admin/submissions?state=…` | Submission queue filtered by state (operator review inbox). |

Every operator action writes an `audit.ndjson` line: `{ at, actor, action, target, detail }`.
Audit is append-only and included in backups; operators cannot edit the past.

## 6. Gate pipeline (CI gates 1–11)

The pipeline is the **same engine** the `mcos-conformance` "market" suite drives
(`mcos-marketplace` review package), so "authors pass local validation → pass CI"
(09 §5.1) is structural, not aspirational.

| Gate | Check | Implemented by | Failure mode |
|------|-------|----------------|--------------|
| 1 | Manifest schema decodes (against 01 §10 JSON Schema) | `McosPackage.readPluginManifest` (zip `PK` gate, required fields, sideEffectClass enum) | error → `CI_REJECTED` |
| 2 | Reserved namespace `mcos.`/`sys.`/`mcp.`/`std.` | engine `NamespaceEnforcer` (shared) | error → `CI_REJECTED` |
| 3 | Duplicate command ids (in-package + across declared dependencies) | `readPluginManifest` (in-package fail-closed) + dependency scan | error → `CI_REJECTED` |
| 4 | sideEffectClass honesty heuristics (04 §13.2 check 4) | engine heuristics (static manifest facts) | **warning** only → routes to `HUMAN_REVIEW` |
| 5 | SemVer: regex + command-version coupling + monotonicity (04 §13.1) | engine (`VersionRange`, registry previous facts) | error → `CI_REJECTED` |
| 6 | i18n completeness (04 §12.1) | engine locale check | error → `CI_REJECTED` |
| 7 | Secret containment — no `{{secret.*}}`/`x-mcos-secret` in the artifact bytes | engine artifact scan | error → `CI_REJECTED` |
| 8 | Signature verification against registered ACTIVE key | `ArtifactVerifier` (security) over registered key | error → `CI_REJECTED` |
| 9 | Malware scan | pluggable `AvScanner` (hash-denylist scanner shipped; engine seam for real AV) | MALICIOUS → error + human flag; UNSCANNED → warning (no engine configured) |
| 10 | Namespace/command arbitration vs approved registry | engine (`registryState.existingCommandIds`) | error → `CI_REJECTED` (first-published wins) |
| 11 | `minRuntimeVersion` ≤ current runtime + monotonic vs previous release | engine (registry previous facts) | error → `CI_REJECTED` (targets future runtime) |

**Honest-boundary wording** (item 51 handover): gates 1/2/3/7 already have local
conformance coverage (`manifest` suite), gate 8 has `trust` suite coverage, gate 5's
SemVer primitives live in `VersionRange`. Item 52 (this work) lands the **marketplace-only**
gate logic (4/9/10/11 + full-report composition) as a *shared production engine* in
`mcos-marketplace`, adds the conformance `market` suite that drives it, and ships the
index server that runs the same engine.

**Report shape.** Gate output is the §5.4 JSON shape of
[09-marketplace.md](./09-marketplace.md): `{ overall, checks: [{ gate, rule, status,
severity, message, location }] }`. `overall` values: `CI_REJECTED` (any `severity:
"error"`), `HUMAN_REVIEW` (warnings only), `APPROVED` (green). Warnings never block but
escalate (09 §5.2).

## 7. Blocklist signing contract

`GET /v1/blocklist` returns a document whose `signature` the client verifies with the
well-known marketplace public key bundled in `TrustAnchors` (fingerprint-pinned). Signing
must therefore reproduce exactly the bytes the client verifies:

1. Canonical payload = the `Blocklist` document with `signature = null`, serialised the
   way `MarketplaceIndex.fetchBlocklist` serialises it before verification:
   `Json { ignoreUnknownKeys = true; explicitNulls = false }` with `encodeDefaults = false`
   (kotlinx default), field order = declaration order. `MarketplaceIndex` constructs that
   exact serializer instance (`blocklist.copy(signature = null)`).
2. The server signs those canonical bytes with the operator Ed25519 (or RSA-PSS-4096)
   private key and writes base64 into `signature`.
3. Document `version` is a monotonic string (server increments a counter per change).
4. Every blocklist mutation (admin add/remove, revoke) re-signs and bumps `version`.

An operator who changes the signing key MUST re-sign from the same canonical-encoding
routine; the interop tests pin this by fetching the blocklist with the real client and
asserting `BlocklistVerifier.verify` succeeds.

## 8. Operations manual

### 8.1 Run

```bash
# dev / CI
MCOS_INDEX_ADMIN_TOKEN=ops-secret sh gradlew :mcos-index-server:run \
  --args="--port 8877 --data-dir ./data/index --keys-dir ./data/index/keys"

# production-style
sh gradlew :mcos-index-server:installDist
./mcos-index-server/build/install/mcos-index-server/bin/mcos-index-server \
  --port 8877 --data-dir /var/lib/mcos-index --keys-dir /var/lib/mcos-index/keys \
  --admin-token "$(cat /run/secrets/mcos-admin-token)"
```

| Option | Default | Description |
|--------|---------|-------------|
| `--port` | `8877` | HTTP port. |
| `--bind-host` | `127.0.0.1` | Bind address. Loopback by default; `0.0.0.0` only behind a reverse proxy. |
| `--data-dir` | `data/index` | Registry persistence directory (created if missing). |
| `--keys-dir` | `<data-dir>/keys` | Seed dir for the operator PEM (`operator-private.pem` + `operator-public.pem`); absent private half ⇒ boots read-only, blocklist signing disabled. |
| `--admin-token` | env `MCOS_INDEX_ADMIN_TOKEN` | Operator token; blank → startup refuses (same posture as `mcos-server`). |

TLS terminates at a reverse proxy (Caddy/nginx); the Bearer tokens protect the API and
TLS protects tokens in transit.

### 8.2 Bootstrap & publisher onboarding

1. Operator starts the server with `--keys-dir` containing the marketplace/operator
   Ed25519 **public** key (the one pinned in `TrustAnchors` on devices).
2. Operator issues a publisher id + token (admin action, audit-logged) and shares the
   token out-of-band.
3. Publisher registers a signing key via `POST /v1/publishers/{id}/keys`; the public half
   of that key is what clients will cache (09 §6.2 step 2).
4. Publisher runs `:mcos-conformance:conformance` locally, then submits.

### 8.3 AV engine wiring (gate 9)

The shipped scanner is hash-denylist based: entries in `{data-dir}/av-denylist.txt`
(one `sha256` hex per line) → `MALICIOUS`. A deployment that wants real scanning drops a
script/binary behind the `AvScanner` seam (env `MCOS_AV_SCANNER_CMD`): the scanner
process receives the artifact path on stdin and prints `CLEAN`/`MALICIOUS`. With no
engine and no denylist hits, gate 9 reports `UNSCANNED` (warning) and submissions route
to human review — the MVP never silently claims a clean scan it did not perform.

### 8.4 Key rotation runbook

Derived from 09 §6.3. Two scenarios:

**Routine rotation (publisher-initiated).** Grace period keeps already-installed plugins
loading on their cached key for up to the client's revocation TTL (7 days) — the rotation
itself does not force-disable anything.

```bash
# 1. Publisher generates a new key pair (Ed25519, 32-byte seed → PKCS#8/X.509)
# 2. Register the NEW key first (stays ACTIVE alongside the old one):
curl -X POST https://market.example/v1/publishers/acme/keys \
  -H "Authorization: Bearer $PUB_TOKEN" \
  -d '{"keyId":"key_2026_09","publisherId":"acme",
       "publicKeyFingerprint":"<sha256 hex>","algorithm":"Ed25519",
       "publicKeyEncoded":"<base64 X.509>","createdAt":"2026-09-04T00:00:00Z"}'
# 3. Sign the next release with the new key and submit.
# 4. Once every shipped version of that publisher is re-signed (or 90 days pass),
#    rotate the old key to REVOKED:
curl -X DELETE https://market.example/v1/publishers/acme/keys/key_2026_01 \
  -H "Authorization: Bearer $PUB_TOKEN"
#    Old key now appears under GET /v1/keys/revoked with its rotatedFrom history.
```

**Emergency revoke (compromise or ban).** Operator-only; immediately visible to clients
on the next blocklist/revoked-keys poll (1h TTL) and force-disables matching plugins
(09 §14.4).

```bash
curl -X POST https://market.example/v1/admin/keys/key_2026_01/emergency-revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"reason":"key compromise reported"}'
# If the publisher is banned outright, revoke all their keys and blocklist all packages:
curl -X POST https://market.example/v1/admin/plugins/acme.telemetry/revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"reason":"PUBLISHER_BANNED","detailUrl":"https://status.example/post-42"}'
```

Operator key itself: generate Ed25519 (`ssh-keygen -t ed25519 -f mcos-operator` or JDK
`KeyPairGenerator`), keep the private key offline (HSM/secret store), publish the public
half via `--keys-dir`. When the operator key rotates, every installed client needs the new
anchor — that is a coordinated client release, not a server action.

### 8.5 Backup & restore

Stop → tar `{data-dir}` → verify with `GET /v1/admin/registry` on a restored copy. Restore
is all-or-nothing file restore; `audit.ndjson` and `telemetry.ndjson` restore along.
The blocklist document is derived state — on restore the server re-signs from the
`blocklist.json` entries using the configured operator key.

## 9. Interop & testing matrix

`mcos-index-server/src/test` starts the server on an ephemeral port and drives it with
the **real shipped client** (`MarketplaceIndex` + `JdkMarketplaceHttpTransport`):

| Area | Covered |
|------|---------|
| Discovery | search (query/category/sort/minRuntimeVersion/paging), by-command, package get (incl. 404→null), versions, recipe search/get |
| Trust endpoints | blocklist signature verifies with the real `BlocklistVerifier`; revoked keys round-trip |
| Report/telemetry | POST accepted; telemetry privacy validation (non-SHA256 id rejected) |
| Publish | register key (fingerprint mismatch rejected), submit → gate verdicts for a synthetic package (all-green → APPROVED; reserved namespace → gate 2 error; duplicate command → gate 3; future `minRuntimeVersion` → gate 11; AV denylist hit → gate 9), poll submission |
| Management | publish decision → package appears on read side; unlist → disappears; revoke → blocklist entry + client sees blocklisted |
| Auth | wrong/missing tokens → 401/403; admin endpoints reject publisher tokens |

`mcos-conformance` "market" suite drives the same engine without a server (author-side),
so local author validation and CI share one gate implementation.

## 10. Related

- Marketplace normative spec: [09-marketplace.md](./09-marketplace.md) §5/§6/§11/§14.
- Client module: `mcos-marketplace` (`MarketplaceIndex`, `BlocklistVerifier`).
- Device trust anchor: `TrustAnchors` in `mcos-android-sdk`.
- Implementation status: [11-implementation-status.md](./11-implementation-status.md).
