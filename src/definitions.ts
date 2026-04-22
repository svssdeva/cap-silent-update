import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Snapshot of the plugin's persisted state. Read this on every check to
 * decide whether to hit the manifest (throttling) or apply a pending
 * bundle.
 */
export interface UpdateState {
  /** Active bundle version, or the string `"factory"` when no OTA is live. */
  currentVersion: string;
  /** Staged-but-not-yet-promoted bundle, or empty string when none. */
  pendingVersion: string;
  /** Epoch ms of the most recent `checkManifest` call. `0` if never. */
  lastCheckTs: number;
  /** False during a trial boot (between `applyNow` and `notifyReady`). */
  confirmed: boolean;
}

/**
 * Result of `checkManifest`. Mirrors the server's manifest.json plus
 * the required gate fields.
 *
 * `signature` is reserved for a future ed25519 signing rollout. The
 * plugin currently accepts and ignores it — servers can ship it ahead
 * of verification landing without breaking older clients.
 */
export interface UpdateManifest {
  version: string;
  url: string;
  checksum: string;
  force: boolean;
  /** Semver-like string. Consumer decides how to compare. */
  min_native_version: string;
  signature?: string;
}

export interface DownloadUpdateOptions {
  url: string;
  version: string;
  checksum: string;
  /** See {@link UpdateManifest.signature}. Forward as-is; plugin no-ops. */
  signature?: string;
}

export interface DownloadUpdateResult {
  success: boolean;
  version: string;
}

/**
 * Stages emitted via the `updateProgress` event:
 * - `download` — bytes are streaming; `percent`, `bytesWritten`, `totalBytes` present
 * - `verify`   — SHA-256 check in progress
 * - `unzip`    — extracting the bundle
 * - `ready`    — staged + `serverBasePath` committed; `version` present
 */
export type UpdateStage = 'download' | 'verify' | 'unzip' | 'ready';

export interface UpdateProgressEvent {
  stage: UpdateStage;
  percent?: number;
  bytesWritten?: number;
  totalBytes?: number;
  version?: string;
}

export interface SilentUpdatePlugin {
  /** Snapshot the persisted OTA state. See {@link UpdateState}. */
  getState(): Promise<UpdateState>;

  /**
   * Override the persisted `lastCheckTs`. Useful after a successful
   * user-initiated check to throttle the next background one.
   */
  setLastCheckTs(options: { ts: number }): Promise<void>;

  /** Fetch and parse the manifest at `url`. */
  checkManifest(options: { url: string }): Promise<UpdateManifest>;

  /**
   * Download the bundle zip, verify SHA-256, extract to the app's private
   * storage, and stage it. The stage becomes the live bundle on the next
   * cold start (see `prepareBoot` in the Android plugin).
   */
  downloadUpdate(options: DownloadUpdateOptions): Promise<DownloadUpdateResult>;

  /**
   * Promote a staged bundle immediately in the running WebView. Used for
   * `force: true` manifests where a restart would be too disruptive.
   * Begins the trial window — JS must call `notifyReady` before the
   * next cold start to avoid rollback.
   */
  applyNow(): Promise<void>;

  /**
   * Mark the current bundle as stable. Call on every successful boot.
   * Failure to call before the next cold start is interpreted as a
   * crash and triggers a rollback to factory.
   */
  notifyReady(): Promise<void>;

  /**
   * Discard any staged bundle + revert to the factory `serverBasePath`.
   * Recreates the activity for immediate visual feedback on Android.
   */
  rollback(): Promise<void>;

  addListener(
    eventName: 'updateProgress',
    listenerFunc: (event: UpdateProgressEvent) => void,
  ): Promise<PluginListenerHandle>;
}
