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
