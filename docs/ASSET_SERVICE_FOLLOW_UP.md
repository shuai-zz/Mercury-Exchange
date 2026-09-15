# AssetService Follow-up Improvements

## Status

Deferred until the remaining tutorial implementation is complete. These items should be addressed together when the full asset flow and its trusted call boundaries are visible.

Related review: [PR #33](https://github.com/shuai-zz/Mercury-Exchange/pull/33)

## 1. Do not expose mutable ledger maps

### Current behavior

- `getAssets(userId)` returns the actual per-user asset map.
- `getUserAssets()` returns the actual top-level ledger map.

### Risk

Callers can use operations such as `put`, `remove`, or `clear` to change ledger structure without going through `tryTransfer`. This can bypass balance checks and break asset-conservation or frozen-balance invariants.

The original tutorial uses the same design. Its known callers currently read these maps for serialization, tests, and integrity validation rather than mutating them, so this is not an observed functional bug in the tutorial flow. It remains an encapsulation and future-maintenance risk.

### Follow-up direction

- Return immutable snapshots or read-only views for query APIs.
- Replace `getUserAssets()` with purpose-specific read-only iteration, snapshot, or aggregation APIs.
- Ensure tests can verify global asset conservation without receiving a mutable reference to the internal ledger.

### Acceptance criteria

- [ ] External callers cannot add, replace, or remove ledger entries.
- [ ] Asset query and serialization behavior remains correct.
- [ ] Global conservation tests no longer depend on a mutable internal map.

## 2. Restrict unchecked transfers to the system debt account

### Current behavior

`tryTransfer(..., checkBalance)` skips source-balance validation whenever `checkBalance` is `false`. The Javadoc says this value should be used only for the system debt account, but the method does not enforce that rule.

### Risk

An internal caller can pass a normal user together with `checkBalance=false` and create a negative available or frozen balance. The ledger invariant therefore depends on every caller following a convention that is currently expressed only in documentation.

The original tutorial later derives the flag at the API boundary from `UserType.DEBT`, so the normal external flow does not accept an arbitrary unchecked-transfer flag. However, `AssetService` itself still exposes a permissive public API and can be misused by future internal code.

### Follow-up direction

- Prefer separate APIs: normal transfers always check balances, while issuance/deposit uses a narrowly scoped system-debt operation.
- If the boolean parameter is retained, enforce both the configured debt-account identity and the permitted transfer type when `checkBalance=false`.
- Do not hard-code the debt user ID before the shared `UserType`/configuration is introduced.

### Acceptance criteria

- [ ] A normal user cannot bypass balance checks.
- [ ] Only the system debt account can issue assets through the intended transfer path.
- [ ] Unchecked frozen-balance transfers are rejected.
- [ ] Tests cover authorized issuance and unauthorized bypass attempts.

## Suggested priority

Address the unchecked-transfer boundary first because it directly controls whether negative balances can be created. Then close the mutable-map exposure so callers cannot bypass the improved transfer API.
