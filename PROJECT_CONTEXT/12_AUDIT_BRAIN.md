# MahaMartAudit V5 — Project Brain / Current State

Last updated: 2026-09-28
Repository: rajuuddanti/Audit-Sept_22
Branch: main
Latest source commit: 4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

## Purpose
MahaMartAudit is the Android stock-audit app for Zebra TC22 devices. The current priority is deployment readiness and preserving the stable behavior while fixing only confirmed issues.

Package:
- com.mahamart.mahamartaudit

## Non-negotiable architecture
PUSH ONLY:
Device -> Room -> Supabase

There must be no Supabase -> Room scan-history import.

SKU/item-name lookup:
Barcode -> master_skus only

Never use scans, History, Recent Scans, or Supabase to determine the item name.

## Scan flow
Scanner:
Zebra scan -> barcode field -> master_skus lookup -> item name -> quantity -> Save -> Room -> Supabase PUSH

Search:
Search SKU -> select SKU -> barcode populated -> master_skus lookup -> item name -> quantity

Manual barcode:
Type barcode -> debounced master_skus lookup -> item name -> quantity

Unknown SKU:
barcode = actual scanned/entered EAN
sku_code = #N/A
item_name = #N/A

Do not replace an unknown barcode with #N/A.

## Data / duplicate rules
Cloud duplicate identity is:
device_id + barcode + rack_no + date + time

Do not add new local duplicate logic unless explicitly requested.

Multiple devices are supported. Device ID is stored with every scan.

## History
- Current device only.
- Sorted newest -> oldest using full date + time, with id as tie-breaker.
- History deletion is soft delete.
- Soft delete sets isDeleted=true; record remains in DB.
- Deleted records appear in Bin.
- History clear-all and selected-delete use batched Supabase deletion followed by local soft delete for successfully processed records.

## Bin
- Restore keeps the record and sets isDeleted=false.
- Permanent delete removes the record from Supabase and then Room.
- Permanent deletion is batched.
- Local large deletes are chunked to avoid SQLite bind-variable limits.

## UI / behavior that must remain stable
- No UI redesign.
- Recent Scans remains.
- Recent Scans must not populate the scan-entry preview.
- Old duplicate/history preview above Quantity is removed.
- Calibri text.
- Quantity max 4 digits.
- Scanning an EAN into Quantity gives an error and does not add that quantity.
- Search and scanner behavior should not be changed except where a confirmed bug requires it.

## Current known fix
The confirmed video bug was manual barcode entry not triggering item-name lookup. Search selection and scanner lookup worked, but manually re-entering the same barcode left the item name blank.

Commit 4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a fixes this by adding a 250ms delayed lookup in the barcode TextWatcher for ordinary manual input, while preserving the scanner programmatic-input guard.

## Current testing state
The source changes are pushed to GitHub. An APK has NOT been built in this environment after the latest patch.

Next test on TC22:
1. Zebra scanner -> verify item name.
2. Search -> verify item name.
3. Manual barcode entry -> verify item name.
4. Unknown barcode -> verify barcode remains actual EAN and name/SKU display #N/A.
5. History current-device filtering/sorting.
6. History delete and clear-all.
7. Bin restore and permanent delete.
8. Large delete behavior if test data permits.

## Safety rule for future changes
Do not modify unrelated files/features. Before changing anything, compare the requested behavior against this brain and the current source. If a change could affect stable behavior, state exactly what can be affected and keep the change reversible through Git commits.
