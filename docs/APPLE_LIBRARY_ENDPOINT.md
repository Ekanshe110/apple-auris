# Apple Music addon library endpoint

This document defines the Apple Music library protocol consumed by the addon client in
`app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt`.

Apple Auris does **not** call Apple's private web API and does **not** implement MusicKit.
Library access is available only through an external addon that implements this contract
and authenticates with a real Apple Music account.

## Manifest capability

Library support is optional. An addon that does not advertise it is not asked for library
routes.

```json
{
  "id": "apple-addon",
  "name": "Apple Addon",
  "version": "1.0.0",
  "resources": ["search", "stream"],
  "library": {
    "provider": "apple-music",
    "collections": ["playlists", "albums", "songs"],
    "authentication": {
      "type": "bearer",
      "header": "Authorization",
      "setting": "libraryToken",
      "required": true
    }
  }
}
```

- `library` is optional. If absent, library requests fail as unsupported.
- `provider` must be `apple-music` (case-insensitive).
- `collections` is an explicit list. Currently understood names are `playlists`, `albums`,
  and `songs`.
- `authentication` describes how the configured credential is presented:
  - `bearer`: `Authorization: Bearer <credential>` by default.
  - `basic`: `Authorization: Basic <credential>` by default.
  - `header`: the raw credential in the declared header.
  - `none`: no credential header.
- `header` defaults to `Authorization`. `required` defaults to `true`.
- `setting` names the credential in the addon's stored source settings. It defaults to
  `libraryToken`.

The library credential is supplied to the client separately and travels only in the
declared header. It must never appear in a query parameter. The existing addon base URL may
carry an addon-protocol token in its path; that token is separate from the library
credential and remains part of the configured addon URL.

## Collection routes

All routes are `GET` requests and return JSON.

| Collection | Route |
|---|---|
| Playlists | `/library/playlists` |
| Albums | `/library/albums` |
| Songs | `/library/songs` |

Tracks within a collection use:

| Collection | Route |
|---|---|
| Playlist tracks | `/library/playlists/{collectionId}/tracks` |
| Album tracks | `/library/albums/{collectionId}/tracks` |
| Song tracks | `/library/songs/{collectionId}/tracks` |

`collectionId` is one URL path segment. The client percent-encodes slashes, spaces, question
marks, ampersands, and other path-significant characters.

The client also sends the addon manifest's declared setting defaults, quality, and `atmos`
parameters on catalogue and stream requests. Library requests do not use those parameters,
because addon setting values may be sensitive.

## Pagination

Collection and collection-track requests use integer pagination parameters:

- `offset`: zero-based index of the first item; must be non-negative.
- `limit`: maximum number of items in the page; must be positive.

Example:

```http
GET /library/playlists?offset=50&limit=25
```

A page has this shape:

```json
{
  "items": [],
  "offset": 50,
  "limit": 25,
  "total": 120,
  "hasMore": true,
  "nextOffset": 75
}
```

- `items` is required by the contract but may be empty.
- `offset` and `limit` echo the requested range.
- `total` and `hasMore` are optional. `nextOffset` is optional and may be supplied as a
  convenience.
- A successful response with an empty body is treated as an empty page, not as fabricated
  library content.

## Library item

A collection page contains items of this shape:

```json
{
  "id": "p.library.playlist.123",
  "catalogId": "p.123",
  "streamId": "apple-stream-456",
  "title": "My Playlist",
  "artist": "Various Artists",
  "album": "Album",
  "artworkURL": "https://example.invalid/artwork.jpg",
  "duration": 245
}
```

Identifier semantics are distinct and must be preserved:

- `id` identifies the object in the user's library.
- `catalogId` is Apple's catalogue identifier when known.
- `streamId` is the identifier the addon accepts when opening a stream.

A client must not use one identifier in place of another. Metadata fields not needed by the
client may be omitted. `duration` is seconds.

## Errors

A non-2xx response should use a machine-readable JSON body:

```json
{
  "error": {
    "code": "signed_out",
    "message": "Apple Music sign-in required"
  }
}
```

A top-level `code` and `message` are also accepted:

```json
{
  "code": "signed_out",
  "message": "Apple Music sign-in required"
}
```

For `401` and `403`, the client exposes the body's code as
`AddonAuthenticationException.code`; for example, `signed_out`. If the response body does not
contain a code, the exception keeps a null code rather than inventing one.

Status handling:

- `401` or `403`: authentication failure; preserve a supplied machine-readable code.
- `404`: the requested item is not present.
- `429`: rate limited and retried according to the addon's `Retry-After` header.
- `5xx`: temporary addon unavailability.
- Other `4xx`: invalid request or unsupported operation.

