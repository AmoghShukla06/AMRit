package org.amrit.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** How a scan was resolved. ON_DEVICE + CONFIRMED + DEMO are the zero-API-call paths. */
enum class ScanPath { ON_DEVICE, CONFIRMED, CACHE_LOCAL, CACHE_SERVER, API, NOT_FOUND, DEMO, SEARCH }

@Entity(tableName = "scan_log")
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val path: String,
    val totalMs: Long,
    val ocrMs: Long,
    val matchMs: Long,
    val apiMs: Long,
    val apiCostUsd: Double,
    val confidence: Double,
    val productName: String?,
)

/** Events waiting to be uploaded (offline-first). Payload is the exact JSON the backend stores. */
@Entity(tableName = "pending_event")
data class PendingEventEntity(@PrimaryKey val eventId: String, val json: String, val createdAt: Long)

/** "My medicines": what the duplicate-dose and interaction checks run over. Product ids are reference-DB ids. */
@Entity(tableName = "cabinet")
data class CabinetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Int,
    val displayName: String,
    /** ingredients read from the strip that a truncated DB row was missing: "id:strength;..." */
    val extraIngredients: String = "",
    val addedAt: Long,
    /** whose medicine (family profile) */
    val profileId: Long = 1,
    /** expiry read from the strip, "yyyy-MM", or null */
    val expiry: String? = null,
    /** batch number read from the strip or its QR code, or null */
    val batch: String? = null,
    /** set when a CDSCO alert matched this batch: "SPURIOUS|AUG-2026" / "NSQ|SEP-2026" (shown + notified once) */
    val alert: String? = null,
)

/** CDSCO alert records received after the shipped reference DB was built (synced from the backend). */
@Entity(tableName = "batch_alert", indices = [androidx.room.Index("batchNorm")])
data class BatchAlertEntity(
    @PrimaryKey val serverId: Long, val batchNorm: String, val batch: String, val product: String, val brand: String?,
    val manufacturer: String?, val result: String?, val kind: String, val reportedBy: String?, val month: String,
)

/** A family member. Each has their own "My medicines"; double-dose and interaction checks run per person. */
@Entity(tableName = "profile")
data class ProfileEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val color: Int, val createdAt: Long)

@Entity(tableName = "course")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productName: String,
    val antibiotic: String,
    val startedAt: Long,
    val days: Int,
    val dosesPerDay: Int,
    val dosesTaken: Int = 0,
    val active: Boolean = true,
    val profileId: Long = 1,
    /** WHO AWaRe group of the antibiotic (Access / Watch / Reserve), for the passport */
    val aware: String? = null,
    /** the anonymous "with a prescription?" answer for this purchase, kept on the phone for the passport */
    val prescribed: Boolean? = null,
    /** when the course was removed (null while running or never removed) */
    val endedAt: Long? = null,
)

/** OCR phrase -> brand base key, learned from a fallback or a user confirmation (local) or pulled from the server. */
@Entity(tableName = "learned_alias")
data class LearnedAliasEntity(@PrimaryKey val phrase: String, val baseKey: String, val source: String, val ts: Long)

/** Brands learned from printed compositions (this phone's fallbacks, or promoted on the server). */
@Entity(tableName = "learned_product")
data class LearnedProductEntity(@PrimaryKey val baseKey: String, val displayName: String, val form: String, val items: String, val ts: Long)

/** Local cache of vision-model readings, keyed by a signature of the OCR tokens. */
@Entity(tableName = "llm_cache")
data class LlmCacheEntity(@PrimaryKey val signature: String, val readingJson: String, val ts: Long)

@Dao
interface AppDao {
    @Insert suspend fun log(e: ScanLogEntity)
    @Query("SELECT * FROM scan_log ORDER BY ts") suspend fun allLogs(): List<ScanLogEntity>
    @Query("DELETE FROM scan_log") suspend fun clearLogs()

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun queue(e: PendingEventEntity)
    /** same event again with more information (the prescription answer): replaces it if not yet uploaded */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun requeue(e: PendingEventEntity)
    @Query("SELECT * FROM pending_event ORDER BY createdAt LIMIT :n") suspend fun pending(n: Int): List<PendingEventEntity>
    @Query("DELETE FROM pending_event WHERE eventId IN (:ids)") suspend fun deleteEvents(ids: List<String>)
    @Query("SELECT COUNT(*) FROM pending_event") fun pendingCount(): Flow<Int>

