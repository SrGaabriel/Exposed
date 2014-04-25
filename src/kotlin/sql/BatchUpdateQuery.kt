package kotlin.sql

import java.util.*
import kotlin.dao.*

class BatchUpdateQuery(val table: IdTable) {
    val data = ArrayList<Pair<EntityID, HashMap<Column<*>, Any?>>>()

    fun addBatch(id: EntityID) {
        data.add(id to HashMap())
    }

    fun <T> set(column: Column<T>, value: T) {
        val values = data.last().second

        if (values containsKey column) {
            error("$column is already initialized")
        }

        values[column] = when(column.columnType) {
            is EnumerationColumnType<*> -> (value as Enum<*>).ordinal()
            is EntityIDColumnType -> if (value is EntityID) value.value else value
            else -> value
        }
    }

    fun execute(session: Session): Int {
        val updateSets = data filterNot {it.second.isEmpty()} groupBy { it.second.keySet() }
        return updateSets.values().fold(0) { acc, set ->
            acc + execute(session, set)
        }
    }

    private fun execute(session: Session, set: Collection<Pair<EntityID, HashMap<Column<*>, Any?>>>): Int {
        val sqlStatement = StringBuilder("UPDATE ${session.identity(table)} SET ")

        val columns = set.first().second.keySet().toList()

        sqlStatement.append(columns.map {"${session.identity(it)} = ?"}.makeString(", "))
        sqlStatement.append(" WHERE ${session.identity(table.id)} = ?")

        val sqlText = sqlStatement.toString()
        return session.exec(sqlText) {
            val stmt = session.prepareStatement(sqlText)
            for ((id, d) in set) {
                val idx = stmt.fillParameters(columns, d)
                stmt.setInt(idx, id.value)
                stmt.addBatch()
            }

            val count = stmt.executeBatch()!!

            assert(count.size == set.size, "Number of results don't match number of entries in batch")

            EntityCache.getOrCreate(session).clearReferrersCache()

            count.sum()
        }
    }
}
