# Local protocol v1

The phone advertises `_okuyuki._tcp.` with its TCP port: 38765 when it is free, otherwise any free port. All routes except hello and pairing
require `Authorization: Bearer <256-bit random token>`. The phone explicitly approves each first pairing;
pending requests expire after two minutes. Photo IDs are MediaStore IDs, not file paths.

- `GET /v1/hello`: `Hello`; protocolVersion must equal 1. `protocolVersions` lists every version the phone speaks.
- `POST /v1/pair`: `PairRequest`; poll with the returned requestId until approved/rejected/expired.
  Both devices show the same four-digit code. Only the phone can approve. Approved response includes token.
  A request without requestId always starts a new request with its own code and replaces the same client's
  pending one; only the requestId reaches an existing request, so a known clientId never reveals its code or token.
- `GET /v1/library?after=<last-id>`: `LibraryPage`, ordered by takenAt descending and ID descending.
  Maximum 48 entries. `next` is the last ID when more entries remain.
- `GET /v1/items/{id}/thumb`: JPEG poster. Requested only for visible grid cells.
- `GET /v1/items/{id}/original`: original HEIC, single HTTP byte ranges including open/suffix intervals.
  Unsatisfiable or multiple ranges return 416; unknown IDs return 404.
- `WS /v1/events`: JSON `Event` (`library_changed`, `viewing`, `heartbeat`). Authentication is identical.

HTTP alpha transport is limited to a trusted local Wi-Fi network; TLS belongs to v1.0.
Photos never enter public storage on Quest. Pairing keys and settings may persist, photo bytes may not.

# Protocol v2 (LG webOS TV)

The TV app in [Okuyuki-webos](https://github.com/lingmulongtai/Okuyuki-webos) uses v2 on the same host; v1 is unchanged.
The route list, JSON shapes and events are defined in that repository's `protocol/README.md`. The fixtures in
`core/protocol/src/test/resources/fixtures/v2/` are identical copies and are decoded and encoded by `ProtocolV2Test`.

- A TV pairs through `POST /v1/pair` with `clientKind: "tv"`, then trades its token for a media key with
  `POST /v2/session`. Images, videos and the event socket of a TV browser cannot send a header, so the key goes in the
  path: `/v2/k/{key}/...`. Keys are 128-bit, last 12 hours, and stay valid when a newer one is opened; removing the TV
  or turning the host off invalidates them at once and closes its event socket with 1008.
- Every response allows any origin, answers the private-network preflight and is `Cache-Control: no-store`, because
  the TV app runs from `file://`.
- `still` is a two-frame, one-frame-per-second MP4 at the TV's video plane (at most 3840x2160), made with Media3
  Transformer: the photo fitted with black around it, HEVC when the TV decodes it, else H.264. Ultra HDR photos
  become HLG BT.2020 HEVC Main 10 stills for a TV that reports `hlg` and `hevc-main10`; a phone that cannot encode
  that falls back to SDR.
- `video` is the original file when the TV reports its codec, the container is MP4 or WebM, it fits the video plane
  and it averages at most 60 Mbps. Anything else is converted to HEVC or H.264 at most 4K, keeping HDR10 or HLG when
  the TV shows it. Stills and conversions run one at a time, are kept in the app cache up to 2 GB (least recently
  used first) and are announced with a `prepared` event.
- The phone's "テレビで見る" opens Okuyuki TV over LG's SSAP WebSocket (`wss://tv:3001`, `ws://tv:3000` only for a TV
  never seen over TLS), passing `{host, port, deviceId}` as launch parameters. The TV's self-signed certificate is
  pinned on first use and the TV's client key is encrypted with an Android Keystore key.
