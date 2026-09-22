# No Scan-History Preview / Push-Only Scanner

The feature that displayed previous scans for the same SKU immediately above Quantity has been removed from MainActivity.

The scanner path no longer queries Room for previous scans or builds a duplicate-history preview after each scan.

Current scan flow:

```text
Zebra scan
  -> barcode field
  -> quantity
  -> Save
  -> Room
  -> Supabase push
```

The Recent Scans section below the form remains a local UI list. It is not used to populate the scan-entry preview above Quantity.

This change is intentionally limited to the scan-entry preview/history feature.
