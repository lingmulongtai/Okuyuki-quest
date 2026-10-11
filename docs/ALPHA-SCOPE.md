# Alpha contract — specification v0.3, 2026-10-12

The first release implements phone MediaStore stereo-photo indexing, a foreground local-network host,
Quest discovery (with manual IP fallback), authenticated paginated browsing, original-file retrieval,
window stereo composition and disposable Quest caching. No cloud service or photo import is required.

The repositories are separate at the user's request. `core/protocol` and `core/stereo` are identical
versioned source snapshots in both repositories; protocol v1 must remain compatible.

Pairing approval protects library endpoints in alpha even though complete v1.0 pairing/TLS is later scope.
Alpha uses HTTP on a trusted private Wi-Fi network. It does not promise encryption or store publication.

## Physical acceptance (pending)
- Galaxy S25 Ultra: enumerate real Samsung HEIC photos, detect a new photo and a deleted photo.
- Quest 3S: discover Galaxy over Wi-Fi and hotspot; manually enter address when mDNS is blocked.
- Quest 3S: verify left/right eye identity, actual stereo composition, portrait orientation and decode latency.
- Verify clean shutdown, crash/restart cleanup and disk-cap eviction on Quest.

CI builds and synthetic parser tests do not satisfy these physical checks. The original HEIC from the
specification is not present in the workspace; embedded JPEG previews cannot validate HEIC decoding.

## Later scope
Immersive mode, TLS certificate pinning, remote selection, favorites/depth controls, tilt previews,
embedded cloud picker, slideshow, video and stores follow after hardware acceptance.
