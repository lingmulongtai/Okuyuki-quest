# Local protocol v1

The phone advertises `_okuyuki._tcp.` with its dynamic TCP port. All routes except hello and pairing
require `Authorization: Bearer <256-bit random token>`. The phone explicitly approves each first pairing;
pending requests expire after two minutes. Photo IDs are MediaStore IDs, not file paths.

- `GET /v1/hello`: `Hello`; protocolVersion must equal 1.
- `POST /v1/pair`: `PairRequest`; poll with the returned requestId until approved/rejected/expired.
  Both devices show the same four-digit code. Only the phone can approve. Approved response includes token.
- `GET /v1/library?after=<last-id>`: `LibraryPage`, ordered by takenAt descending and ID descending.
  Maximum 48 entries. `next` is the last ID when more entries remain.
- `GET /v1/items/{id}/thumb`: JPEG poster. Requested only for visible grid cells.
- `GET /v1/items/{id}/original`: original HEIC, single HTTP byte ranges including open/suffix intervals.
  Unsatisfiable or multiple ranges return 416; unknown IDs return 404.
- `WS /v1/events`: JSON `Event` (`library_changed`, `viewing`, `heartbeat`). Authentication is identical.

HTTP alpha transport is limited to a trusted local Wi-Fi network; TLS belongs to v1.0.
Photos never enter public storage on Quest. Pairing keys and settings may persist, photo bytes may not.
