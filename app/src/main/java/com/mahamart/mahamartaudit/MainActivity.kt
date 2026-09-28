package com.mahamart.mahamartaudit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase

    private lateinit var etBarcode: EditText
    private lateinit var etQuantity: EditText
    private lateinit var etRackNo: EditText
    private lateinit var tvDate: TextView
    private lateinit var tvTime: TextView
    private lateinit var etDeviceId: EditText
    private lateinit var tvTitle: TextView
    private lateinit var btnSend: Button
    private lateinit var containerRecentScans: LinearLayout

    // SKU Search Mode UI
    private lateinit var switchSkuSearch: SwitchMaterial
    private lateinit var actvSkuSearch: PersistentAutoCompleteTextView
    private lateinit var tvBarcodeLabel: TextView
    private lateinit var tvSkuSearchLabel: TextView

    private var isSkuSearchMode = false

    // Keeps selected search SKU alive while async master lookup runs.
    private var selectedSkuFromSearch: String? = null

    // Item name shown directly below Barcode.
    // This is UI-only and uses master_skus.
    private var tvSkuNamePreview: TextView? = null

    private var isProcessingSave = false
    private var isUpdatingBarcodeFromScan = false
    private var isBarcodeLocked = false
    private var lastScanTime: Long = 0

    // Prevent the remaining characters of a rejected scanner
    // input from leaking into Quantity or Barcode.
    private var isQuantityScanBlocked = false
    private var quantityScanBlockUntil: Long = 0L

    // ---------------------------------------------------------
    // ZEBRA DATAWEDGE RECEIVER
    // ---------------------------------------------------------

    private val zebraScanReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                val action = intent?.action

                if (
                    action == "com.symbol.datawedge.api.RESULT_ACTION" ||
                    action == "com.mahamart.SCAN_ACTION" ||
                    action == "android.intent.ACTION_DECODE_DATA"
                ) {

                    val scannedBarcode =
                        intent.getStringExtra(
                            "com.symbol.datawedge.data_string"
                        )
                            ?: intent.getStringExtra("data")

                    if (!scannedBarcode.isNullOrEmpty()) {

                        if (currentFocus == etQuantity) {

                            /*
                             * HARD STOP:
                             * A scanner scan is not allowed to enter Quantity.
                             *
                             * Clear Quantity immediately and block the
                             * remaining keyboard-wedge characters from the
                             * same scan so the last 3/4 digits cannot become
                             * a valid quantity and leak back into Barcode.
                             */
                            isQuantityScanBlocked = true

                            quantityScanBlockUntil =
                                System.currentTimeMillis() + 1500L

                            etQuantity.setText("")

                            triggerErrorFeedback()

                            Toast.makeText(
                                this@MainActivity,
                                "Scan rejected in Quantity area!",
                                Toast.LENGTH_SHORT
                            ).show()

                            return

                        } else if (!isSkuSearchMode) {

                            isBarcodeLocked = false

                            processScannedBarcode(
                                scannedBarcode.trim()
                            )
                        }
                    }
                }
            }
        }

    // ---------------------------------------------------------
    // ON CREATE
    // ---------------------------------------------------------

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        db =
            AppDatabase.getDatabase(this)

        etBarcode =
            findViewById(R.id.etBarcode)

        etQuantity =
            findViewById(R.id.etQuantity)

        etRackNo =
            findViewById(R.id.etRackNo)

        tvDate =
            findViewById(R.id.tvDate)

        tvTime =
            findViewById(R.id.tvTime)

        etDeviceId =
            findViewById(R.id.etDeviceId)

        tvTitle =
            findViewById(R.id.tvTitle)

        btnSend =
            findViewById(R.id.btnSend)

        containerRecentScans =
            findViewById(R.id.containerRecentScans)

        // SKU Search views
        switchSkuSearch =
            findViewById(R.id.switchSkuSearch)

        actvSkuSearch =
            findViewById(R.id.actvSkuSearch)

        tvBarcodeLabel =
            findViewById(R.id.tvBarcodeLabel)

        tvSkuSearchLabel =
            findViewById(R.id.tvSkuSearchLabel)

        // Rack is controlled from Settings.
        etRackNo.isFocusable = false
        etRackNo.isClickable = false
        etRackNo.isEnabled = false

        // Restore Item Name display.
        setupSkuPreviewLabel()

        updateDateTime()

        setupSkuSearchMode()

        // -----------------------------------------------------
        // MANUAL BARCODE TOUCH
        // -----------------------------------------------------

        etBarcode.setOnTouchListener { _, _ ->

            unlockScanInput()

            false
        }

        etBarcode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) refreshSkuPreviewFromBarcodeIfNeeded()
        }

        // -----------------------------------------------------
        // QUANTITY FOCUS
        // -----------------------------------------------------

        etQuantity.setOnFocusChangeListener {
                view,
                hasFocus ->

            if (hasFocus) {

                view.postDelayed({

                    val imm =
                        getSystemService(
                            Context.INPUT_METHOD_SERVICE
                        ) as InputMethodManager

                    imm.showSoftInput(
                        view,
                        InputMethodManager.SHOW_IMPLICIT
                    )

                }, 100)
            }
        }

        // -----------------------------------------------------
        // BARCODE TEXT WATCHER
        // -----------------------------------------------------

        etBarcode.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                    if (
                        count > 0 &&
                        after > 0
                    ) {

                        isBarcodeLocked = false
                    }
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {

                    val input =
                        s?.toString()
                            ?.trim()
                            ?: ""

                    if (isUpdatingBarcodeFromScan) {
                        return
                    }

                    if (input.isEmpty()) {

                        isBarcodeLocked = false

                        tvSkuNamePreview?.text = ""

                        return
                    }

                    // Manual barcode entry must also resolve the item name
                    // from master_skus. Wait briefly so we do not query
                    // Room once for every character typed.
                    tvSkuNamePreview?.text = ""

                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(250L)

                        val currentInput =
                            etBarcode.text
                                .toString()
                                .trim()

                        if (
                            currentInput == input &&
                            currentInput.isNotEmpty()
                        ) {
                            lookupSkuName(currentInput)
                        }
                    }

                    // Support scanner profiles that send CR/LF.
                    if (
                        s.toString().contains("\n") ||
                        s.toString().contains("\r")
                    ) {

                        val cleanBarcode =
                            s.toString()
                                .replace("\n", "")
                                .replace("\r", "")
                                .trim()

                        if (
                            cleanBarcode.isNotEmpty()
                        ) {

                            isBarcodeLocked = false

                            processScannedBarcode(
                                cleanBarcode
                            )

                        } else {

                            tvSkuNamePreview?.text = ""
                        }
                    }
                }
            }
        )

        // -----------------------------------------------------
        // QUANTITY TEXT WATCHER
        // -----------------------------------------------------

        etQuantity.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {

                    val input =
                        s?.toString()
                            ?.trim()
                            ?: ""

                    if (input.length > 4) {

                        /*
                         * HARD STOP:
                         * Once Quantity exceeds 4 digits, clear the entire
                         * field and block the remaining scanner keystrokes.
                         */
                        isQuantityScanBlocked = true

                        quantityScanBlockUntil =
                            System.currentTimeMillis() + 1500L

                        etQuantity.removeTextChangedListener(
                            this
                        )

                        etQuantity.setText("")

                        etQuantity.addTextChangedListener(
                            this
                        )

                        triggerErrorFeedback()

                        Toast.makeText(
                            this@MainActivity,
                            "Max 4 digits allowed in Qty!",
                            Toast.LENGTH_SHORT
                        ).show()

                        etQuantity.post {

                            etQuantity.requestFocus()
                            etQuantity.setSelection(0)
                        }
                    }
                }
            }
        )

        // -----------------------------------------------------
        // BARCODE IME
        // -----------------------------------------------------

        etBarcode.setOnEditorActionListener {
                _,
                actionId,
                _ ->

            if (
                actionId ==
                EditorInfo.IME_ACTION_NEXT ||
                actionId ==
                EditorInfo.IME_ACTION_DONE ||
                actionId ==
                EditorInfo.IME_ACTION_UNSPECIFIED ||
                actionId ==
                EditorInfo.IME_NULL
            ) {

                val barcodeText =
                    etBarcode.text
                        .toString()
                        .trim()

                if (
                    barcodeText.isNotEmpty()
                ) {

                    isBarcodeLocked = false

                    processScannedBarcode(
                        barcodeText
                    )
                }

                true

            } else {

                false
            }
        }

        // -----------------------------------------------------
        // QUANTITY ENTER / DONE
        // -----------------------------------------------------

        etQuantity.setOnEditorActionListener {
                _,
                actionId,
                event ->

            val isEnterKeyDown =
                event != null &&
                        event.keyCode ==
                        KeyEvent.KEYCODE_ENTER &&
                        event.action ==
                        KeyEvent.ACTION_DOWN

            val isDoneAction =
                actionId ==
                        EditorInfo.IME_ACTION_DONE ||
                        actionId ==
                        EditorInfo.IME_ACTION_SEND

            if (
                (isDoneAction ||
                        isEnterKeyDown) &&
                !isProcessingSave
            ) {

                val qtyStr =
                    etQuantity.text
                        .toString()
                        .trim()

                if (
                    qtyStr.length > 4
                ) {

                    etQuantity.setText("")

                    triggerErrorFeedback()

                    true

                } else {

                    isProcessingSave = true

                    triggerFeedback()

                    saveRecord()

                    etQuantity.postDelayed(
                        {

                            isProcessingSave = false

                        },
                        300
                    )

                    true
                }

            } else {

                false
            }
        }

        // -----------------------------------------------------
        // HISTORY
        // -----------------------------------------------------

        findViewById<ImageButton>(
            R.id.btnHistory
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    HistoryActivity::class.java
                )
            )
        }

        // -----------------------------------------------------
        // SETTINGS
        // -----------------------------------------------------

        findViewById<ImageButton>(
            R.id.btnSettings
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    SettingsActivity::class.java
                )
            )
        }

        // -----------------------------------------------------
        // PLUS
        // -----------------------------------------------------

        findViewById<Button>(
            R.id.btnPlus
        ).setOnClickListener {

            val current =
                etQuantity.text
                    .toString()
                    .toIntOrNull()
                    ?: 0

            if (current < 9999) {

                etQuantity.setText(
                    (current + 1).toString()
                )
            }
        }

        // -----------------------------------------------------
        // MINUS
        // -----------------------------------------------------

        findViewById<Button>(
            R.id.btnMinus
        ).setOnClickListener {

            val current =
                etQuantity.text
                    .toString()
                    .toIntOrNull()
                    ?: 0

            if (current > 1) {

                etQuantity.setText(
                    (current - 1).toString()
                )

            } else if (current == 1) {

                etQuantity.setText("")
            }
        }

        // -----------------------------------------------------
        // SEND
        // -----------------------------------------------------

        btnSend.setOnClickListener {

            if (!isProcessingSave) {

                isProcessingSave = true

                triggerFeedback()

                saveRecord()

                btnSend.postDelayed(
                    {

                        isProcessingSave = false

                    },
                    300
                )
            }
        }
    }

    // ---------------------------------------------------------
    // SKU SEARCH MODE
    // ---------------------------------------------------------

    private fun setupSkuSearchMode() {

        switchSkuSearch.setOnCheckedChangeListener {
                _,
                isChecked ->

            isSkuSearchMode =
                isChecked

            if (isChecked) {

                tvBarcodeLabel.visibility =
                    View.GONE

                etBarcode.visibility =
                    View.GONE

                tvSkuSearchLabel.visibility =
                    View.VISIBLE

                actvSkuSearch.visibility =
                    View.VISIBLE

                actvSkuSearch.requestFocus()

            } else {

                tvSkuSearchLabel.visibility =
                    View.GONE

                actvSkuSearch.visibility =
                    View.GONE

                tvBarcodeLabel.visibility =
                    View.VISIBLE

                etBarcode.visibility =
                    View.VISIBLE

                unlockScanInput()

                etBarcode.requestFocus()
                refreshSkuPreviewFromBarcodeIfNeeded()
            }
        }

        // Open dropdown on click.
        actvSkuSearch.setOnClickListener {

            if (
                actvSkuSearch.text
                    .trim()
                    .length >= 2
            ) {

                actvSkuSearch.showDropDown()
            }
        }

        // Open dropdown on focus.
        actvSkuSearch.setOnFocusChangeListener {
                _,
                hasFocus ->

            if (
                hasFocus &&
                actvSkuSearch.text
                    .trim()
                    .length >= 2
            ) {

                actvSkuSearch.postDelayed(
                    {

                        actvSkuSearch.showDropDown()

                    },
                    100
                )
            }
        }

        // Predictive SKU search.
        actvSkuSearch.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {

                    val query =
                        s?.toString()
                            ?.trim()
                            ?: ""

                    if (
                        query.length >= 2
                    ) {

                        // New search means no currently
                        // selected SKU.
                        selectedSkuFromSearch = null

                        lifecycleScope.launch(
                            Dispatchers.IO
                        ) {

                            val results =
                                db.scanDao()
                                    .searchSkusByName(
                                        query
                                    )

                            val displayList =
                                results
                                    .groupBy {
                                        it.name
                                    }
                                    .map {
                                            (name, skus) ->

                                        val primarySku =
                                            skus.minByOrNull {
                                                it.barcode.length
                                            }?.barcode
                                                ?: ""

                                        "$name | Code: $primarySku"
                                    }

                            withContext(
                                Dispatchers.Main
                            ) {

                                val adapter =
                                    ArrayAdapter(
                                        this@MainActivity,
                                        R.layout.item_sku_dropdown,
                                        displayList
                                    )

                                actvSkuSearch
                                    .setAdapter(adapter)

                                adapter
                                    .notifyDataSetChanged()

                                if (
                                    actvSkuSearch
                                        .hasFocus()
                                ) {

                                    actvSkuSearch
                                        .showDropDown()
                                }
                            }
                        }
                    }
                }
            }
        )

        // -----------------------------------------------------
        // SKU SELECTION
        // -----------------------------------------------------

        actvSkuSearch.setOnItemClickListener {
                parent,
                _,
                position,
                _ ->

            val selectedItem =
                parent.getItemAtPosition(
                    position
                ) as String

            val skuCode =
                selectedItem
                    .substringAfter(
                        "| Code: "
                    )
                    .trim()

            if (
                skuCode.isEmpty()
            ) {

                return@setOnItemClickListener
            }

            // Keep selected SKU alive while
            // master lookup runs.
            selectedSkuFromSearch =
                skuCode

            // Put selected SKU/EAN into actual
            // barcode field.
            etBarcode.setText(
                skuCode
            )

            // IMPORTANT:
            // Master SKU lookup ONLY.
            lookupSkuName(
                skuCode
            )

            actvSkuSearch.setText("")

            actvSkuSearch
                .forceDismissDropDown()

            jumpToQuantity()
        }
    }

    // ---------------------------------------------------------
    // LOCK / UNLOCK BARCODE
    // ---------------------------------------------------------

    private fun lockScanInput() {

        etBarcode.isEnabled =
            false

        etBarcode.isFocusable =
            false

        etBarcode.isFocusableInTouchMode =
            false
    }

    private fun unlockScanInput() {

        etBarcode.isEnabled =
            true

        etBarcode.isFocusable =
            true

        etBarcode.isFocusableInTouchMode =
            true
    }

    // ---------------------------------------------------------
    // SCANNER
    // ---------------------------------------------------------

    @Synchronized
    private fun processScannedBarcode(
        barcode: String
    ) {

        val cleanBarcode =
            barcode.trim()

        if (
            cleanBarcode.isEmpty()
        ) {
            return
        }

        val currentTime =
            System.currentTimeMillis()

        if (
            currentTime -
            lastScanTime < 500
        ) {

            return
        }

        lastScanTime =
            currentTime

        isBarcodeLocked =
            true

        // Scanner input is not SKU search.
        selectedSkuFromSearch =
            null

        etBarcode.post {

            isUpdatingBarcodeFromScan =
                true

            etBarcode.setText(
                cleanBarcode
            )

            etBarcode.setSelection(
                cleanBarcode.length
            )

            isUpdatingBarcodeFromScan =
                false

            lockScanInput()

            // IMPORTANT:
            // Only master_skus is queried here.
            //
            // NO scans history.
            // NO Supabase history import.
            // NO duplicate lookup.
            lookupSkuName(
                cleanBarcode
            )

            jumpToQuantity()
        }
    }

    // ---------------------------------------------------------
    // FEEDBACK
    // ---------------------------------------------------------

    private fun triggerFeedback() {

        playBeepSound()

        triggerVibration()
    }

    private fun triggerErrorFeedback() {

        try {

            val toneGen =
                ToneGenerator(
                    AudioManager.STREAM_ALARM,
                    100
                )

            toneGen.startTone(
                ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                250
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as VibratorManager

                val vibrator =
                    vibratorManager.defaultVibrator

                val doubleVibe =
                    VibrationEffect
                        .createWaveform(
                            longArrayOf(
                                0,
                                150,
                                100,
                                150
                            ),
                            -1
                        )

                vibrator.vibrate(
                    doubleVibe
                )

            } else {

                @Suppress("DEPRECATION")
                val vibrator =
                    getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

                    val doubleVibe =
                        VibrationEffect
                            .createWaveform(
                                longArrayOf(
                                    0,
                                    150,
                                    100,
                                    150
                                ),
                                -1
                            )

                    vibrator.vibrate(
                        doubleVibe
                    )

                } else {

                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        300
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }

    private fun playBeepSound() {

        try {

            val toneGen =
                ToneGenerator(
                    AudioManager.STREAM_MUSIC,
                    100
                )

            toneGen.startTone(
                ToneGenerator.TONE_PROP_BEEP,
                100
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }

    private fun triggerVibration() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as VibratorManager

                val vibrator =
                    vibratorManager.defaultVibrator

                val syncWave =
                    VibrationEffect
                        .createWaveform(
                            longArrayOf(
                                0,
                                120
                            ),
                            intArrayOf(
                                0,
                                VibrationEffect
                                    .DEFAULT_AMPLITUDE
                            ),
                            -1
                        )

                vibrator.vibrate(
                    syncWave
                )

            } else {

                @Suppress("DEPRECATION")
                val vibrator =
                    getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

                    val syncWave =
                        VibrationEffect
                            .createWaveform(
                                longArrayOf(
                                    0,
                                    120
                                ),
                                intArrayOf(
                                    0,
                                    VibrationEffect
                                        .DEFAULT_AMPLITUDE
                                ),
                                -1
                            )

                    vibrator.vibrate(
                        syncWave
                    )

                } else {

                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        120
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------
    // KEY EVENTS
    // ---------------------------------------------------------

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        /*
         * IMPORTANT:
         * When a scanner is rejected in Quantity, DataWedge may still
         * be sending the barcode characters as keyboard-wedge events.
         *
         * Consume those remaining events for a short window so they
         * cannot become a partial quantity or leak into Barcode.
         */
        if (isQuantityScanBlocked) {

            val now =
                System.currentTimeMillis()

            if (now < quantityScanBlockUntil) {

                return true
            }

            isQuantityScanBlocked = false
        }

        if (event.action == KeyEvent.ACTION_DOWN) {

            val isEnterKey =
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_TAB

            /*
             * Normal Barcode ENTER/TAB behavior.
             */
            if (
                isEnterKey &&
                currentFocus == etBarcode
            ) {

                val barcodeText =
                    etBarcode.text
                        .toString()
                        .trim()

                if (barcodeText.isNotEmpty()) {

                    isBarcodeLocked = false

                    processScannedBarcode(
                        barcodeText
                    )

                    return true
                }
            }

            /*
             * If Quantity is focused, ENTER/TAB must never move
             * scanner input to Barcode.
             */
            if (
                isEnterKey &&
                currentFocus == etQuantity
            ) {

                return true
            }
        }

        return super.dispatchKeyEvent(
            event
        )
    }

    // ---------------------------------------------------------
    // MOVE TO QUANTITY
    // ---------------------------------------------------------

    private fun jumpToQuantity() {

        etQuantity.postDelayed({

            etQuantity.requestFocus()

            etQuantity.selectAll()

            val imm =
                getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager

            imm.showSoftInput(
                etQuantity,
                InputMethodManager.SHOW_IMPLICIT
            )

        }, 100)
    }

    // ---------------------------------------------------------
    // RESTORE ITEM NAME PREVIEW
    // ---------------------------------------------------------

    private fun refreshSkuPreviewFromBarcodeIfNeeded() {

        val barcode =
            etBarcode.text
                .toString()
                .trim()

        if (
            barcode.isNotEmpty() &&
            tvSkuNamePreview?.text
                ?.toString()
                ?.trim()
                .isNullOrEmpty()
        ) {
            lookupSkuName(barcode)
        }
    }

    // ITEM NAME PREVIEW
    // ---------------------------------------------------------

    private fun setupSkuPreviewLabel() {

        val parent =
            etBarcode.parent
                    as? LinearLayout

        val index =
            parent?.indexOfChild(
                etBarcode
            ) ?: -1

        if (
            parent != null &&
            index != -1
        ) {

            tvSkuNamePreview =
                TextView(this).apply {

                    textSize =
                        10.5f

                    setTypeface(
                        Typeface.MONOSPACE,
                        Typeface.BOLD
                    )

                    setPadding(
                        8,
                        4,
                        8,
                        8
                    )

                    maxLines =
                        2

                    setTextColor(
                        Color.parseColor(
                            "#008000"
                        )
                    )
                }

            parent.addView(
                tvSkuNamePreview,
                index + 1
            )
        }
    }

    // ---------------------------------------------------------
    // MASTER SKU LOOKUP ONLY
    // ---------------------------------------------------------

    private fun lookupSkuName(
        barcode: String
    ) {

        val cleanBarcode =
            barcode.trim()

        if (
            cleanBarcode.isEmpty()
        ) {

            tvSkuNamePreview?.text =
                ""

            return
        }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            // ONLY master_skus.
            val master =
                db.scanDao()
                    .getMasterSkuByBarcode(
                        cleanBarcode
                    )

            val itemName =
                master
                    ?.name
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: "#N/A"

            withContext(
                Dispatchers.Main
            ) {

                /*
                 * Don't let an older asynchronous lookup
                 * overwrite a newer barcode.
                 */
                val currentBarcode =
                    etBarcode.text
                        .toString()
                        .trim()

                /*
                 * Search selection can temporarily clear
                 * the search box, but the real barcode field
                 * remains the selected SKU.
                 */
                if (
                    currentBarcode !=
                    cleanBarcode
                ) {

                    return@withContext
                }

                tvSkuNamePreview?.text =
                    "Item: $itemName"

                if (
                    itemName == "#N/A"
                ) {

                    tvSkuNamePreview
                        ?.setTextColor(
                            Color.parseColor(
                                "#D32F2F"
                            )
                        )

                    tvSkuNamePreview
                        ?.setTypeface(
                            Typeface.MONOSPACE,
                            Typeface.NORMAL
                        )

                } else {

                    tvSkuNamePreview
                        ?.setTextColor(
                            Color.parseColor(
                                "#008000"
                            )
                        )

                    tvSkuNamePreview
                        ?.setTypeface(
                            Typeface.MONOSPACE,
                            Typeface.BOLD
                        )
                }
            }
        }
    }

    // ---------------------------------------------------------
    // RESUME
    // ---------------------------------------------------------

    override fun onResume() {

        super.onResume()

        updateDateTime()

        applySavedSettings()

        refreshSkuPreviewFromBarcodeIfNeeded()

        loadRecentScans()

        val filter =
            IntentFilter().apply {

                addAction(
                    "com.symbol.datawedge.api.RESULT_ACTION"
                )

                addAction(
                    "com.mahamart.SCAN_ACTION"
                )

                addAction(
                    "android.intent.ACTION_DECODE_DATA"
                )
            }

        ContextCompat.registerReceiver(
            this,
            zebraScanReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            SyncManager.syncUnsyncedData(
                this@MainActivity,
                db
            )
        }
    }

    // ---------------------------------------------------------
    // PAUSE
    // ---------------------------------------------------------

    override fun onPause() {

        super.onPause()

        try {

            unregisterReceiver(
                zebraScanReceiver
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------
    // SETTINGS
    // ---------------------------------------------------------

    private fun applySavedSettings() {

        val prefs =
            getSharedPreferences(
                "AuditPrefs",
                Context.MODE_PRIVATE
            )

        val store =
            prefs.getString(
                "STORE_NAME",
                "Mahalaxmi Mahamart"
            ) ?: "Mahalaxmi Mahamart"

        val device =
            prefs.getString(
                "DEVICE_ID",
                "DEV01"
            ) ?: "DEV01"

        val rack =
            prefs.getString(
                "RACK_NO",
                "RACK-01"
            ) ?: "RACK-01"

        tvTitle.text =
            "Stock Audit - $store"

        etDeviceId.setText(
            device
        )

        etRackNo.setText(
            rack
        )
    }

    // ---------------------------------------------------------
    // DATE / TIME
    // ---------------------------------------------------------

    private fun updateDateTime() {

        val now =
            Date()

        tvDate.text =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(now)

        tvTime.text =
            SimpleDateFormat(
                "hh:mm:ss a",
                Locale.getDefault()
            ).format(now)
    }

    // ---------------------------------------------------------
    // RECENT SCANS
    // ---------------------------------------------------------

    private fun loadRecentScans() {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val myDeviceId =
                getSharedPreferences(
                    "AuditPrefs",
                    Context.MODE_PRIVATE
                )
                    .getString("DEVICE_ID", "DEV01")
                    ?.trim()
                    .takeUnless {
                        it.isNullOrEmpty()
                    }
                    ?: "DEV01"

            val recents =
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
                            try {
                                SimpleDateFormat(
                                    "yyyy-MM-dd hh:mm:ss a",
                                    Locale.getDefault()
                                ).parse(
                                    it.date + " " + it.time
                                )?.time ?: 0L
                            } catch (
                                e: Exception
                            ) {
                                0L
                            }
                        }.thenByDescending {
                            it.id
                        }
                    )
                    .take(5)

            val skuNameMap =
                mutableMapOf<
                        String,
                        String
                        >()

            recents.forEach { record ->

                val master =
                    db.scanDao()
                        .getMasterSkuByBarcode(
                            record.barcode
                        )

                if (
                    master != null &&
                    master.name.isNotEmpty()
                ) {

                    skuNameMap[
                        record.barcode
                    ] =
                        master.name
                }
            }

            withContext(
                Dispatchers.Main
            ) {

                containerRecentScans
                    .removeAllViews()

                if (
                    recents.isEmpty()
                ) {

                    val emptyTv =
                        TextView(
                            this@MainActivity
                        ).apply {

                            text =
                                "No recent scans available"

                            setTextColor(
                                Color.parseColor(
                                    "#7A6664"
                                )
                            )

                            textSize =
                                13f

                            gravity =
                                Gravity.CENTER

                            setPadding(
                                0,
                                24,
                                0,
                                24
                            )
                        }

                    containerRecentScans
                        .addView(
                            emptyTv
                        )

                } else {

                    recents.forEach {
                            record ->

                        val row =
                            LinearLayout(
                                this@MainActivity
                            ).apply {

                                orientation =
                                    LinearLayout.HORIZONTAL

                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    )

                                setPadding(
                                    0,
                                    8,
                                    0,
                                    8
                                )
                            }

                        val skuColumn =
                            LinearLayout(
                                this@MainActivity
                            ).apply {

                                orientation =
                                    LinearLayout.VERTICAL

                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        2.2f
                                    )
                            }

                        val tvBarcode =
                            TextView(
                                this@MainActivity
                            ).apply {

                                text =
                                    record.barcode

                                setTextColor(
                                    Color.parseColor(
                                        "#111111"
                                    )
                                )

                                textSize =
                                    13f

                                setTypeface(
                                    null,
                                    Typeface.BOLD
                                )
                            }

                        val skuName =
                            skuNameMap[
                                record.barcode
                            ] ?: "#N/A"

                        val tvSkuName =
                            TextView(
                                this@MainActivity
                            ).apply {

                                text =
                                    skuName

                                setTextColor(
                                    Color.parseColor(
                                        "#666666"
                                    )
                                )

                                textSize =
                                    11f
                            }

                        skuColumn.addView(
                            tvBarcode
                        )

                        skuColumn.addView(
                            tvSkuName
                        )

                        val tvQty =
                            TextView(
                                this@MainActivity
                            ).apply {

                                text =
                                    record.quantity
                                        .toString()

                                setTextColor(
                                    Color.parseColor(
                                        "#111111"
                                    )
                                )

                                textSize =
                                    13f

                                gravity =
                                    Gravity.CENTER

                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        0.8f
                                    )
                            }

                        val tvRack =
                            TextView(
                                this@MainActivity
                            ).apply {

                                text =
                                    record.rackNo
                                        .ifEmpty {
                                            "-"
                                        }

                                setTextColor(
                                    Color.parseColor(
                                        "#111111"
                                    )
                                )

                                textSize =
                                    13f

                                gravity =
                                    Gravity.CENTER

                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        1.0f
                                    )
                            }

                        val tvTimeVal =
                            TextView(
                                this@MainActivity
                            ).apply {

                                val formattedTime =
                                    record.time
                                        .replace(
                                            "am",
                                            "AM"
                                        )
                                        .replace(
                                            "pm",
                                            "PM"
                                        )

                                text =
                                    formattedTime

                                setTextColor(
                                    Color.parseColor(
                                        "#111111"
                                    )
                                )

                                textSize =
                                    11f

                                gravity =
                                    Gravity.END

                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        1.2f
                                    )
                            }

                        row.addView(
                            skuColumn
                        )

                        row.addView(
                            tvQty
                        )

                        row.addView(
                            tvRack
                        )

                        row.addView(
                            tvTimeVal
                        )

                        containerRecentScans
                            .addView(
                                row
                            )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // SAVE
    // ---------------------------------------------------------

    private fun saveRecord() {

        val barcode =
            etBarcode.text
                .toString()
                .trim()

        val qtyStr =
            etQuantity.text
                .toString()
                .trim()

        val rackNo =
            etRackNo.text
                .toString()
                .trim()

        if (
            barcode.isEmpty()
        ) {

            Toast.makeText(
                this,
                "Please enter Barcode",
                Toast.LENGTH_SHORT
            ).show()

            unlockScanInput()

            etBarcode.requestFocus()

            isBarcodeLocked =
                false

            return
        }

        if (
            qtyStr.isEmpty()
        ) {

            Toast.makeText(
                this,
                "Please enter Quantity",
                Toast.LENGTH_SHORT
            ).show()

            etQuantity.requestFocus()

            return
        }

        val qty =
            qtyStr.toIntOrNull()
                ?: return

        val clickTime =
            Date()

        val date =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(
                clickTime
            )

        val time =
            SimpleDateFormat(
                "hh:mm:ss a",
                Locale.getDefault()
            ).format(
                clickTime
            )

        val record =
            ScanItem(
                barcode =
                    barcode,

                quantity =
                    qty,

                rackNo =
                    rackNo,

                date =
                    date,

                time =
                    time,

                deviceId =
                    etDeviceId.text
                        .toString()
                        .trim()
                        .ifEmpty {
                            "DEV01"
                        }
            )

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            db.scanDao()
                .insertScan(
                    record
                )

            withContext(
                Dispatchers.Main
            ) {

                Toast.makeText(
                    this@MainActivity,
                    "Saved successfully!",
                    Toast.LENGTH_SHORT
                ).show()

                isBarcodeLocked =
                    false

                unlockScanInput()

                etBarcode.setText("")

                etQuantity.setText("")

                tvSkuNamePreview?.text =
                    ""

                selectedSkuFromSearch =
                    null

                if (
                    isSkuSearchMode
                ) {

                    actvSkuSearch
                        .requestFocus()

                } else {

                    etBarcode.post {

                        etBarcode.requestFocus()
                    }
                }

                updateDateTime()

                loadRecentScans()
            }

            // Existing PUSH ONLY sync.
            SyncManager.syncUnsyncedData(
                this@MainActivity,
                db
            )
        }
    }

    // ---------------------------------------------------------
    // BACK
    // ---------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {

        val imm =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        if (
            imm.isAcceptingText
        ) {

            currentFocus?.let {
                    focusView ->

                imm.hideSoftInputFromWindow(
                    focusView.windowToken,
                    0
                )
            }

        } else {

            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
