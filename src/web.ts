import { WebPlugin } from '@capacitor/core';

import type { SilentUpdatePlugin } from './definitions';

export class SilentUpdateWeb extends WebPlugin implements SilentUpdatePlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
