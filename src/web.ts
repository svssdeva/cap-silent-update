/* eslint-disable @typescript-eslint/no-unused-vars -- web stubs accept typed args for interface parity with the native implementations */
import { WebPlugin } from '@capacitor/core';

import type {
  DownloadUpdateOptions,
  DownloadUpdateResult,
  SilentUpdatePlugin,
  UpdateManifest,
  UpdateState,
} from './definitions';

/**
 * Web/PWA fallback. The plugin is native-only — browsers already have
 * their own cache + service-worker mechanisms for code updates. Every
 * method rejects with `this.unimplemented(...)`, which Capacitor surfaces
 * to callers as a standard unimplemented error.
 */
export class SilentUpdateWeb extends WebPlugin implements SilentUpdatePlugin {
  private static readonly MSG = 'SilentUpdate: web not supported. Gate calls behind `Capacitor.isNativePlatform()`.';

  async getState(): Promise<UpdateState> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }

  async setLastCheckTs(_options: { ts: number }): Promise<void> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }

  async checkManifest(_options: { url: string }): Promise<UpdateManifest> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }

  async downloadUpdate(_options: DownloadUpdateOptions): Promise<DownloadUpdateResult> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }

  async applyNow(): Promise<void> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }

  async notifyReady(): Promise<void> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }

  async rollback(): Promise<void> {
    throw this.unimplemented(SilentUpdateWeb.MSG);
  }
}
