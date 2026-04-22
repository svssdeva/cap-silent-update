import { registerPlugin } from '@capacitor/core';

import type { SilentUpdatePlugin } from './definitions';

const SilentUpdate = registerPlugin<SilentUpdatePlugin>('SilentUpdate', {
  web: () => import('./web').then((m) => new m.SilentUpdateWeb()),
});

export * from './definitions';
export { SilentUpdate };