    @Insert suspend fun addToCabinet(e: CabinetEntity)
    @Query("SELECT COUNT(*) FROM cabinet WHERE profileId=:p") suspend fun cabinetCount(p: Long): Int
    @Query("SELECT productId FROM cabinet WHERE profileId=:p") suspend fun cabinetProductIds(p: Long): List<Int>
    @Query("SELECT * FROM cabinet ORDER BY addedAt DESC") fun cabinet(): Flow<List<CabinetEntity>>
    @Query("SELECT * FROM cabinet WHERE profileId=:p ORDER BY addedAt DESC") fun cabinetOf(p: Long): Flow<List<CabinetEntity>>

    @Insert suspend fun addProfile(p: ProfileEntity): Long
    @Query("SELECT * FROM profile ORDER BY id") fun profiles(): Flow<List<ProfileEntity>>
    @Query("SELECT COUNT(*) FROM profile") suspend fun profileCount(): Int
    @Query("DELETE FROM cabinet WHERE id=:id") suspend fun removeFromCabinet(id: Long)

    @Insert suspend fun startCourse(c: CourseEntity)
    @Query("SELECT * FROM course WHERE active=1 ORDER BY startedAt DESC") fun activeCourses(): Flow<List<CourseEntity>>
    @Query("SELECT * FROM course WHERE active=1 AND profileId=:p ORDER BY startedAt DESC") fun activeCoursesOf(p: Long): Flow<List<CourseEntity>>
    @Query("SELECT * FROM course WHERE active=1") suspend fun activeCoursesNow(): List<CourseEntity>
    @Query("UPDATE course SET dosesTaken = dosesTaken + 1 WHERE id=:id") suspend fun takeDose(id: Long)
    @Query("UPDATE course SET active=0, endedAt=:at WHERE id=:id") suspend fun endCourse(id: Long, at: Long)
    @Query("SELECT * FROM course WHERE profileId=:p ORDER BY startedAt DESC") suspend fun coursesOf(p: Long): List<CourseEntity>
    @Query("UPDATE course SET prescribed=:rx WHERE productName=:name AND profileId=:p AND startedAt>=:since") suspend fun setCoursePrescribed(name: String, p: Long, since: Long, rx: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAlias(a: LearnedAliasEntity)
    @Query("SELECT * FROM learned_alias") suspend fun aliases(): List<LearnedAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLearned(p: LearnedProductEntity)
    @Query("SELECT * FROM learned_product") suspend fun learned(): List<LearnedProductEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAlerts(a: List<BatchAlertEntity>)
    @Query("SELECT * FROM batch_alert WHERE batchNorm=:norm") suspend fun alertsFor(norm: String): List<BatchAlertEntity>
    @Query("SELECT * FROM cabinet WHERE batch IS NOT NULL AND alert IS NULL") suspend fun cabinetToRecheck(): List<CabinetEntity>
    @Query("SELECT * FROM cabinet WHERE expiry IS NOT NULL") suspend fun cabinetWithExpiry(): List<CabinetEntity>
    @Query("UPDATE cabinet SET alert=:alert WHERE id=:id") suspend fun setCabinetAlert(id: Long, alert: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLlm(c: LlmCacheEntity)
    @Query("SELECT * FROM llm_cache WHERE signature=:sig") suspend fun getLlm(sig: String): LlmCacheEntity?
}

@Database(
    entities = [ScanLogEntity::class, PendingEventEntity::class, CabinetEntity::class, CourseEntity::class,
        LearnedAliasEntity::class, LlmCacheEntity::class, LearnedProductEntity::class, ProfileEntity::class, BatchAlertEntity::class],
    version = 6, exportSchema = false,
)
abstract class LocalDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** 4 -> 5: batch + CDSCO alert state on saved medicines, and the synced alert table. Keeps everyone's medicines. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cabinet ADD COLUMN batch TEXT")
                db.execSQL("ALTER TABLE cabinet ADD COLUMN alert TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS batch_alert (serverId INTEGER NOT NULL, batchNorm TEXT NOT NULL, batch TEXT NOT NULL, " +
                    "product TEXT NOT NULL, brand TEXT, manufacturer TEXT, result TEXT, kind TEXT NOT NULL, reportedBy TEXT, month TEXT NOT NULL, " +
                    "PRIMARY KEY(serverId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_batch_alert_batchNorm ON batch_alert (batchNorm)")
            }
        }

        /** 5 -> 6: the antibiotic passport (AWaRe group, prescription answer and end time on each course). */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course ADD COLUMN aware TEXT")
                db.execSQL("ALTER TABLE course ADD COLUMN prescribed INTEGER")
                db.execSQL("ALTER TABLE course ADD COLUMN endedAt INTEGER")
            }
        }

        // versions before 4 were pre-release builds only: those still reset
        fun create(ctx: Context) = Room.databaseBuilder(ctx, LocalDb::class.java, "amrit_local.db")
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6).fallbackToDestructiveMigrationFrom(1, 2, 3).build()
    }
}
