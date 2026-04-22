export interface SilentUpdatePlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
