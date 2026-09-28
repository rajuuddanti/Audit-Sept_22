package com.mahamart.mahamartaudit

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BinActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var lvBin: ListView
    private var deletedList = mutableListOf<ScanItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bin)

        db = AppDatabase.getDatabase(this)
        lvBin = findViewById(R.id.lvBin)

        // Enable multiple choice checkbox selection
        lvBin.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        findViewById<Button>(R.id.btnHome).setOnClickListener {
            goToHome()
        }

        // Restore Selected OR Restore All
        findViewById<Button>(R.id.btnRestoreAll).setOnClickListener {

            if (deletedList.isEmpty()) {
                Toast.makeText(
                    this,
                    "Bin is already empty",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val selectedItems = getSelectedItems()

            if (selectedItems.isNotEmpty()) {
                restoreSelectedItems(selectedItems)
            } else {
                confirmRestoreAll()
            }
        }

        // Empty Selected OR Empty All
        findViewById<Button>(R.id.btnEmptyBin).setOnClickListener {

            if (deletedList.isEmpty()) {
                Toast.makeText(
                    this,
                    "Bin is already empty",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val selectedItems = getSelectedItems()

            if (selectedItems.isNotEmpty()) {
                deleteSelectedPermanently(selectedItems)
            } else {
                confirmEmptyBin()
            }
        }

        loadDeletedScans()
    }

    private fun getSelectedItems(): List<ScanItem> {

        val checkedPositions = lvBin.checkedItemPositions
        val selected = mutableListOf<ScanItem>()

        for (i in 0 until deletedList.size) {
            if (checkedPositions.get(i)) {
                selected.add(deletedList[i])
            }
        }

        return selected
    }

    private fun loadDeletedScans() {

        lifecycleScope.launch(Dispatchers.IO) {

            deletedList =
                db.scanDao()
                    .getDeletedScans()
                    .toMutableList()

            // Map identical layout format to HistoryActivity with #N/A fallback
            val displayStrings =
                deletedList.map { scan ->

                    val master =
                        db.scanDao()
                            .getMasterSkuByBarcode(scan.barcode)

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

                // Update total bin count header view
                val tvTotalCount =
                    findViewById<TextView?>(R.id.tvTotalCount)

                if (tvTotalCount != null) {

                    tvTotalCount.text =
                        "Bin Scans: ${deletedList.size}"

                } else {

                    supportActionBar?.title =
                        "Recycle Bin (${deletedList.size})"
                }

                val adapter =
                    object : ArrayAdapter<String>(
                        this@BinActivity,
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

                            view.typeface =
                                androidx.core.content.res
                                    .ResourcesCompat
                                    .getFont(
                                        this@BinActivity,
                                        R.font.calibri_regular
                                    )

                            view.textSize = 13f

                            return view
                        }
                    }

                lvBin.adapter = adapter
                lvBin.clearChoices()
            }
        }
    }

    // ============================================================
    // RESTORE SELECTED
    // ============================================================

    private fun restoreSelectedItems(
        items: List<ScanItem>
    ) {

        lifecycleScope.launch(Dispatchers.IO) {

            // 1. Instant local restore
            items.forEach { item ->
                db.scanDao().restore(item.id)
            }

            // 2. Immediate UI Refresh
            withContext(Dispatchers.Main) {

                Toast.makeText(
                    this@BinActivity,
                    "${items.size} item(s) restored!",
                    Toast.LENGTH_SHORT
                ).show()

                loadDeletedScans()
            }

            // 3. Background cloud restore
            //
            // IMPORTANT:
            // Identify the EXACT scan using:
            // deviceId + barcode + rackNo + date + time

            items.forEach { item ->

                SyncManager.restoreScanInCloud(
                    item.deviceId,
                    item.barcode,
                    item.rackNo,
                    item.date,
                    item.time
                )
            }
        }
    }

    // ============================================================
    // DELETE SELECTED PERMANENTLY
    // ============================================================

    private fun deleteSelectedPermanently(
        items: List<ScanItem>
    ) {

        AlertDialog.Builder(this)
            .setTitle("Delete Selected Permanently")
            .setMessage(
                "Permanently delete \${items.size} selected item(s) from local database and cloud?"
            )
            .setPositiveButton("Delete Selected") { _, _ ->

                lifecycleScope.launch(Dispatchers.IO) {

                    // Delete the exact selected identities in cloud batches first.
                    val successfulItems =
                        SyncManager.hardDeleteScansFromCloud(items)

                    if (successfulItems.isNotEmpty()) {
                        db.scanDao().hardDeleteListChunked(
                            successfulItems.map { it.id }
                        )
                    }

                    val failedCount =
                        items.size - successfulItems.size

                    withContext(Dispatchers.Main) {
                        val message =
                            if (failedCount == 0) {
                                "\${successfulItems.size} item(s) deleted permanently from app and cloud"
                            } else {
                                "\${successfulItems.size} deleted, \${failedCount} failed cloud delete(s). Failed item(s) remain in Bin."
                            }

                        Toast.makeText(
                            this@BinActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()

                        loadDeletedScans()
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
    // RESTORE ALL
    // ============================================================
    // ============================================================
    // RESTORE ALL
    // ============================================================

    private fun confirmRestoreAll() {

        AlertDialog.Builder(this)
            .setTitle("Restore All Items")
            .setMessage(
                "Do you want to restore all items back to active scan history?"
            )
            .setPositiveButton("Restore All") { _, _ ->

                lifecycleScope.launch(Dispatchers.IO) {

                    val itemsToProcess =
                        deletedList.toList()

                    // 1. Instant local restore
                    itemsToProcess.forEach { item ->
                        db.scanDao().restore(item.id)
                    }

                    // 2. Immediate UI Refresh
                    withContext(Dispatchers.Main) {

                        Toast.makeText(
                            this@BinActivity,
                            "All items restored!",
                            Toast.LENGTH_SHORT
                        ).show()

                        loadDeletedScans()
                    }

                    // 3. Background cloud restore
                    //
                    // IMPORTANT:
                    // Restore the EXACT scan using:
                    // deviceId + barcode + rackNo + date + time

                    itemsToProcess.forEach { item ->

                        SyncManager.restoreScanInCloud(
                            item.deviceId,
                            item.barcode,
                            item.rackNo,
                            item.date,
                            item.time
                        )
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
    // EMPTY BIN
    // ============================================================

    private fun confirmEmptyBin() {

        AlertDialog.Builder(this)
            .setTitle("Empty Recycle Bin")
            .setMessage(
                "Are you sure you want to permanently delete all items in the bin and remove them from the cloud?"
            )
            .setPositiveButton("Empty Bin") { _, _ ->

                lifecycleScope.launch(Dispatchers.IO) {

                    val itemsToProcess = deletedList.toList()

                    val successfulItems =
                        SyncManager.hardDeleteScansFromCloud(itemsToProcess)

                    if (successfulItems.isNotEmpty()) {
                        db.scanDao().hardDeleteListChunked(
                            successfulItems.map { it.id }
                        )
                    }

                    val failedCount =
                        itemsToProcess.size - successfulItems.size

                    withContext(Dispatchers.Main) {
                        val message =
                            if (failedCount == 0) {
                                "Bin emptied permanently: \${successfulItems.size} item(s) deleted from app and cloud"
                            } else {
                                "\${successfulItems.size} deleted, \${failedCount} failed cloud delete(s). Failed item(s) remain in Bin."
                            }

                        Toast.makeText(
                            this@BinActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()

                        loadDeletedScans()
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
    // GO HOME
    // ============================================================
}
