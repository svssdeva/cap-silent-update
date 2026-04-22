# @svssdeva/cap-silent-update

Self-hosted silent live updates for Capacitor apps. No accounts, no cloud vendor, no SDKs phoning home — you host the bundle on your own S3/CDN and the plugin installs it on the next cold boot. Integrity via SHA-256, trial + automatic rollback on crash.

## Status

| Platform | Status                                       |
| -------- | -------------------------------------------- |
| Android  | Working. Used in production.                 |
| iOS      | Plugin surface present, methods unimplemented. Planned. |
| Web      | Unimplemented — gate calls behind `Capacitor.isNativePlatform()`. |

## Why another update plugin?

| | This plugin | Capgo Live Updates | Capawesome Live Updates |
| --- | --- | --- | --- |
| Self-hosted | Yes | Optional (default: Capgo cloud) | Cloud only |
| Account required | None | Capgo | Capawesome |
| Runtime deps | Zero | `@capgo/capacitor-updater` | `@capawesome/capacitor-live-update` |
| Code size | ~15 kB minified | Larger | Larger |
| Signing | Scaffolded (ed25519 planned) | Bring-your-own | Cloud-managed |
| License | MIT | MIT | Commercial |

This plugin is deliberately small. You bring the hosting, the manifest format is one JSON object, and the only native dependency is Capacitor itself.

## Install

```bash
npm install @svssdeva/cap-silent-update
npx cap sync
```

### Android setup

Call `SilentUpdatePlugin.prepareBoot(this)` from your `MainActivity.onCreate` **before `super.onCreate(...)`**. This runs the cold-start state machine: promote a staged bundle to trial, or roll back a failed trial to factory.

```java
package com.example.myapp;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.svssdeva.silentupdate.SilentUpdatePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SilentUpdatePlugin.prepareBoot(this);
        super.onCreate(savedInstanceState);
    }
}
```

Capacitor auto-discovers the plugin via Gradle — no manual `registerPlugin(...)` call needed.

### iOS setup

Installing the pod keeps the JS surface stable. Every method currently rejects with `unimplemented`. Gate iOS calls defensively:

```ts
import { Capacitor } from '@capacitor/core';
import { SilentUpdate } from '@svssdeva/cap-silent-update';

if (Capacitor.getPlatform() === 'android') {
  await SilentUpdate.checkManifest({ url: '...' });
}
```

## Usage

```ts
import { SilentUpdate } from '@svssdeva/cap-silent-update';

// On every successful boot — MUST be called to confirm the trial bundle.
await SilentUpdate.notifyReady();

// Check for an update (cheap: single HTTP GET of your manifest).
const manifest = await SilentUpdate.checkManifest({
  url: 'https://cdn.example.com/ota/manifest.json',
});

const state = await SilentUpdate.getState();
if (manifest.version === state.currentVersion) return;

// Download + verify + stage. Takes effect on next cold start.
await SilentUpdate.downloadUpdate({
  url: manifest.url,
  version: manifest.version,
  checksum: manifest.checksum,
  signature: manifest.signature,
});

// If your manifest says force:true, apply without waiting for a restart.
if (manifest.force) {
  await SilentUpdate.applyNow();
}
```

### Progress events

The plugin emits a single `updateProgress` event with a `stage` discriminator. Use this to drive a progress bar on force-apply or user-initiated checks.

```ts
import type { UpdateProgressEvent } from '@svssdeva/cap-silent-update';

const handle = await SilentUpdate.addListener('updateProgress', (ev: UpdateProgressEvent) => {
  switch (ev.stage) {
    case 'download':
      console.log(`${ev.percent}% (${ev.bytesWritten}/${ev.totalBytes})`);
      break;
    case 'verify':
      console.log('Verifying checksum…');
      break;
    case 'unzip':
      console.log('Extracting…');
      break;
    case 'ready':
      console.log(`Bundle ${ev.version} staged.`);
      break;
  }
});

// Remember to detach.
await handle.remove();
```

Progress events are throttled to at most one every 200 ms (or every 1% change) for the `download` stage. `verify`, `unzip`, and `ready` are emitted once each.

## Manifest schema

The server-side manifest is one JSON object. The plugin only reads these fields:

