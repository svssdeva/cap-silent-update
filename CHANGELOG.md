# Changelog

All notable changes to this project will be documented here.

## [Unreleased]

### Added
- Initial Android implementation of the silent live-update plugin (port from an in-tree Tithimala plugin).
- One-time prefs migration from the legacy `ota_prefs` namespace into `silentupdate_prefs` so apps upgrading from a custom in-tree plugin don't lose in-flight trials or staged bundles.
- `updateProgress` event with `stage` discriminator (`download` | `verify` | `unzip` | `ready`). Throttled to 200 ms / 1% on the download stage.
- iOS plugin surface with all 7 methods returning `unimplemented`, so the JS API is cross-platform and apps can adopt the plugin without branching.
- Web stub rejecting every method with `unimplemented`.
- Optional `signature` field accepted (and currently ignored) on `downloadUpdate` + surfaced through `checkManifest`. Reserved for ed25519 verification in a future minor.

### Known limitations
- No iOS implementation.
- No ed25519 signature verification (scaffolded; server-side opt-in).
- No cancel-in-progress API for active downloads.
- No delta / patch updates — full bundle each time.
