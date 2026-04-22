# @beyondcodekarma/cap-silent-update

Self-hosted silent live updates for Capacitor apps. Zero cloud, SHA-256 integrity, trial + rollback. Android only today; iOS planned.

## Install

To use npm

```bash
npm install @beyondcodekarma/cap-silent-update
````

To use yarn

```bash
yarn add @beyondcodekarma/cap-silent-update
```

Sync native files

```bash
npx cap sync
```

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
