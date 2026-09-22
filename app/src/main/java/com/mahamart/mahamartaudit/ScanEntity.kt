package com.mahamart.mahamartaudit

import android.content.Context
import androidx.room.*

// --- SCAN ITEM ENTITY ---
@Entity(tableName = "scans")
data class ScanItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val quantity: Int,
    val rackNo: String,
    val date: String,
    val time: String,
    val deviceId: String = "DEV01",
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false
)

// --- MASTER SKU ENTITY ---
@Entity(tableName = "master_skus")
data class MasterSku(
    @PrimaryKey val barcode: String,
    val name: String
)

// --- DATA ACCESS OBJECT (DAO) ---
@Dao
interface ScanDao {

    // Scan operations
    @Insert
    suspend fun insertScan(scan: ScanItem)

    // Used when importing scans from Supabase. The identity of one scan is
    // barcode + rack + date + time + device. The check and insert are kept
    // inside one Room transaction so concurrent remote fetches cannot create
    // the same local row twice.
    @Query("""
        SELECT COUNT(*) FROM scans
        WHERE barcode = :barcode
          AND rackNo = :rackNo
          AND date = :date
          AND time = :time
          AND deviceId = :deviceId
    """)
    suspend fun countScanByIdentity(
        barcode: String,
        rackNo: String,
        date: String,
        time: String,
        deviceId: String
    ): Int

    @Transaction
    suspend fun insertRemoteScanIfMissing(scan: ScanItem) {
        val exists = countScanByIdentity(
            barcode = scan.barcode,
            rackNo = scan.rackNo,
            date = scan.date,
            time = scan.time,
            deviceId = scan.deviceId
        ) > 0

        if (!exists) {
            insertScan(scan)
        }
    }

    // Orders active scans strictly from Oldest to Newest (ASC)
    @Query("SELECT * FROM scans WHERE isDeleted = 0 ORDER BY date ASC, time ASC, id ASC")
    suspend fun getAllActiveScans(): List<ScanItem>

    @Query("""
        SELECT * FROM scans
        WHERE isDeleted = 0
          AND deviceId = :deviceId
          AND barcode IN (:barcodes)
    """)
    suspend fun getActiveScansByBarcodes(
        barcodes: List<String>,
        deviceId: String
    ): List<ScanItem>

    // Orders deleted scans strictly from Oldest to Newest (ASC)
    @Query("SELECT * FROM scans WHERE isDeleted = 1 ORDER BY date ASC, time ASC, id ASC")
    suspend fun getDeletedScans(): List<ScanItem>

    @Query("UPDATE scans SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Query("UPDATE scans SET isDeleted = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun hardDelete(id: Long)

    // Bulk Delete operations
    @Query("UPDATE scans SET isDeleted = 1 WHERE id IN (:ids)")
    suspend fun softDeleteList(ids: List<Long>)

    @Query("DELETE FROM scans WHERE id IN (:ids)")
    suspend fun hardDeleteList(ids: List<Long>)

    // Sync operations for Supabase
    @Query("SELECT * FROM scans WHERE (isSynced = 0 OR isSynced IS NULL) AND isDeleted = 0")
    suspend fun getUnsyncedScans(): List<ScanItem>

    @Query("UPDATE scans SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("UPDATE scans SET isSynced = 0 WHERE isDeleted = 0")
    suspend fun markAllAsUnsynced()

    // Master SKU operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMasterSkus(skus: List<MasterSku>)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMasterSkusBatch(skus: List<MasterSku>)

    @Query("DELETE FROM master_skus")
    suspend fun clearMasterSkuTable()

    @Query("SELECT COUNT(*) FROM master_skus")
    suspend fun getSkuMasterCount(): Int

    @Query("SELECT * FROM master_skus WHERE barcode = :barcode LIMIT 1")
    suspend fun getMasterSkuByBarcode(barcode: String): MasterSku?

    // Predictive Search Query for SKU Search Mode (Prioritizes 5-digit SKU codes first)
    @Query("""
        SELECT * FROM master_skus 
        WHERE name LIKE '%' || :query || '%' 
        ORDER BY LENGTH(barcode) ASC, name ASC 
        LIMIT 20
    """)
    suspend fun searchSkusByName(query: String): List<MasterSku>

    // Reverse lookup: Finds the first SKU code associated with the scanned item's name
    @Query("""
        SELECT barcode FROM master_skus 
        WHERE name = (SELECT name FROM master_skus WHERE barcode = :scannedBarcode LIMIT 1) 
        ORDER BY LENGTH(barcode) ASC 
        LIMIT 1
    """)
    suspend fun getPrimarySkuCodeByBarcode(scannedBarcode: String): String?
}

// --- ROOM DATABASE ---
@Database(entities = [ScanItem::class, MasterSku::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "audit_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}