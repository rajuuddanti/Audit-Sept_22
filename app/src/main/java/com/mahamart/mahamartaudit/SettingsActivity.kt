package com.mahamart.mahamartaudit

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal

class SettingsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var etStoreName: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etRackNo: EditText
    private lateinit var tvMasterCount: TextView
    private lateinit var progressBarImport: ProgressBar
    private lateinit var tvImportStatus: TextView
    private lateinit var btnImport: Button

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importCsvMasterData(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        db = AppDatabase.getDatabase(this)

        etStoreName = findViewById(R.id.etStoreName)
        etDeviceId = findViewById(R.id.etDeviceIdSetting)
        etRackNo = findViewById(R.id.etRackNoSetting)
        tvMasterCount = findViewById(R.id.tvMasterCount)
        progressBarImport = findViewById(R.id.progressBarImport)
        tvImportStatus = findViewById(R.id.tvImportStatus)
        btnImport = findViewById(R.id.btnImportExcelOffline)

        loadSavedSettings()

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        btnImport.setOnClickListener { filePickerLauncher.launch("*/*") }

        // Route Recycle Bin button directly to BinActivity
        findViewById<Button>(R.id.btnGoToBin).setOnClickListener {
            val intent = Intent(this, BinActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateMasterCount()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("AuditPrefs", Context.MODE_PRIVATE)
        etStoreName.setText(prefs.getString("STORE_NAME", "Mahalaxmi Mahamart"))
        etDeviceId.setText(prefs.getString("DEVICE_ID", "DEV01"))
        etRackNo.setText(prefs.getString("RACK_NO", "RACK-01"))
    }

    private fun saveSettings() {
        val store = etStoreName.text.toString().trim()
        val device = etDeviceId.text.toString().trim()
        val rack = etRackNo.text.toString().trim()

        if (store.isEmpty() || device.isEmpty() || rack.isEmpty()) {
            Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
            return
        }

        val currentDevice = getSharedPreferences("AuditPrefs", Context.MODE_PRIVATE)
            .getString("DEVICE_ID", "DEV01")?.trim().orEmpty()

        if (device != currentDevice) {
            showDeviceIdPinDialog(store, device, rack)
        } else {
            persistSettings(store, device, rack)
        }
    }

    private fun showDeviceIdPinDialog(store: String, device: String, rack: String) {
        val pinInput = EditText(this).apply {
            hint = "Enter PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Authorize Device ID Change")
            .setMessage("Enter the PIN to change the Device ID.")
            .setView(pinInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Verify", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pinInput.text.toString() == "1413") {
                    dialog.dismiss()
                    persistSettings(store, device, rack)
                } else {
                    pinInput.error = "Incorrect PIN"
                    pinInput.setText("")
                }
            }
        }
        dialog.show()
    }

    private fun persistSettings(store: String, device: String, rack: String) {
        getSharedPreferences("AuditPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("STORE_NAME", store)
            putString("DEVICE_ID", device)
            putString("RACK_NO", rack)
            apply()
        }

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun importCsvMasterData(uri: Uri) {
        btnImport.isEnabled = false
        progressBarImport.visibility = View.VISIBLE
        tvImportStatus.visibility = View.VISIBLE
        progressBarImport.isIndeterminate = false
        progressBarImport.progress = 0
        tvImportStatus.text = "Counting lines..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var totalLines = 0
                contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        while (reader.readLine() != null) totalLines++
                    }
                }

                if (totalLines <= 1) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "File is empty or contains no data rows", Toast.LENGTH_LONG).show()
                        resetImportUi()
                    }
                    return@launch
                }

                db.scanDao().clearMasterSkuTable()

                val batchList = mutableListOf<MasterSku>()
                var processedLines = 0

                contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        var line: String? = reader.readLine() // Skip Header Row

                        while (reader.readLine().also { line = it } != null) {
                            processedLines++
                            val tokens = line?.split(",", "\t", ";") ?: continue

                            if (tokens.isNotEmpty()) {
                                val rawBarcode = tokens[0].trim().replace("\"", "")
                                val name = if (tokens.size > 1) tokens[1].trim().replace("\"", "") else ""

                                val cleanBarcode = sanitizeBarcode(rawBarcode)

                                if (cleanBarcode.isNotEmpty() && !cleanBarcode.equals("barcode", ignoreCase = true)) {
                                    batchList.add(MasterSku(barcode = cleanBarcode, name = name))
                                }
                            }

                            if (batchList.size >= 5000) {
                                db.scanDao().insertMasterSkusBatch(batchList)
                                batchList.clear()

                                val currentProgress = ((processedLines.toFloat() / totalLines.toFloat()) * 100).toInt()
                                val count = processedLines

                                withContext(Dispatchers.Main) {
                                    progressBarImport.progress = currentProgress
                                    tvImportStatus.text = "Importing: $currentProgress% ($count / $totalLines)"
                                }
                            }
                        }

                        if (batchList.isNotEmpty()) {
                            db.scanDao().insertMasterSkusBatch(batchList)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBarImport.progress = 100
                    tvImportStatus.text = "Completed! Imported $processedLines SKUs"
                    btnImport.isEnabled = true
                    updateMasterCount()
                    Toast.makeText(this@SettingsActivity, "Imported $processedLines Master SKUs successfully!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    resetImportUi()
                }
            }
        }
    }

    private fun sanitizeBarcode(raw: String): String {
        if (raw.isEmpty()) return ""

        return try {
            if (raw.contains("E", ignoreCase = true)) {
                BigDecimal(raw).toPlainString().split(".")[0]
            } else if (raw.contains(".")) {
                raw.split(".")[0]
            } else {
                raw
            }
        } catch (e: Exception) {
            raw
        }
    }

    private fun resetImportUi() {
        progressBarImport.visibility = View.GONE
        tvImportStatus.visibility = View.GONE
        btnImport.isEnabled = true
    }

    private fun updateMasterCount() {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = db.scanDao().getSkuMasterCount()
            withContext(Dispatchers.Main) {
                tvMasterCount.text = "Total Master SKUs: $count"
            }
        }
    }
}