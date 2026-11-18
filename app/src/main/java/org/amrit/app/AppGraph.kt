package org.amrit.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.amrit.app.data.AndroidSql
import org.amrit.app.data.LocalDb
import org.amrit.app.net.ApiClient
import org.amrit.app.scan.DemoMode
import org.amrit.app.scan.OcrEngine
import org.amrit.app.scan.ScanService
import org.amrit.app.work.CourseReminderWorker
import org.amrit.app.work.SyncWorker
import org.amrit.core.BrandIndex
import org.amrit.core.Lang
import org.amrit.core.OcrLine
import org.amrit.core.OcrResult
import org.amrit.core.RefData
import org.amrit.core.Resolver
import org.amrit.core.RuleEngine
import org.amrit.core.ScorerConfig
import java.util.concurrent.TimeUnit

class AmritApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.warmUp()
    }
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
}

val Context.graph: AppGraph get() = (applicationContext as AmritApp).graph

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("amrit", Context.MODE_PRIVATE)
    /** last /v1/dashboard response and when it was fetched, shown when offline */
    var dashboardJson: String?
        get() = sp.getString("dashboardJson", null)
        set(v) = sp.edit().putString("dashboardJson", v).apply()
    var dashboardAt: Long
        get() = sp.getLong("dashboardAt", 0L)
        set(v) = sp.edit().putLong("dashboardAt", v).apply()
    var pincode: String?
        get() = sp.getString("pincode", null)
        set(v) = sp.edit().putString("pincode", v).apply()
    /** null until the user picked one on first launch */
    var language: Lang?
        get() = sp.getString("lang", null)?.let { Lang.of(it) } ?: if (sp.getBoolean("hindi", false)) Lang.HI else null
        set(v) = sp.edit().putString("lang", v?.code).apply()
    /** Larger text across the app (older eyes) */
    var largeText: Boolean
        get() = sp.getBoolean("largeText", false)
        set(v) = sp.edit().putBoolean("largeText", v).apply()
    /** Read each scan result aloud as soon as it appears */
    var autoRead: Boolean
        get() = sp.getBoolean("autoRead", false)
        set(v) = sp.edit().putBoolean("autoRead", v).apply()
    var demoMode: Boolean
        get() = sp.getBoolean("demo", false)
        set(v) = sp.edit().putBoolean("demo", v).apply()
    /** hidden: upload locked frames + OCR to the laptop to build the real-strip test set */
    var captureMode: Boolean
        get() = sp.getBoolean("capture", false)
        set(v) = sp.edit().putBoolean("capture", v).apply()
    var backendUrl: String
        get() = sp.getString("backend", BuildConfig.BACKEND_URL)!!
        set(v) = sp.edit().putString("backend", v.trimEnd('/')).apply()
    var aliasesSince: Long
        get() = sp.getLong("aliasesSince", 0)
        set(v) = sp.edit().putLong("aliasesSince", v).apply()
    var learnedSince: Long
        get() = sp.getLong("learnedSince", 0)
        set(v) = sp.edit().putLong("learnedSince", v).apply()
    /** expiry notices already shown: "cabinetId:yyyy-MM" */
    var expiryNotified: Set<String>
        get() = sp.getStringSet("expiryNotified", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("expiryNotified", v).apply()
    /** last CDSCO alert record id received from the backend */
    var alertsSince: Long
        get() = sp.getLong("alertsSince", 0)
        set(v) = sp.edit().putLong("alertsSince", v).apply()
    var profileId: Long
        get() = sp.getLong("profile", 1)
        set(v) = sp.edit().putLong("profile", v).apply()
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()
}

/** Hand-wired singletons. Small enough not to need a DI framework. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val local = LocalDb.create(app)
    val dao = local.dao()
    val ref: RefData by lazy { RefData(AndroidSql.open(app)) }
    val dbVersion: String by lazy { ref.meta["inputs_sha256"]?.take(12) ?: "dev" }
    val rules: RuleEngine by lazy { RuleEngine(ref) }
    val ocr by lazy { OcrEngine() }
    val codes by lazy { org.amrit.app.scan.CodeReader() }
    val api = ApiClient { settings.backendUrl }
    val scans by lazy { ScanService(this) }
    val demo by lazy { DemoMode(this) }

    val scorerConfig: ScorerConfig by lazy {
        runCatching {
            app.assets.open("scorer.json").bufferedReader().use { Json { ignoreUnknownKeys = true }.decodeFromString(ScorerConfig.serializer(), it.readText()) }
        }.getOrDefault(ScorerConfig())
    }

    /** Index over ~180k brand names; built once in the background at app start. */
    private lateinit var resolverJob: Deferred<Resolver>
    var indexBuildMs: Long = 0; private set

    fun warmUp() {
        resolverJob = scope.async {
            val t = System.nanoTime()
            val index = BrandIndex(ref.allBaseKeys())
            for (a in dao.aliases()) index.addAlias(a.phrase, a.baseKey)
            if (dao.profileCount() == 0) dao.addProfile(org.amrit.app.data.ProfileEntity(name = "", color = 0, createdAt = System.currentTimeMillis()))
            for (p in dao.learned()) if (ref.addLearned(p.baseKey, p.displayName, p.form, p.items) != null) index.addKey(p.baseKey)
            ref.aliases
            indexBuildMs = (System.nanoTime() - t) / 1_000_000
            Resolver(ref, index, scorerConfig).also { r ->
                // first match/rules call pays for class loading + caches; do it now, not on the first scan
                r.resolve(OcrResult(listOf(OcrLine("Dolo 650", 50f), OcrLine("Each tablet contains Paracetamol IP 650 mg", 20f))))
            }
        }
        // ML Kit initialises its model on first use (~0.3-0.5 s); warm it up so the first real scan is fast
        scope.launch { runCatching { ocr.run(android.graphics.Bitmap.createBitmap(64, 32, android.graphics.Bitmap.Config.ARGB_8888)) } }
        runCatching { // (absent in JVM screenshot tests)
            val wm = WorkManager.getInstance(app)
            wm.enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(netConstraint).build())
            // hourly: a due dose is reminded within the hour; UPDATE moves phones off the old 4-hour schedule
            wm.enqueueUniquePeriodicWork("course", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CourseReminderWorker>(1, TimeUnit.HOURS).build())
            requestSync()
        }
    }

    suspend fun resolver(): Resolver = resolverJob.await()
    val resolverReady: Boolean get() = ::resolverJob.isInitialized && resolverJob.isCompleted

    /** For the camera analyzer thread: null while the index is still building. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val resolverNow: Resolver? get() = if (resolverReady) resolverJob.getCompleted() else null

    /** A brand learned from a printed composition becomes matchable on this phone immediately. */
    fun registerLearned(baseKey: String, name: String, form: String, items: String) {
        if (ref.addLearned(baseKey, name, form, items) != null) resolverNow?.index?.addKey(baseKey)
    }

    fun reportLearnedAsync(baseKey: String, name: String, form: String, items: String) {
        scope.launch { runCatching { api.reportLearned(ApiClient.LearnedReport(baseKey, name, form, items, dbVersion)) } }
    }

    private val netConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestSync() = runCatching {
        WorkManager.getInstance(app).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(netConstraint).build())
    }

    fun reportAliasAsync(phrase: String, baseKey: String) {
        scope.launch { runCatching { api.reportAlias(ApiClient.AliasReport(phrase, baseKey, dbVersion)) } }
    }
}