A manifest without the Apple Music library capability is reported as
`AddonUnsupportedException`. This is distinct from an addon error or temporary server failure.

## Current limitation

This repository provides the addon-backed client protocol, wire models, and a dedicated Library
page. It does not include an Apple Music addon implementation, MusicKit integration, private
web-session scraping, a direct Apple sign-in flow, or fabricated library data.

Authenticated library access therefore requires all of the following:

1. An external addon implementing this document.
2. A configured addon endpoint that advertises the `apple-music` library capability.
3. A valid Apple Music account configured with that addon.

Until such an endpoint is configured and reachable, the Library page reports the addon as
unconfigured, signed out, empty, or failed rather than inventing account content.

## Ready-to-apply server patch / pseudocode

No addon server source exists in this repository. The following is an exact route map and a
drop-in Express/TypeScript implementation for the external addon. It deliberately separates
the client credential (`libraryToken`) from the linked slot's Apple credentials: the client
credential proves which linked slot may be used, while the server reads the Apple
`Music-User-Token` and `Developer-Token` from that slot's secret store.

Assumptions for the snippet:

- `resolveSlot(req)` maps the authenticated addon request to the linked account slot.
- `slot.apple.musicUserToken` and `slot.apple.developerToken` are server-side secrets.
- `appleFetch` is the server's authenticated Apple API client. It must send:
  - `Authorization: Bearer <developerToken>`
  - `Music-User-Token: <musicUserToken>`
  - `Origin: https://music.apple.com`
- Credentials are never accepted from query parameters and are never written to logs.

```ts
// apple-library-routes.ts
import { Router, type Request, type Response } from "express";

type Slot = {
  id: string;
  apple: {
    musicUserToken: string;
    developerToken: string;
  };
};

type ApplePage = {
  data?: Array<Record<string, unknown>> | Record<string, unknown>;
  next?: string;
  total?: number;
};

const COLLECTIONS = {
  playlists: "playlists",
  albums: "albums",
  songs: "songs",
} as const;

type Collection = keyof typeof COLLECTIONS;

function pageParams(req: Request): { offset: number; limit: number } {
  const offset = Number(req.query.offset ?? 0);
  const limit = Number(req.query.limit ?? 25);
  if (!Number.isInteger(offset) || offset < 0) {
    throw Object.assign(new Error("offset must be a non-negative integer"), { status: 400 });
  }
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw Object.assign(new Error("limit must be an integer from 1 to 100"), { status: 400 });
  }
  return { offset, limit };
}

function appleHeaders(slot: Slot): Record<string, string> {
  return {
    Authorization: `Bearer ${slot.apple.developerToken}`,
    "Music-User-Token": slot.apple.musicUserToken,
    Origin: "https://music.apple.com",
  };
}

// `appleFetch` must follow Apple's `next` URL and return one normalized page.
// Never append a client-provided URL without validating its origin.
async function fetchApplePage(
  slot: Slot,
  path: string,
  offset: number,
  limit: number,
): Promise<ApplePage> {
  const url = new URL(path, "https://amp-api.music.apple.com");
  url.searchParams.set("offset", String(offset));
  url.searchParams.set("limit", String(limit));
  const response = await fetch(url, { headers: appleHeaders(slot) });
  if (response.status === 401 || response.status === 403) {
    throw Object.assign(new Error("Apple Music sign-in required"), {
      status: response.status,
      code: "signed_out",
    });
  }
  if (response.status === 429) {
    throw Object.assign(new Error("Apple Music rate limit"), {
      status: 429,
      retryAfter: response.headers.get("retry-after") ?? undefined,
    });
  }
  if (!response.ok) {
    throw Object.assign(new Error(`Apple Music request failed (${response.status})`), {
      status: 502,
      code: "apple_upstream_error",
    });
  }
  return response.json() as Promise<ApplePage>;
}

function relationshipId(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) {
    throw Object.assign(new Error("Apple response omitted an identifier"), {
      status: 502,
      code: "invalid_upstream_response",
    });
  }
  return value;
}

function item(raw: Record<string, unknown>) {
  const attrs = (raw.attributes ?? {}) as Record<string, unknown>;
  const id = relationshipId(raw.id);
  const catalogId =
    typeof raw.catalogId === "string" && raw.catalogId ? raw.catalogId : id;
  const streamId =
    typeof raw.streamId === "string" && raw.streamId ? raw.streamId : catalogId;
  return {
    id,
    catalogId,
    streamId,
    title: typeof attrs.name === "string" ? attrs.name : "",
    artist:
      typeof attrs.artistName === "string"
        ? attrs.artistName
        : typeof attrs.artistDisplayName === "string"
          ? attrs.artistDisplayName
          : "",
    album: typeof attrs.albumName === "string" ? attrs.albumName : "",
    artworkURL:
      typeof attrs.artwork?.url === "string" ? attrs.artwork.url.replace("{w}", "600") : "",
    duration:
      typeof attrs.durationInMillis === "number"
        ? Math.round(attrs.durationInMillis / 1000)
        : undefined,
  };
}

function page(raw: ApplePage, offset: number, limit: number) {
  const items = (raw.data ?? []).map((entry) => item(entry));
  const hasMore = Boolean(raw.next);
  return {
    items,
    offset,
    limit,
    total: raw.total,
    hasMore,
    nextOffset: hasMore ? offset + items.length : null,
  };
}

export function createAppleLibraryRouter(resolveSlot: (req: Request) => Promise<Slot>) {
  const router = Router();

  const handle = (fn: (req: Request, res: Response) => Promise<void>) =>
    async (req: Request, res: Response) => {
      try {
        await fn(req, res);
      } catch (error: any) {
        const status = error.status === 429 ? 429 : error.status === 400 ? 400 : 502;
        const code = error.code ?? (status === 400 ? "invalid_request" : "apple_upstream_error");
        if (error.retryAfter) res.setHeader("Retry-After", error.retryAfter);
        res.status(status).json({
          error: { code, message: error.message ?? "Apple Music library request failed" },
        });
      }
    };

  for (const collection of Object.keys(COLLECTIONS) as Collection[]) {
    router.get(
      `/library/${collection}`,
      handle(async (req, res) => {
        const { offset, limit } = pageParams(req);
        const slot = await resolveSlot(req);
        const raw = await fetchApplePage(
          slot,
          `/v1/me/library/${COLLECTIONS[collection]}`,
          offset,
          limit,
        );
        res.json(page(raw, offset, limit));
      }),
    );

    router.get(
      `/library/${collection}/:id/tracks`,
      handle(async (req, res) => {
        const { offset, limit } = pageParams(req);
        const slot = await resolveSlot(req);
        const id = encodeURIComponent(req.params.id);
        const path =
          collection === "songs"
            ? // Apple's song-library response is the single song relationship.
              // The server still exposes the addon's uniform tracks contract.
              `/v1/me/library/songs/${id}`
            : `/v1/me/library/${COLLECTIONS[collection]}/${id}/tracks`;
        const raw = await fetchApplePage(slot, path, offset, limit);
        const normalized = collection === "songs"
          ? { ...raw, data: raw.data ? [raw.data] : [] }
          : raw;
        res.json(page(normalized, offset, limit));
      }),
    );
  }

  return router;
}
```

