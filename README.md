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

* [`echo(...)`](#echo)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### echo(...)

```typescript
echo(options: { value: string; }) => Promise<{ value: string; }>
```

| Param         | Type                            |
| ------------- | ------------------------------- |
| **`options`** | <code>{ value: string; }</code> |

**Returns:** <code>Promise&lt;{ value: string; }&gt;</code>

--------------------

</docgen-api>
