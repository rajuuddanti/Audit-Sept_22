# MahaMartAudit V5 — Decisions and Rationale

Last updated: 2026-09-28

## Decision 1 — Keep push-only architecture
Decision:
Device -> Room -> Supabase only.

Reason:
The app is an audit capture client. Cloud scan history must not be imported back into Room or used to populate the active scan-entry UI.

## Decision 2 — Item name lookup uses master_skus only
Decision:
Barcode -> master_skus -> item name.

Reason:
Using scans/history as a fallback caused the scan-entry area to become dependent on previous audit records. Item identity must come from the SKU master.

## Decision 3 — Preserve actual barcode for unknown SKU
Decision:
Unknown SKU keeps the actual EAN in barcode and uses #N/A for SKU code/item name.

Reason:
The scanned EAN is the audit evidence and must not be destroyed or replaced with a placeholder.

## Decision 4 — Do not add local duplicate logic
Decision:
Cloud duplicate prevention remains based on:
device_id + barcode + rack_no + date + time

Reason:
The project already has a defined composite identity. Adding another local duplicate layer could change stable scan behavior and was not requested.

## Decision 5 — Batch cloud deletion
Decision:
History and Bin cloud deletion uses batches of 25 with retries.

Reason:
One HTTP request per record does not scale well for large selections. Batch requests reduce network overhead while retaining exact identity matching.

## Decision 6 — Chunk local Room deletion
Decision:
Large Room delete operations use chunks of 500 IDs.

Reason:
SQLite has bind-variable limits. Chunking prevents large selection operations from failing because too many parameters are passed in one statement.

## Decision 7 — Cloud deletion before local deletion
Decision:
For delete operations, cloud processing happens first. Local records are removed/soft-deleted only for successful cloud items.

Reason:
This avoids losing local records when a cloud operation actually fails.

## Decision 8 — HTTP 2xx with zero matching cloud rows counts as success
Decision:
A successful HTTP response is considered successful even if the exact cloud row is already absent.

Reason:
The desired final state is that the cloud record is absent. If it is already absent, local cleanup can proceed.

## Decision 9 — Keep scanner TextWatcher guarded
Decision:
Scanner programmatic barcode setText() remains protected by isUpdatingBarcodeFromScan.

Reason:
Hardware scanner input is already handled explicitly by processScannedBarcode(). The TextWatcher should not duplicate scanner work.

## Decision 10 — Manual barcode lookup is debounced
Decision:
Ordinary manual barcode typing waits 250ms before calling lookupSkuName.

Reason:
Without a delay, Room would be queried on every character. A short delay provides responsive lookup while avoiding unnecessary repeated queries.

## Decision 11 — No UI redesign for the fix
Decision:
Only the lookup behavior is changed.

Reason:
The reported issue was functional, not a layout problem. Changing the UI would increase regression risk immediately before deployment.

## Decision 12 — Git history is the rollback mechanism
Decision:
Every focused change is committed separately.

Important recent commits:
- d076382355bd430e133e9c90f7f0d25c99f82393 — SyncManager batched cloud deletion
- d49eca88d3a8b86099c050aa4458c668e4390f76 — Room chunked delete helpers
- 36d40400bb9831f06f4eea7e4b87a708997b35fe — History batch delete integration
- d421b32e7e61f42e4a1a230f422731383d3e859f — Bin batch delete integration
- 2ee1aa2d89dd5f8c3a0c57582f0cf28a7d12d3b1 — restore Bin goToHome
- d1e464dbf8525b8048e104a3f6a45001b4d8ad0ad — History cleanup
- 90853e195176772e682fd53fe2eeae3608ea9c00 — SyncManager Kotlin compile fix
- b778caea8d47efabeb635d72d8b868e9cff02817 — SKU preview lifecycle/focus recovery
- 4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a — manual barcode item-name lookup fix

No post-fix APK build has been verified yet.