Mount the router behind the addon's existing authentication middleware:

```ts
app.use("/library", requireAddonSlot, createAppleLibraryRouter(resolveLinkedSlot));
```

### Server error mapping

| Upstream/server condition | HTTP response | Error code |
|---|---:|---|
| Missing or expired Apple user token | `401` | `signed_out` |
| Apple user token lacks account/library scope | `403` | `forbidden` |
| Apple rate limit | `429` plus `Retry-After` | `rate_limited` |
| Invalid offset/limit | `400` | `invalid_request` |
| Collection or item absent | `404` | `not_found` |
| Apple `5xx`, timeout, or malformed response | `502` | `apple_upstream_error` |
| Addon slot absent or unresolved | `401` | `signed_out` |

The server must distinguish `401/403` from a successful empty page. An empty `items` array is
only valid when Apple returned a valid collection page with no objects. It must never be used
as an error fallback.

### Required server tests

Apply these tests with the server patch. They prove the client-visible contract without an
Apple account:

1. Every collection route calls the matching Apple `/v1/me/library/...` path with the linked
   slot's two Apple headers.
2. Playlist, album, and song track routes percent-encode one path segment and never accept a
   second slash as part of the id.
3. `offset=0&limit=25` is forwarded; invalid offsets and limits return `400`.
4. Apple `next` produces `hasMore: true` and `nextOffset`; the final page produces `hasMore:
   false`.
5. `id`, `catalogId`, and `streamId` remain distinct in the response.
6. Apple `401`/`403` becomes `401`/`403` with `signed_out`/`forbidden`, never an empty page.
7. Apple `429` becomes `429` and preserves `Retry-After`.
8. Logs and response bodies contain neither the client credential nor either Apple token.
