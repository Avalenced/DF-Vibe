# Web requests (WebResponse / WebRequest)

DF can make outbound HTTP calls from code. **Requires Overlord rank.**

- **`WebRequest`** (GAME ACTION) — fire-and-forget; sends a request, ignores the reply.
- **`WebResponse`** (SET VARIABLE) — sends a request and stores the reply in a dict var.
  Tags: `Request Method` (Get/Post/Put/Delete), `Content Type` (text/plain | application/json),
  `Code Flow` (Synchronous | Asynchronous). The result dict has keys **`status`** (HTTP code),
  **`statusText`**, and **`body`** — or **`json`** if the response is JSON (then `GetDictValue`
  into it). Example:
  `SetVar('WebResponse', var('r','local'), txt(url), tags={'Code Flow':'Synchronous','Request Method':'Get','Content Type':'application/json'})`.

## Hard limits (measured in-game, 2026-06)
- **Rate limit (the real wall):** DF allows only **~5 rapid requests, then HTTP 429**, refilling
  slowly (**~1 request per 2.5–3 s** sustained). It's request-**count** based — *compression
  does not relax it*. This forces a ~2.5–3 s latency floor on any polling loop.
- **Response size cap:** ~**600 rows / ~1 MB** per response; beyond that → **HTTP 413**.
- **Synchronous blocks** the code line for the full round-trip (a remote/tunnel RTT is ~90 ms+);
  use `Asynchronous` if you don't need the value inline.
- **gzip/base64 inbound is a dead end for big payloads.** DF *can* decode
  (`StringToBytes → Base64Decode → GzipDecompress → BytesToString → JsonToValue`, all
  Signed=True / UTF-8), but an intermediate text/byte-list size cap means only **~10 compressed
  rows** survive — far below the uncompressed ~600-row cap. See [[df-gzip-base64-decode]].

## When to use it / when NOT to
- **Good for:** occasional small calls — leaderboards, config/version fetch, Discord webhooks
  (`DiscordWebhook`), low-rate API lookups.
- **NOT for high-throughput / live streaming INTO a plot.** The rate cliff + size cap make it
  unusable for many-updates-per-second data. To push large/continuous data to a plot, use the
  **held-item custom-tag channel** instead: a client mod writes data into the held item's
  `hypercube:` tags and sends a creative-slot packet; the plot reads it locally with **Get All
  Item Custom Tags** on **Main Hand Item** — no HTTP, no 429, far higher throughput.
