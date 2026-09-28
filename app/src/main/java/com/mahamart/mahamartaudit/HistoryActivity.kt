package com.mahamart.mahamartaudit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var lvHistory: ListView
    private var scanList = mutableListOf<ScanItem>()

    // Register ActivityResultLauncher to handle native Android Save Document picker
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                writeCsvToUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        db = AppDatabase.getDatabase(this)
        lvHistory = findViewById(R.id.lvHistory)

        // Enable multi-selection mode
        lvHistory.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // Cloud Sync Button
        findViewById<ImageButton>(R.id.btnSyncCloud)?.setOnClickListener {
            val syncBtn = it as ImageButton
            syncBtn.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                SyncManager.syncUnsyncedData(
                    this@HistoryActivity,
                    db,
                    isManual = true
                )

                withContext(Dispatchers.Main) {
                    syncBtn.isEnabled = true
                    loadActiveScans()
                }
            }
        }

        // Delete Button (Handles Selected items OR Clear All)
        findViewById<ImageButton>(R.id.btnClearAll)?.setOnClickListener {
            if (scanList.isEmpty()) {
                Toast.makeText(
                    this,
                    "History is already empty",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val selectedItems = getSelectedItems()

            if (selectedItems.isNotEmpty()) {
                confirmMoveSelectedToBin(selectedItems)
            } else {
                confirmClearAllToBin()
            }
        }

        // Export Button showing Popup Menu with customized text colors
        findViewById<ImageButton>(R.id.btnExportCsv)?.setOnClickListener { view ->
            if (scanList.isEmpty()) {
                Toast.makeText(
                    this,
                    "No scans available to export",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            showExportPopupMenu(view)
        }

        loadActiveScans()
    }

    private fun showExportPopupMenu(view: View) {

        // Off-white light theme wrapper for popup menu
        val contextThemeWrapper = ContextThemeWrapper(
            this,
            androidx.appcompat.R.style.Theme_AppCompat_Light
        )

        val popup = PopupMenu(contextThemeWrapper, view)

        // 1. "Save CSV file" with Green text (#2E7D32)
        val saveTitle = SpannableString("Save CSV file").apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor("#2E7D32")),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. "Share CSV file" with Red text (#D32F2F)
        val shareTitle = SpannableString("Share CSV file").apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor("#D32F2F")),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        popup.menu.add(0, 1, 0, saveTitle)
        popup.menu.add(0, 2, 1, shareTitle)

        popup.setOnMenuItemClickListener { item ->

            when (item.itemId) {

                1 -> {
                    openFilePickerToSave()
                    true
                }

                2 -> {
                    exportAndSendEmail()
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    // ============================================================
    // SAVE CSV FILE
    // ============================================================

    private fun openFilePickerToSave() {

        val prefs = getSharedPreferences(
            "AuditPrefs",
            Context.MODE_PRIVATE
        )

        val storeName = prefs.getString(
            "STORE_NAME",
            "Mahalaxmi Mahamart"
        ) ?: "Mahalaxmi Mahamart"

        val deviceId = prefs.getString(
            "DEVICE_ID",
            "DEV01"
        ) ?: "DEV01"

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(Date())

        val defaultFileName =
            "${storeName.replace(" ", "_")}_${deviceId}_scans_${timestamp}.csv"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, defaultFileName)
        }

        createDocumentLauncher.launch(intent)
    }

    private fun writeCsvToUri(uri: Uri) {

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val prefs = getSharedPreferences(
                    "AuditPrefs",
                    Context.MODE_PRIVATE
                )

                val storeName = prefs.getString(
                    "STORE_NAME",
                    "Mahalaxmi Mahamart"
                ) ?: "Mahalaxmi Mahamart"

                val deviceId = prefs.getString(
                    "DEVICE_ID",
                    "DEV01"
                ) ?: "DEV01"

                val currentDate = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())

                contentResolver.openOutputStream(uri)?.use { outputStream ->

                    val writer = OutputStreamWriter(outputStream)

                    writer.append("Store Name: $storeName\n")
                    writer.append("Device ID: $deviceId\n")
                    writer.append("Export Date: $currentDate\n\n")
                    writer.append(
                        "Barcode,SkuCode,Item Name,Quantity,RackNo,Date,Time\n"
                    )

                    scanList.forEach { item ->

                        val master =
                            db.scanDao().getMasterSkuByBarcode(item.barcode)

                        val itemName =
                            master?.name?.ifEmpty { "#N/A" } ?: "#N/A"

                        // IMPORTANT:
                        // Unknown barcode = #N/A, NOT scanned barcode.
                        val skuCode =
                            db.scanDao()
                                .getPrimarySkuCodeByBarcode(item.barcode)
                                ?: "#N/A"

                        writer.append(
                            "\"${item.barcode}\"," +
                                    "\"$skuCode\"," +
                                    "\"$itemName\"," +
                                    "${item.quantity}," +
                                    "\"${item.rackNo.ifEmpty { "-" }}\"," +
                                    "\"${item.date}\"," +
                                    "\"${item.time}\"\n"
                        )
                    }

                    writer.flush()
                }

                withContext(Dispatchers.Main) {

                    Toast.makeText(
                        this@HistoryActivity,
                        "File saved successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {

                    Toast.makeText(
                        this@HistoryActivity,
                        "Save Error: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // SHARE CSV FILE
    // ============================================================

    private fun exportAndSendEmail() {

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val prefs = getSharedPreferences(
                    "AuditPrefs",
                    Context.MODE_PRIVATE
                )

                val storeName = prefs.getString(
                    "STORE_NAME",
                    "Mahalaxmi Mahamart"
                ) ?: "Mahalaxmi Mahamart"

                val deviceId = prefs.getString(
                    "DEVICE_ID",
                    "DEV01"
                ) ?: "DEV01"

                val currentDate = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())

                val timestamp = SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.getDefault()
                ).format(Date())

                val fileName =
                    "${storeName.replace(" ", "_")}_${deviceId}_scans_${timestamp}.csv"

                val shareFile = File(
                    cacheDir,
                    fileName
                )

                val writer = FileWriter(shareFile)

                writer.append("Store Name: $storeName\n")
                writer.append("Device ID: $deviceId\n")
                writer.append("Export Date: $currentDate\n\n")
                writer.append(
                    "Barcode,SkuCode,Item Name,Quantity,RackNo,Date,Time\n"
                )

                scanList.forEach { item ->

                    val master =
                        db.scanDao().getMasterSkuByBarcode(item.barcode)

                    val itemName =
                        master?.name?.ifEmpty { "#N/A" } ?: "#N/A"

                    // IMPORTANT:
                    // Unknown barcode = #N/A, NOT scanned barcode.
                    val skuCode =
                        db.scanDao()
                            .getPrimarySkuCodeByBarcode(item.barcode)
                            ?: "#N/A"

                    writer.append(
                        "\"${item.barcode}\"," +
                                "\"$skuCode\"," +
                                "\"$itemName\"," +
                                "${item.quantity}," +
                                "\"${item.rackNo.ifEmpty { "-" }}\"," +
                                "\"${item.date}\"," +
                                "\"${item.time}\"\n"
                    )
                }

                writer.flush()
                writer.close()

                val fileUri = FileProvider.getUriForFile(
                    this@HistoryActivity,
                    "$packageName.provider",
                    shareFile
                )

                val emailIntent = Intent(Intent.ACTION_SEND).apply {

                    type = "text/csv"

                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "Stock Audit Report - $storeName ($deviceId)"
                    )

                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Attached is the latest stock audit scan CSV file.\n\n" +
                                "Store: $storeName\n" +
                                "Device: $deviceId\n" +
                                "Date: $currentDate"
                    )

                    putExtra(
                        Intent.EXTRA_STREAM,
                        fileUri
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                withContext(Dispatchers.Main) {

                    startActivity(
                        Intent.createChooser(
                            emailIntent,
                            "Share CSV File Via..."
                        )
                    )
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {

                    Toast.makeText(
                        this@HistoryActivity,
                        "Error: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // SELECTED ITEMS
    // ============================================================

    private fun getSelectedItems(): List<ScanItem> {

        val checkedPositions =
            lvHistory.checkedItemPositions

        val selected = mutableListOf<ScanItem>()

        for (i in 0 until scanList.size) {

            if (checkedPositions.get(i)) {
                selected.add(scanList[i])
            }
        }

        return selected
    }

    // ============================================================
    // DATE + TIME SORTING
    // ============================================================

    private fun scanDateTimeMillis(scan: ScanItem): Long {

        val candidates = listOf(
            "yyyy-MM-dd hh:mm:ss a",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (pattern in candidates) {

            try {

                val formatter =
                    SimpleDateFormat(
                        pattern,
                        Locale.US
                    ).apply {
                        isLenient = false
                    }

                val parsed =
                    formatter.parse(
                        "${scan.date} ${scan.time}"
                    )

                if (parsed != null) {
                    return parsed.time
                }

            } catch (_: Exception) {
                // Try next supported format.
            }
        }

        // Keep malformed/legacy records at the bottom.
        return Long.MIN_VALUE
    }

    // ============================================================
    // LOAD ACTIVE SCANS
    // ============================================================

    private fun loadActiveScans() {

        lifecycleScope.launch(Dispatchers.IO) {

            // HISTORY:
            // Newest scan first, oldest scan last.
            //
            // Do NOT compare stored 12-hour time strings
            // lexicographically.

            val myDeviceId =
                getSharedPreferences(
                    "AuditPrefs",
                    Context.MODE_PRIVATE
                )
                    .getString(
                        "DEVICE_ID",
                        "DEV01"
                    )
                    ?.trim()
                    .takeUnless {
                        it.isNullOrEmpty()
                    }
                    ?: "DEV01"

            scanList =
                db.scanDao()
                    .getAllActiveScans()
                    .filter {
                        it.deviceId.equals(
                            myDeviceId,
                            ignoreCase = true
                        )
                    }
                    .sortedWith(
                        compareByDescending<ScanItem> {
                            scanDateTimeMillis(it)
                        }.thenByDescending {
                            it.id
                        }
                    )
                    .toMutableList()

            val displayStrings =
                scanList.map { scan ->

                    val master =
                        db.scanDao()
                            .getMasterSkuByBarcode(
                                scan.barcode
                            )

                    val itemName =
                        master?.name?.ifEmpty {
                            "#N/A"
                        } ?: "#N/A"

                    "${scan.barcode} ($itemName)\n" +
                            "Qty: ${scan.quantity} | " +
                            "Rack: ${scan.rackNo.ifEmpty { "-" }} | " +
                            "${scan.date} ${scan.time}"
                }

            withContext(Dispatchers.Main) {

                val tvTotalCount =
                    findViewById<TextView?>(
                        R.id.tvTotalCount
                    )

                if (tvTotalCount != null) {

                    tvTotalCount.text =
                        "Total Scans: ${scanList.size}"

                } else {

                    supportActionBar?.title =
                        "Scan History (${scanList.size})"
                }

                val adapter =
                    object : ArrayAdapter<String>(
                        this@HistoryActivity,
                        R.layout.list_item_checkbox,
                        displayStrings
                    ) {

                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: ViewGroup
                        ): View {

                            val view =
                                super.getView(
                                    position,
                                    convertView,
                                    parent
                                ) as TextView

                            view.setTextColor(
                                Color.parseColor("#111111")
                            )

                            // Calibri
                            view.typeface =
                                androidx.core.content.res
                                    .ResourcesCompat
                                    .getFont(
                                        this@HistoryActivity,
                                        R.font.calibri_regular
                                    )

                            view.textSize = 13f

                            return view
                        }
                    }

                lvHistory.adapter = adapter
                lvHistory.clearChoices()
            }
        }
    }

    // ============================================================
    // MOVE SELECTED TO BIN
    // ============================================================

    private fun confirmMoveSelectedToBin(
        items: List<ScanItem>
    ) {

        AlertDialog.Builder(this)
            .setTitle("Move Selected to Bin")
            .setMessage(
                "Move \${items.size} selected item(s) to Recycle Bin?"
            )
            .setPositiveButton("Move Selected") { _, _ ->

                lifecycleScope.launch(Dispatchers.IO) {

                    // Process the exact selected identities in cloud batches.
                    val successfulItems =
                        SyncManager.softDeleteScansFromCloud(items)

                    if (successfulItems.isNotEmpty()) {
                        db.scanDao().softDeleteListChunked(
                            successfulItems.map { it.id }
                        )
                    }

                    val failedCount =
                        items.size - successfulItems.size

                    withContext(Dispatchers.Main) {

                        val message =
                            if (failedCount == 0) {
                                "\${successfulItems.size} item(s) moved to Bin"
                            } else {
                                "\${successfulItems.size} moved to Bin, \${failedCount} cloud update(s) failed. Failed item(s) remain in History."
                            }

                        Toast.makeText(
                            this@HistoryActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()

                        loadActiveScans()
                    }
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    // ============================================================
    // MOVE ALL TO BIN
    // ============================================================

    private fun confirmClearAllToBin() {

        AlertDialog.Builder(this)
            .setTitle("Move All to Bin")
            .setMessage(
                "Do you want to move all active scan records to the Recycle Bin?"
            )
            .setPositiveButton("Move All") { _, _ ->

                lifecycleScope.launch(Dispatchers.IO) {

                    val itemsToProcess = scanList.toList()

                    val successfulItems =
                        SyncManager.softDeleteScansFromCloud(itemsToProcess)

                    if (successfulItems.isNotEmpty()) {
                        db.scanDao().softDeleteListChunked(
                            successfulItems.map { it.id }
                        )
                    }

                    val failedCount =
                        itemsToProcess.size - successfulItems.size

                    withContext(Dispatchers.Main) {

                        val message =
                            if (failedCount == 0) {
                                "All \${successfulItems.size} records moved to Recycle Bin"
                            } else {
                                "\${successfulItems.size} moved to Bin, \${failedCount} cloud update(s) failed. Failed item(s) remain in History."
                            }

                        Toast.makeText(
                            this@HistoryActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()

                        loadActiveScans()
                    }
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }
}