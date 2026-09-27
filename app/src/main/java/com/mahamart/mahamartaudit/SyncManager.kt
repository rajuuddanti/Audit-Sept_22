package com.mahamart.mahamartaudit

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SyncManager {

    private const val SUPABASE_URL =
        "https://vejwgtflirwxrovaafts.supabase.co"

    private const val SUPABASE_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZlandndGZsaXJ3eHJvdmFhZnRzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc1NDg1MTEsImV4cCI6MjEwMzEyNDUxMX0.8qMDD-giACKIbef8RZPFTdARCBOAPvqp_5-tZkajf0w"

    private fun encodeUrlParam(param: String): String {
        return URLEncoder.encode(param, "UTF-8").replace("+", "%20")
    }

    // ============================================================
    // PUSH LOCAL UNSYNCED SCANS -> SUPABASE
    // ============================================================

    suspend fun syncUnsyncedData(
        context: Context,
        db: AppDatabase,
        isManual: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            try {

                val unsyncedScans = db.scanDao().getUnsyncedScans()

                if (unsyncedScans.isEmpty()) {

                    if (isManual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Cloud is already up to date",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    return@withContext
                }

                val jsonArray = JSONArray()

                unsyncedScans.forEach { scan ->

                    val master =
                        db.scanDao().getMasterSkuByBarcode(scan.barcode)

                    // IMPORTANT:
                    // Unknown barcode must NOT use the barcode as SKU code.
                    val skuCode =
                        db.scanDao().getPrimarySkuCodeByBarcode(scan.barcode)
                            ?: "#N/A"

                    val itemName =
                        master?.name?.ifEmpty { "#N/A" } ?: "#N/A"

                    val obj = JSONObject().apply {

                        put("barcode", scan.barcode)
                        put("sku_code", skuCode)
                        put("item_name", itemName)
                        put("quantity", scan.quantity)
                        put("rack_no", scan.rackNo)
                        put("date", scan.date)
                        put("time", scan.time)
                        put("device_id", scan.deviceId)
                        put("is_deleted", scan.isDeleted)
                    }

                    jsonArray.put(obj)
                }

                // Composite identity:
                // device_id + barcode + rack_no + date + time
                val url = URL(
                    "$SUPABASE_URL/rest/v1/scans" +
                            "?on_conflict=device_id,barcode,rack_no,date,time"
                )

                val connection =
                    (url.openConnection() as HttpURLConnection).apply {

                        requestMethod = "POST"

                        setRequestProperty(
                            "apikey",
                            SUPABASE_KEY
                        )

                        setRequestProperty(
                            "Authorization",
                            "Bearer $SUPABASE_KEY"
                        )

                        setRequestProperty(
                            "Content-Type",
                            "application/json"
                        )

                        setRequestProperty(
                            "Prefer",
                            "resolution=merge-duplicates,return=minimal"
                        )

                        doOutput = true
                    }

                val writer =
                    OutputStreamWriter(connection.outputStream)

                writer.write(jsonArray.toString())
                writer.flush()
                writer.close()

                val responseCode =
                    connection.responseCode

                connection.disconnect()

                if (responseCode in 200..299) {

                    val syncedIds =
                        unsyncedScans.map { it.id }

                    db.scanDao().markAsSynced(syncedIds)

                    if (isManual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Synced ${unsyncedScans.size} record(s) to cloud!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                } else {

                    if (isManual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Sync failed! Code: $responseCode",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {

                e.printStackTrace()

                if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Sync Error: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    // ============================================================
    // REMOTE SKU SCAN IMPORT
    // ============================================================
    //
    // INTENTIONALLY REMOVED.
    //
    // This app is PUSH ONLY:
    //
    // Device -> Room -> Supabase
    //
    // Supabase scans are never imported back into Room.
    // ============================================================


    // ============================================================
    // SOFT DELETE EXACT SCAN IN SUPABASE
    // ============================================================

    data class SoftDeleteResult(
        val success: Boolean,
        val statusCode: Int,
        val responseBody: String
    )

    suspend fun softDeleteScanFromCloud(
        deviceId: String,
        barcode: String,
        rackNo: String,
        date: String,
        time: String
    ): SoftDeleteResult {
        return withContext(Dispatchers.IO) {
            try {
                val updatePayload = JSONObject().apply {
                    put("is_deleted", true)
                }

                val url = URL(
                    "$SUPABASE_URL/rest/v1/scans" +
                            "?device_id=eq.${encodeUrlParam(deviceId)}" +
                            "&barcode=eq.${encodeUrlParam(barcode)}" +
                            "&rack_no=eq.${encodeUrlParam(rackNo)}" +
                            "&date=eq.${encodeUrlParam(date)}" +
                            "&time=eq.${encodeUrlParam(time)}"
                )

                val connection =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("apikey", SUPABASE_KEY)
                        setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Prefer", "return=representation")
                        doOutput = true
                    }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(updatePayload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val stream =
                    if (responseCode in 200..299) connection.inputStream
                    else connection.errorStream

                val responseBody =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""

                connection.disconnect()

                val updatedCount =
                    try { JSONArray(responseBody).length() } catch (_: Exception) { 0 }

                val success = responseCode in 200..299 && updatedCount == 1

                if (!success) {
                    android.util.Log.e(
                        "SyncManager",
                        "Cloud soft-delete failed: status=$responseCode, updatedCount=$updatedCount, barcode=$barcode, device=$deviceId, rack=$rackNo, date=$date, time=$time, body=$responseBody"
                    )
                }

                SoftDeleteResult(success, responseCode, responseBody)

            } catch (e: Exception) {
                e.printStackTrace()
                SoftDeleteResult(false, -1, e.localizedMessage ?: "Unknown error")
            }
        }
    }

    // ============================================================
    // RESTORE EXACT SCAN IN SUPABASE
    // ============================================================

    suspend fun restoreScanInCloud(
        deviceId: String,
        barcode: String,
        rackNo: String,
        date: String,
        time: String
    ) {
        withContext(Dispatchers.IO) {

            try {

                val updatePayload = JSONObject().apply {
                    put("is_deleted", false)
                }

                val encodedDeviceId =
                    encodeUrlParam(deviceId)

                val encodedBarcode =
                    encodeUrlParam(barcode)

                val encodedRackNo =
                    encodeUrlParam(rackNo)

                val encodedDate =
                    encodeUrlParam(date)

                val encodedTime =
                    encodeUrlParam(time)

                val url = URL(
                    "$SUPABASE_URL/rest/v1/scans" +
                            "?device_id=eq.$encodedDeviceId" +
                            "&barcode=eq.$encodedBarcode" +
                            "&rack_no=eq.$encodedRackNo" +
                            "&date=eq.$encodedDate" +
                            "&time=eq.$encodedTime"
                )

                val connection =
                    (url.openConnection() as HttpURLConnection).apply {

                        requestMethod = "PATCH"

                        setRequestProperty(
                            "apikey",
                            SUPABASE_KEY
                        )

                        setRequestProperty(
                            "Authorization",
                            "Bearer $SUPABASE_KEY"
                        )

                        setRequestProperty(
                            "Content-Type",
                            "application/json"
                        )

                        setRequestProperty(
                            "Prefer",
                            "return=minimal"
                        )

                        doOutput = true
                    }

                val writer =
                    OutputStreamWriter(connection.outputStream)

                writer.write(updatePayload.toString())
                writer.flush()
                writer.close()

                connection.responseCode
                connection.disconnect()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    // ============================================================
    // HARD DELETE EXACT SCAN FROM SUPABASE
    // ============================================================

    data class HardDeleteResult(
        val success: Boolean,
        val statusCode: Int,
        val responseBody: String
    )

    suspend fun hardDeleteScanFromCloud(
        deviceId: String,
        barcode: String,
        rackNo: String,
        date: String,
        time: String
    ): HardDeleteResult {
        return withContext(Dispatchers.IO) {

            try {

                val encodedDeviceId = encodeUrlParam(deviceId)
                val encodedBarcode = encodeUrlParam(barcode)
                val encodedRackNo = encodeUrlParam(rackNo)
                val encodedDate = encodeUrlParam(date)
                val encodedTime = encodeUrlParam(time)

                val url = URL(
                    "$SUPABASE_URL/rest/v1/scans" +
                            "?device_id=eq.$encodedDeviceId" +
                            "&barcode=eq.$encodedBarcode" +
                            "&rack_no=eq.$encodedRackNo" +
                            "&date=eq.$encodedDate" +
                            "&time=eq.$encodedTime"
                )

                val connection =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        setRequestProperty("apikey", SUPABASE_KEY)
                        setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                        setRequestProperty("Prefer", "return=representation")
                    }

                val responseCode = connection.responseCode

                val stream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val responseBody =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""

                connection.disconnect()

                val deletedCount =
                    try {
                        JSONArray(responseBody).length()
                    } catch (_: Exception) {
                        0
                    }

                val success =
                    responseCode in 200..299 && deletedCount == 1

                if (!success) {
                    android.util.Log.e(
                        "SyncManager",
                        "Cloud delete failed: status=$responseCode, deletedCount=$deletedCount, barcode=$barcode, device=$deviceId, rack=$rackNo, date=$date, time=$time, body=$responseBody"
                    )
                }

                HardDeleteResult(
                    success = success,
                    statusCode = responseCode,
                    responseBody = responseBody
                )

            } catch (e: Exception) {

                e.printStackTrace()

                HardDeleteResult(
                    success = false,
                    statusCode = -1,
                    responseBody = e.localizedMessage ?: "Unknown error"
                )
            }
        }
    }
}
