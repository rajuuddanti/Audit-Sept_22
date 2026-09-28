# MahaMartAudit V5 — MASTER HANDOFF

## START HERE

Repository:
https://github.com/rajuuddanti/Audit-Sept_22.git

Branch:
main

Latest commit:
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

App:
MahaMartAudit V5

Package:
com.mahamart.mahamartaudit

Device:
Zebra TC22

## Current status
The latest source fixes a confirmed bug where manually entering a barcode did not trigger the item-name preview.

The source is pushed to GitHub.

The latest APK has NOT been built/verified in this environment.

The team is waiting for deployment, so the next action is build + TC22 testing, not more redesign.

## Confirmed manual-entry bug
Video:
User-provided video, duration about 38.23 seconds.

Observed:
- Search allam
- select ALLAM PASTE 1KG
- barcode becomes 8934
- item name appears
- clear/re-enter 8934 manually
- barcode remains correct
- item name is blank

Root cause:
Barcode TextWatcher handled scanner programmatic input and CR/LF scanner cases but did not perform master_skus lookup for ordinary manual barcode input.

## Latest fix
Commit:
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

Only MainActivity.kt changed for this latest fix.

Manual barcode input now:
- clears preview while editing
- waits 250ms
- verifies barcode has not changed
- calls lookupSkuName()
- lookupSkuName reads master_skus only
- stale result guard remains

Scanner path remains guarded by isUpdatingBarcodeFromScan and explicitly calls lookupSkuName().

## Core architecture
Device -> Room -> Supabase PUSH ONLY.

No Supabase -> Room scan import.

Item name:
barcode -> master_skus only.

Unknown SKU:
barcode = real EAN
sku_code = #N/A
item_name = #N/A

Cloud identity:
device_id + barcode + rack_no + date + time

## History / Bin
History:
- current device only
- newest -> oldest
- soft delete

Bin:
- restore
- permanent delete

Deletion:
- Supabase first
- local cleanup after successful cloud processing
- cloud batch size 25
- retries up to 3
- Room local delete chunks of 500

## Important source implementation
ScanItem fields:
id, barcode, quantity, rackNo, date, time, deviceId, isSynced, isDeleted.

History uses AuditPrefs DEVICE_ID, fallback DEV01.

MainActivity key state:
selectedSkuFromSearch
tvSkuNamePreview
isProcessingSave
isUpdatingBarcodeFromScan

lookupSkuName():
db.scanDao().getMasterSkuByBarcode(cleanBarcode)

## Recent commit history
d076382355bd430e133e9c90f7f0d25c99f82393 — SyncManager batched cloud deletion
d49eca88d3a8b86099c050aa4458c668e4390f76 — Room chunked delete helpers
36d40400bb9831f06f4eea7e4b87a708997b35fe — History batch-delete integration
d421b32e7e61f42e4a1a230f422731383d3e859f — Bin batch-delete integration
2ee1aa2d89dd5f8c3a0c57582f0cf28a7d12d3b1 — Bin goToHome restore
d1e464dbf8525b8048e104a3f6a45001b4d8ad0ad — History cleanup
90853e195176772e682fd53fe2eeae3608ea9c00 — SyncManager compile fix
b778caea8d47efabeb635d72d8b868e9cff02817 — SKU preview lifecycle/focus recovery
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a — manual barcode lookup fix

## Existing project context docs
- 09_SCANNER_FIX_APPLIED.md
- 10_NO_SCAN_HISTORY_IMPORT_PREVIEW.md
- 11_FINAL_SCANNER_PATH_FIX.md

## Rules for the next conversation
1. Read this file and the other PROJECT_CONTEXT docs before modifying source.
2. Do not assume an APK was built.
3. Do not redesign UI unless requested.
4. Do not reintroduce scan-history lookup into the scan-entry area.
5. Do not change the push-only architecture.
6. Do not add local duplicate logic.
7. Keep unknown barcode values intact.
8. Make focused commits so rollback is easy.
9. Test on TC22 before declaring deployment-ready.
10. If a requested change can affect stable behavior, explain the affected areas before applying it.

## Immediate next step
Build the latest main commit and test:
A. scanner
B. search
C. manual barcode
Then test deletion/history/bin behavior before full-scale deployment.