```json
{
  "version": "1.2.0",
  "url": "https://cdn.example.com/ota/bundles/1.2.0.zip",
  "checksum": "a1b2c3…",
  "force": false,
  "min_native_version": "1.1",
  "signature": "optional-ed25519-hex-reserved-for-future-use"
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `version` | string | yes | Opaque to the plugin. Used to name the on-disk bundle dir. |
| `url` | string | yes | Absolute URL to the bundle zip. |
| `checksum` | string | yes | Hex-encoded SHA-256 of the zip bytes. |
| `force` | boolean | no | When `true`, consumer should call `applyNow()` after download. |
| `min_native_version` | string | no | Semver-ish. Consumer decides gating — plugin only passes it through. |
| `signature` | string | no | Reserved; plugin currently ignores. |

### Bundle layout

The zip must expand to a Capacitor-compatible webroot: an `index.html` at the zip root plus whatever assets it references. That's usually the output of your web build tool (`dist/`, `build/`, `www/`, etc.).

### Example upload script

A reference script used by the plugin author is at `examples/ota-push.sh` in the repo. It builds, zips, uploads to S3, and updates `manifest.json`. Plugin itself has no dependency on any specific storage — any public HTTPS endpoint works.

## Security model

**Integrity is enforced. Authenticity is not — yet.**

- The `checksum` field is a SHA-256 of the zip bytes, verified before extraction. This protects against accidental corruption (truncated download, CDN byte-flip) and against installing the wrong bundle.
- SHA-256 does **not** protect against a malicious actor who can write to your manifest URL. Such an attacker can publish a bundle with a matching checksum and the plugin will install it.
- Authenticity today is delegated to your bucket/origin access controls — only principals you authorize can update `manifest.json`, and TLS protects the manifest in transit.
- The JS surface and manifest schema already carry an optional `signature` field (ed25519, hex-encoded over the bundle bytes). The plugin accepts and currently ignores it. A future minor version will verify against a pinned public key before extraction. **Ship the signature field in your manifest today** — older clients will ignore it, newer clients will verify it, and the rollout is a server-side change.
- Zip-slip protection is in place: the extractor rejects entries whose canonical path escapes the bundle directory.

## Trial + rollback contract

A bundle goes through three states: `DOWNLOADED → TRIAL → CURRENT` (success) or `TRIAL → FACTORY` (failure).

1. After `downloadUpdate`, the bundle is **staged**. It is not live until the next cold start.
2. On the next cold start, `prepareBoot` promotes it to **trial** and clears the `confirmed` flag.
3. The trial bundle's JS must call `notifyReady()` within that boot.
4. If the process dies or the app is killed before `notifyReady`, the **next** `prepareBoot` detects an unconfirmed boot and rolls back to factory, clearing the bundle directory.

Calls to `applyNow()` short-circuit step 2 — they load the bundle in the running WebView and begin the trial immediately, without a restart.

Explicit `rollback()` discards any state and reverts to factory immediately.

## Upgrade path from a custom plugin

If you're migrating from an in-tree plugin that stored state under a different `SharedPreferences` namespace, the plugin performs a one-time migration from the legacy `"ota_prefs"` namespace (`ota_current_version`, `ota_pending_version`, `ota_pending_path`, `ota_confirmed`, `ota_last_check_ts`) into the new `"silentupdate_prefs"` namespace on the first `prepareBoot` after upgrade. Legacy keys are cleared after the copy.

This preserves an in-flight trial or staged bundle across the plugin swap. If your custom plugin used a different namespace, open an issue and a migration for your shape can be added.

## API

<docgen-index>

* [`getState()`](#getstate)
* [`setLastCheckTs(...)`](#setlastcheckts)
* [`checkManifest(...)`](#checkmanifest)
* [`downloadUpdate(...)`](#downloadupdate)
* [`applyNow()`](#applynow)
* [`notifyReady()`](#notifyready)
* [`rollback()`](#rollback)
* [`addListener('updateProgress', ...)`](#addlistenerupdateprogress-)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### getState()

```typescript
getState() => Promise<UpdateState>
```

Snapshot the persisted OTA state. See {@link <a href="#updatestate">UpdateState</a>}.

**Returns:** <code>Promise&lt;<a href="#updatestate">UpdateState</a>&gt;</code>

--------------------


### setLastCheckTs(...)

```typescript
setLastCheckTs(options: { ts: number; }) => Promise<void>
```

Override the persisted `lastCheckTs`. Useful after a successful
user-initiated check to throttle the next background one.

| Param         | Type                         |
| ------------- | ---------------------------- |
| **`options`** | <code>{ ts: number; }</code> |

--------------------


### checkManifest(...)

```typescript
checkManifest(options: { url: string; }) => Promise<UpdateManifest>
```

Fetch and parse the manifest at `url`.

| Param         | Type                          |
| ------------- | ----------------------------- |
| **`options`** | <code>{ url: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#updatemanifest">UpdateManifest</a>&gt;</code>

--------------------


### downloadUpdate(...)

```typescript
downloadUpdate(options: DownloadUpdateOptions) => Promise<DownloadUpdateResult>
```

Download the bundle zip, verify SHA-256, extract to the app's private
storage, and stage it. The stage becomes the live bundle on the next
cold start (see `prepareBoot` in the Android plugin).

| Param         | Type                                                                    |
| ------------- | ----------------------------------------------------------------------- |
| **`options`** | <code><a href="#downloadupdateoptions">DownloadUpdateOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#downloadupdateresult">DownloadUpdateResult</a>&gt;</code>

--------------------


### applyNow()

```typescript
applyNow() => Promise<void>
```

Promote a staged bundle immediately in the running WebView. Used for
`force: true` manifests where a restart would be too disruptive.
Begins the trial window — JS must call `notifyReady` before the
next cold start to avoid rollback.

--------------------


### notifyReady()

```typescript
notifyReady() => Promise<void>
```

Mark the current bundle as stable. Call on every successful boot.
Failure to call before the next cold start is interpreted as a
crash and triggers a rollback to factory.

--------------------


### rollback()

```typescript
rollback() => Promise<void>
```

Discard any staged bundle + revert to the factory `serverBasePath`.
Recreates the activity for immediate visual feedback on Android.

--------------------


### addListener('updateProgress', ...)

```typescript
addListener(eventName: 'updateProgress', listenerFunc: (event: UpdateProgressEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                    |
| ------------------ | --------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'updateProgress'</code>                                                           |
| **`listenerFunc`** | <code>(event: <a href="#updateprogressevent">UpdateProgressEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### Interfaces


#### UpdateState

Snapshot of the plugin's persisted state. Read this on every check to
decide whether to hit the manifest (throttling) or apply a pending
bundle.

| Prop                 | Type                 | Description                                                           |
| -------------------- | -------------------- | --------------------------------------------------------------------- |
| **`currentVersion`** | <code>string</code>  | Active bundle version, or the string `"factory"` when no OTA is live. |
| **`pendingVersion`** | <code>string</code>  | Staged-but-not-yet-promoted bundle, or empty string when none.        |
| **`lastCheckTs`**    | <code>number</code>  | Epoch ms of the most recent `checkManifest` call. `0` if never.       |
| **`confirmed`**      | <code>boolean</code> | False during a trial boot (between `applyNow` and `notifyReady`).     |


#### UpdateManifest

Result of `checkManifest`. Mirrors the server's manifest.json plus
the required gate fields.

`signature` is reserved for a future ed25519 signing rollout. The
plugin currently accepts and ignores it — servers can ship it ahead
of verification landing without breaking older clients.

| Prop                     | Type                 | Description                                          |
| ------------------------ | -------------------- | ---------------------------------------------------- |
| **`version`**            | <code>string</code>  |                                                      |
| **`url`**                | <code>string</code>  |                                                      |
| **`checksum`**           | <code>string</code>  |                                                      |
| **`force`**              | <code>boolean</code> |                                                      |
| **`min_native_version`** | <code>string</code>  | Semver-like string. Consumer decides how to compare. |
| **`signature`**          | <code>string</code>  |                                                      |


#### DownloadUpdateResult

| Prop          | Type                 |
| ------------- | -------------------- |
| **`success`** | <code>boolean</code> |
| **`version`** | <code>string</code>  |


#### DownloadUpdateOptions

| Prop            | Type                | Description                                                                                       |
| --------------- | ------------------- | ------------------------------------------------------------------------------------------------- |
| **`url`**       | <code>string</code> |                                                                                                   |
| **`version`**   | <code>string</code> |                                                                                                   |
| **`checksum`**  | <code>string</code> |                                                                                                   |
| **`signature`** | <code>string</code> | See {@link <a href="#updatemanifest">UpdateManifest.signature</a>}. Forward as-is; plugin no-ops. |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### UpdateProgressEvent

| Prop               | Type                                                |
| ------------------ | --------------------------------------------------- |
| **`stage`**        | <code><a href="#updatestage">UpdateStage</a></code> |
| **`percent`**      | <code>number</code>                                 |
| **`bytesWritten`** | <code>number</code>                                 |
| **`totalBytes`**   | <code>number</code>                                 |
| **`version`**      | <code>string</code>                                 |


### Type Aliases


#### UpdateStage

Stages emitted via the `updateProgress` event:
- `download` — bytes are streaming; `percent`, `bytesWritten`, `totalBytes` present
- `verify`   — SHA-256 check in progress
- `unzip`    — extracting the bundle
- `ready`    — staged + `serverBasePath` committed; `version` present

<code>'download' | 'verify' | 'unzip' | 'ready'</code>

</docgen-api>

## License

MIT. See [LICENSE](./LICENSE).
