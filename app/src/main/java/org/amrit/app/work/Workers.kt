package org.amrit.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.amrit.app.data.LearnedAliasEntity
import org.amrit.app.graph

/** Uploads queued anonymous events and pulls brand aliases other phones' fallbacks have taught the server. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        return try {
            while (true) {
                val batch = g.dao.pending(100)
                if (batch.isEmpty()) break
                if (!g.api.postEvents(batch.map { it.json })) return Result.retry()
                g.dao.deleteEvents(batch.map { it.eventId })
            }
            val list = g.api.fetchAliases(g.settings.aliasesSince, g.dbVersion)
            for (a in list.aliases) {
                g.dao.putAlias(LearnedAliasEntity(a.phrase, a.base_key, "server", System.currentTimeMillis()))
                if (g.resolverReady) g.resolver().index.addAlias(a.phrase, a.base_key)
            }
            if (list.now > 0) g.settings.aliasesSince = list.now
            val learned = g.api.fetchLearned(g.settings.learnedSince, g.dbVersion)
            for (p in learned.products) {
                g.dao.putLearned(org.amrit.app.data.LearnedProductEntity(p.brand_key, p.display_name, p.form, p.items, System.currentTimeMillis()))
                g.registerLearned(p.brand_key, p.display_name, p.form, p.items)
            }
            if (learned.now > 0) g.settings.learnedSince = learned.now
            syncAlerts(g)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * New CDSCO alert records (the whole list, so no batch numbers leave the phone), then a re-check of saved medicines:
 * a medicine whose batch was flagged after it was saved gets one notification and a mark in My medicines.
 */
private suspend fun SyncWorker.syncAlerts(g: org.amrit.app.AppGraph) {
    while (true) {
        val list = g.api.fetchBatchAlerts(g.settings.alertsSince)
        if (list.alerts.isEmpty()) break
        g.dao.putAlerts(list.alerts.map {
            org.amrit.app.data.BatchAlertEntity(it.id, it.batch_norm, it.batch, it.product, it.brand, it.manufacturer, it.result, it.kind,
                it.reported_by, it.month)
        })
        g.settings.alertsSince = list.last_id
        if (list.alerts.size < 5000) break
    }
    val flagged = g.scans.recheckCabinet()
    if (flagged.isEmpty()) return
    val ctx = applicationContext
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val lang = g.settings.language ?: org.amrit.core.Lang.EN
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("alerts", org.amrit.core.I18n.t(lang, "notif.alert.channel"), NotificationManager.IMPORTANCE_HIGH))
    val open = android.app.PendingIntent.getActivity(ctx, 0, android.content.Intent(ctx, org.amrit.app.ui.MainActivity::class.java),
        android.app.PendingIntent.FLAG_IMMUTABLE)
    for ((row, a) in flagged) {
        val kind = org.amrit.core.I18n.t(lang, if (a.kind == org.amrit.core.BatchAlert.Kind.SPURIOUS) "res.alert.spurious.title" else "res.alert.nsq.title")
        val n = NotificationCompat.Builder(ctx, "alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(org.amrit.core.I18n.t(lang, "notif.alert.title", "product" to row.displayName))
            .setContentText(kind)
            .setStyle(NotificationCompat.BigTextStyle().bigText(org.amrit.core.I18n.t(lang, "notif.alert.body",
                "kind" to kind, "batch" to a.batch, "month" to a.month)))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(100_000 + row.id.toInt(), n)
    }
}

/**
 * Reminders, in the user's language: an antibiotic dose that is due and not logged (finish the course), and a saved
 * medicine that expires this month or next (told once per medicine).
 */
class CourseReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val g = ctx.graph
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val lang = g.settings.language ?: org.amrit.core.Lang.EN
        fun t(k: String, vararg a: Pair<String, Any?>) = org.amrit.core.I18n.t(lang, k, *a)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = android.app.PendingIntent.getActivity(ctx, 0, android.content.Intent(ctx, org.amrit.app.ui.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()

        val courses = g.dao.activeCoursesNow()
        if (courses.isNotEmpty()) nm.createNotificationChannel(NotificationChannel("course", t("notif.course.channel"), NotificationManager.IMPORTANCE_DEFAULT))
        for (c in courses) {
            val due = org.amrit.core.Reminders.dosesDue(c.startedAt, c.dosesPerDay, c.days, now)
            if (c.dosesTaken >= due) continue
            val total = c.days * c.dosesPerDay
            val n = NotificationCompat.Builder(ctx, "course")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(t("notif.course.title", "antibiotic" to c.antibiotic))
                .setStyle(NotificationCompat.BigTextStyle().bigText(t("notif.course.body", "taken" to c.dosesTaken, "total" to total)))
                .setContentText(t("notif.course.body", "taken" to c.dosesTaken, "total" to total))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)  // hourly re-checks update quietly
                .build()
            NotificationManagerCompat.from(ctx).notify(c.id.toInt(), n)
        }

        val today = java.time.LocalDate.now()
        val told = g.settings.expiryNotified.toMutableSet()
        val expiring = g.dao.cabinetWithExpiry().mapNotNull { row ->
            val ym = runCatching { java.time.YearMonth.parse(row.expiry) }.getOrNull() ?: return@mapNotNull null
            if (!org.amrit.core.Reminders.expiresSoon(ym, today) || "${row.id}:$ym" in told) null else row to ym
        }
        if (expiring.isNotEmpty()) nm.createNotificationChannel(NotificationChannel("expiry", t("notif.expiry.channel"), NotificationManager.IMPORTANCE_DEFAULT))
        for ((row, ym) in expiring) {
            val expired = ym.atEndOfMonth().isBefore(today)
            val n = NotificationCompat.Builder(ctx, "expiry")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (expired) t("notif.expired.title", "product" to row.displayName)
                    else t("notif.expiry.title", "product" to row.displayName, "date" to org.amrit.core.Expiry.format(ym)))
                .setContentText(t("notif.expiry.body"))
                .setContentIntent(open).setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(200_000 + row.id.toInt(), n)
            told += "${row.id}:$ym"
        }
        g.settings.expiryNotified = told
        return Result.success()
    }
}
