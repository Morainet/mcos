# mcos-server

Standalone, self-hosted sync endpoint for MCOS episodic memories. Implements
the `SyncBlobTransport` REST contract ([07-memory.md §11.0](../../docs/zh/07-memory.md))
against a backing store of **opaque blobs** — the server treats every blob as
opaque bytes and never parses, inspects or transforms payloads; decryption
happens only on the device. The one exception is the **enterprise-policy
management channel** (see below), which the server validates on upload so a
typo is refused with `400` instead of silently pushing clients into their
fail-closed restricted mode.

Zero third-party runtime dependencies (JDK `com.sun.net.httpserver` + NIO).

## REST contract

| Method | Path                  | Success | Errors                              |
|--------|-----------------------|---------|-------------------------------------|
| PUT    | `/blobs/{id}`         | 204     | 400 bad id · 401 no/bad token · 405 · 413 too large |
| GET    | `/blobs/{id}`         | 200     | 400 bad id · 401 · 404 not found · 405 |
| DELETE | `/blobs/{id}`         | 204     | 400 bad id · 401 · 405 (idempotent) |
| PUT    | `/enterprise/policy`  | 204     | 400 malformed policy · 401 · 405 · 413 too large |
| GET    | `/enterprise/policy`  | 200 JSON | 401 · 404 no policy stored · 405 |
| DELETE | `/enterprise/policy`  | 204     | 401 · 405 (idempotent)             |
| GET    | `/healthz`            | 200     | 405 (no auth required)              |

Blob ids must match `[A-Za-z0-9_-]{1,128}` (they double as file names — the
allowlist prevents path traversal). Blobs are capped at 16 MiB.

## Enterprise policy channel

`PUT|GET|DELETE /enterprise/policy` is the **"delivered via `mcos-server`"**
arm of enterprise-policy distribution ([08-security.md §13.3](../../docs/en/08-security.md)).
The server stores a single well-known `EnterprisePolicy` JSON document
(atomic write, survives restarts) behind the same mandatory Bearer auth as the
blob API:

- `PUT` validates the body with `EnterprisePolicy.parse` — malformed JSON or an
  unsupported schema version is refused with `400` (and a human-readable reason)
  at upload time, so operators learn about a bad policy immediately rather than
  discovering every client sitting in restricted mode after the next hourly fetch.
  Documents are capped at 1 MiB (`413` beyond).
- `GET` returns the stored document as `application/json` (`404` when none).
- `DELETE` removes it (idempotent).

**Client side:** the device pulls the document with `HttpEnterprisePolicySource`
(mcos-security) over an `EnterprisePolicyHttpTransport` seam; the default JVM
transport is `JdkEnterprisePolicyHttpTransport` (mcos-runtime-core,
`java.net.http`). Transport or parse failures are handled by the source's
fail-closed semantics: last good policy is kept, and with no cache the source
serves `FAIL_CLOSED` — never "no policy".

## Authentication

Authentication is **mandatory** and cannot be disabled. Clients send:

```
Authorization: Bearer <token>
```

The token is compared in constant time (`MessageDigest.isEqual`). Requests
without a valid token receive `401` plus a `WWW-Authenticate: Bearer` challenge.

## Run

```bash
# token via CLI flag or MCOS_SERVER_TOKEN env var (at least one is required)
# (gradlew in this repo has no exec bit — invoke it via `sh gradlew`)
MCOS_SERVER_TOKEN=change-me sh gradlew :mcos-server:run --args="--port 8787 --data-dir ./data/blobs"

# or a production-style fat jar
sh gradlew :mcos-server:installDist
./mcos-server/build/install/mcos-server/bin/mcos-server --port 8787 --data-dir /var/lib/mcos --token change-me
```

| Option      | Default         | Description                          |
|-------------|-----------------|--------------------------------------|
| `--port`    | `8787`          | HTTP port to bind                    |
| `--data-dir`| `data/blobs`    | Blob storage directory (created if missing) |
| `--token`   | env `MCOS_SERVER_TOKEN` | API token; blank → startup refuses |

Blobs are persisted to disk under `{data-dir}/blobs/{id}` and survive restarts
(atomic writes: tmp file + rename). Stop with Ctrl-C / SIGTERM.

> **TLS**: for production use, terminate TLS at a reverse proxy (Caddy,
> nginx) in front of this server. The token protects the blob API; TLS
> protects the token in transit (see 08-security.md "network eavesdropping").

## Client

The device side uses `JdkSyncBlobTransport(baseUrl, token = "...")` (or the
Android `HttpURLConnection` transport); see
`mcos-runtime-core/.../memory/MemorySyncClient.kt`.

## Tests

```bash
sh gradlew :mcos-server:test
```

The suite starts a live server and drives it with the **real** device-side
`JdkSyncBlobTransport`, covering authenticated round-trips, 401/404/405
semantics, idempotent delete, path-traversal hardening and restart
persistence. `PolicyEndpointTest` (EP1-EP10) covers the enterprise-policy
channel with the **real** client chain
(`HttpEnterprisePolicySource` + `JdkEnterprisePolicyHttpTransport`):
upload validation (400/413), auth, delete + idempotency, restart persistence,
and fail-closed client behaviour over real 401/404s plus hot policy refresh.
