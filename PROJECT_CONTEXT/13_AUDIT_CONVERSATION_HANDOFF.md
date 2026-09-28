# MahaMartAudit V5 — Conversation Handoff

Last updated: 2026-09-28

## Why this file exists
This file records the important reasoning and conversation context needed to start a new ChatGPT conversation without losing the current state.

## Current situation
The team is waiting for the app to be deployed at full scale. The immediate task is to build and test the latest GitHub source on Zebra TC22.

The user explicitly wants practical, direct fixes and does not want unrelated changes.

## What happened today
We reviewed the changes made to the audit app and documented the work. The important investigation came from a user-provided video showing a barcode/item-name mismatch.

Initial interpretation:
- It looked like the item name disappeared after selecting a SKU from Search.

Detailed video trace showed the actual sequence:
1. Search for allam.
2. Select ALLAM PASTE 1KG.
3. Barcode becomes 8934.
4. Item name appears correctly.
5. Barcode is cleared/re-entered manually as 8934.
6. Barcode remains correct.
7. Item name does NOT appear.
8. Returning through the Search selection path makes the item name appear again.

Conclusion:
The confirmed bug was not the master lookup itself. The manual barcode TextWatcher path did not call lookupSkuName for ordinary typed input.

## Relevant source behavior
MainActivity already had:
- scanner programmatic-input guard: isUpdatingBarcodeFromScan
- selectedSkuFromSearch
- tvSkuNamePreview
- lookupSkuName()
- setupSkuPreviewLabel()
- jumpToQuantity()
- lifecycle refresh logic from the previous fix

lookupSkuName() queries master_skus only and has a stale-result guard so an older asynchronous lookup cannot overwrite a newer barcode.

## Latest fix
Commit:
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

Only MainActivity.kt was changed in this latest fix.

The barcode TextWatcher now:
- ignores scanner programmatic setText while isUpdatingBarcodeFromScan is true
- clears the preview when the barcode is empty
- waits 250ms after ordinary manual input stops changing
- verifies the current barcode still equals the captured input
- calls lookupSkuName(currentInput)
- keeps the existing CR/LF scanner handling

This prevents a Room query for every keystroke and keeps stale async results from overwriting newer input.

## Previous related fix
Commit:
b778caea8d47efabeb635d72d8b868e9cff02817

Added refreshSkuPreviewFromBarcodeIfNeeded() and lifecycle/focus recovery calls so the visible item-name preview can be rebuilt when the barcode remains but the preview UI was lost after lifecycle/focus changes.

## Earlier scanner/history work
The project had already removed the old scan-history/duplicate preview above Quantity.

The scanner should not query previous scans to populate the entry area. It only looks up the SKU name from master_skus.

## What was NOT changed by the latest manual-entry fix
- ScanEntity
- Room schema
- SyncManager deletion architecture
- History
- Bin
- Supabase push-only architecture
- duplicate identity
- Recent Scans
- scanner UI/layout
- search UI/layout
- quantity validation
- unknown SKU rule

## Important limitation
Do not claim the latest source has been built successfully. The latest source was pushed, but no Android build was run from this environment after the latest patch.

## Immediate next action
Pull main at:
4b1b4174a801a4f92326c4f4452ce2e2dcff2e3a

Build the APK locally, install on TC22, then run the three confirmed paths:
A. Scanner
B. Search
C. Manual barcode
