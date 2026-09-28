# MahaMartAudit V5 — Implementation State

Last updated: 2026-09-28

## Repository
GitHub:
https://github.com/rajuuddanti/Audit-Sept_22.git

Branch:
main

Latest commit:
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

## Key source files

### ScanEntity.kt
Current ScanItem:
- id: Long, Room auto-generated primary key
- barcode: String
- quantity: Int
- rackNo: String
- date: String
- time: String
- deviceId: String = DEV01
- isSynced: Boolean = false
- isDeleted: Boolean = false

No schema change was made for today's deletion batching.

### DAO
Active scans:
SELECT * FROM scans WHERE isDeleted = 0 ORDER BY date ASC, time ASC, id ASC

Deleted scans:
SELECT * FROM scans WHERE isDeleted = 1 ORDER BY date ASC, time ASC, id ASC

Soft delete:
UPDATE scans SET isDeleted = 1 WHERE id = :id

Restore:
UPDATE scans SET isDeleted = 0 WHERE id = :id

Hard delete:
DELETE FROM scans WHERE id = :id

List operations exist for soft/hard delete, plus chunked helpers:
- softDeleteListChunked(ids)
- hardDeleteListChunked(ids)

Chunk size is 500 to avoid SQLite bind-variable limits.

### SyncManager.kt
Push-only synchronization remains.

Batch cloud deletion:
- DELETE_BATCH_SIZE = 25
- DELETE_MAX_RETRIES = 3
- exact composite identity filter:
  device_id + barcode + rack_no + date + time
- 15 second connect timeout
- 30 second read timeout
- Prefer: return=minimal
- retries for 408, 429, 5xx and exceptions
- retry delays include 500ms and 1000ms
- HTTP 2xx counts as success even if zero rows match, allowing local cleanup if cloud row is already absent

Methods:
- softDeleteScansFromCloud(items)
- hardDeleteScansFromCloud(items)

Single-record delete methods remain.

### HistoryActivity.kt
Current device ID:
SharedPreferences AuditPrefs -> DEVICE_ID, fallback DEV01.

Active History list is filtered to the current device and sorted:
newest -> oldest by full date/time, then id descending.

Delete behavior:
1. Send selected/clear-all records to Supabase in batches.
2. Soft-delete locally only for successfully processed cloud items.
3. Show success/failure counts.

### BinActivity.kt
Permanent delete behavior:
1. Send records to Supabase in batches.
2. Hard-delete locally for successfully processed cloud items.
3. Show success/failure counts.

Restore behavior remains sequential/background and is not part of the batch-delete change.

### MainActivity.kt
Important state:
- selectedSkuFromSearch
- tvSkuNamePreview
- isProcessingSave
- isUpdatingBarcodeFromScan

setupSkuPreviewLabel() dynamically places the item-name TextView below barcode.

lookupSkuName(cleanBarcode):
- queries db.scanDao().getMasterSkuByBarcode(cleanBarcode)
- source is master_skus only
- item name becomes #N/A when no master match
- uses a stale-result/current-barcode check before updating the UI
- displays Item: <name>
- green for a match, red for #N/A

Scanner process:
processScannedBarcode(cleanBarcode)
- clears selectedSkuFromSearch
- sets barcode programmatically under isUpdatingBarcodeFromScan
- locks scan input
- calls lookupSkuName(cleanBarcode)
- moves focus to Quantity

Search selection:
- selectedSkuFromSearch = skuCode
- etBarcode.setText(skuCode)
- lookupSkuName(skuCode)
- clears search/dropdown
- jumps to Quantity

Manual barcode fix:
The barcode TextWatcher now performs:
1. scanner guard check
2. empty-input preview clear
3. capture typed input
4. wait 250ms
5. read current barcode
6. if unchanged and non-empty, call lookupSkuName(currentInput)
7. retain CR/LF handling

This is the exact implementation of the latest confirmed fix.

## Existing project context docs
Before today's new handoff docs, PROJECT_CONTEXT contained:
- 09_SCANNER_FIX_APPLIED.md
- 10_NO_SCAN_HISTORY_IMPORT_PREVIEW.md
- 11_FINAL_SCANNER_PATH_FIX.md

Those documents should remain intact.

## Build status
No post-fix Android build has been verified in this environment.

## Deployment test checklist
- [ ] Pull latest main
- [ ] Clean/build Debug APK
- [ ] Install on Zebra TC22
- [ ] Scanner path
- [ ] Search path
- [ ] Manual barcode path
- [ ] Unknown SKU path
- [ ] History device filtering
- [ ] History delete
- [ ] Bin permanent delete
- [ ] Restore
