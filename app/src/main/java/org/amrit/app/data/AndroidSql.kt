package org.amrit.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.amrit.core.Row
import org.amrit.core.Sql
import java.io.File

/** [Sql] over the read-only reference DB copied from assets on first launch (or when a new build ships a new DB). */
class AndroidSql(private val db: SQLiteDatabase) : Sql {
    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> {
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val row = object : Row {
                override fun int(i: Int) = c.getInt(i)
                override fun long(i: Int) = c.getLong(i)
                override fun double(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
                override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
            }
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(row)
            return out
        }
    }

    companion object {
        private const val ASSET = "amrit_ref.db"

        fun open(ctx: Context): AndroidSql {
            val f = File(ctx.noBackupFilesDir, ASSET)
            val stamp = File(ctx.noBackupFilesDir, "$ASSET.version")
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
            if (!f.exists() || !stamp.exists() || stamp.readText() != version) {
                val tmp = File(f.path + ".tmp")
                ctx.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                tmp.renameTo(f)
                stamp.writeText(version)
            }
            return AndroidSql(SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS))
        }
    }
}
