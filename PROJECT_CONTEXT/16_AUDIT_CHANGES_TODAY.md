# MahaMartAudit V5 — Changes Made Today

Date: 2026-09-28

## A. Deletion / scaling changes
1. Replaced one-by-one Supabase cloud deletion with batched deletion.
2. Batch size is 25 records.
3. Added up to 3 retries for transient HTTP failures/exceptions.
4. Exact cloud identity remains device_id + barcode + rack_no + date + time.
5. Added 15s connect timeout and 30s read timeout for batch deletion.
6. Added Prefer: return=minimal.
7. Treat HTTP 2xx as successful even when zero cloud rows match.
8. Added Room chunking at 500 IDs for large local soft/hard deletes.
9. Updated History selected-delete and clear-all to use batch cloud deletion.
10. Updated Bin permanent delete to use batch cloud deletion.
11. Restored Bin goToHome() after it was affected during editing.
12. Cleaned obsolete duplicate History comments.

## B. History behavior
1. History filters records to the current device ID from AuditPrefs/DEVICE_ID.
2. Fallback device ID is DEV01.
3. History is sorted newest -> oldest using full date/time and id tie-breaker.
4. Soft delete remains the mechanism for removing records from History.

## C. MainActivity / SKU preview
1. Added lifecycle/focus recovery for the SKU preview in commit b778cae.
2. Investigated the user-provided 38.23 second video.
3. Confirmed Search selection correctly shows item name.
4. Confirmed scanner path already performs master_skus lookup.
5. Confirmed the failure occurs when the barcode is manually typed/re-entered.
6. Added a 250ms delayed manual-input lookup in the barcode TextWatcher.
7. Kept scanner programmatic setText protected by isUpdatingBarcodeFromScan.
8. Kept existing lookupSkuName() stale-result guard.
9. Kept master_skus as the only item-name source.

## D. Deliberately unchanged
- ScanEntity schema
- Room schema/version
- Supabase push-only architecture
- composite duplicate identity
- Recent Scans
- scanner flow
- search UI
- UI layout
- quantity validation
- unknown SKU barcode preservation
- History/Bin overall design
- multi-device behavior

## E. Current source commits
Latest:
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

Parent:
b778caea8d47efabeb635d72d8b868e9cff02817

## F. Testing still required
The latest fix is source-only until tested on the real device.

Required:
1. Build APK.
2. Install on Zebra TC22.
3. Scanner -> item name.
4. Search -> item name.
5. Manual barcode -> item name.
6. Unknown barcode -> actual barcode + #N/A name/SKU.
7. History delete/clear.
8. Bin restore/permanent delete.
9. Multi-device current-device filtering.

Do not state that these tests passed until they are actually run.
