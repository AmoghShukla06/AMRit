package org.amrit.core

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** JDBC implementation of [Sql] for the JVM (unit tests, eval harness). The app uses an Android SQLiteDatabase one. */
class JdbcSql(private val conn: Connection) : Sql {
    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = object : Row {
                    override fun int(i: Int) = rs.getInt(i + 1)
                    override fun long(i: Int) = rs.getLong(i + 1)
                    override fun double(i: Int): Double? = rs.getDouble(i + 1).let { if (rs.wasNull()) null else it }
                    override fun string(i: Int): String? = rs.getString(i + 1)
                }
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                return out
            }
        }
    }

    companion object {
        fun open(path: String) = JdbcSql(DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro"))
    }
}
