# Final Scanner Path Fix

The current source already had the old history/duplicate preview removed from the scanner path.

This final change only adds a guard around the scanner's programmatic barcode `setText()` so the barcode TextWatcher cannot start extra work during a hardware scan.

Scanner path remains:

Scan → Barcode → Quantity → Save → Room → Supabase PUSH

Intentionally unchanged:
- Recent Scans
- Search
- #N/A behavior
- database duplicate restriction
- push-only architecture
- History/Bin delete behavior
- Calibri
- multi-device support
- quantity validation
- UI
