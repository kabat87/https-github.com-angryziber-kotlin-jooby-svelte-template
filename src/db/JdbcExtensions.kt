package db

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset.UTC
import java.util.*
import javax.sql.DataSource
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

fun DataSource.insert(table: String, values: Map<String, *>): Int = withConnection {
  val valuesByIndex = values.values.map { if (it is SelectMax) it.value else it }
  prepareStatement("""insert into $table (${values.keys.joinToString(",") { it }})
    values (${values.entries.joinToString(",") { (it.value as? SelectMax)?.sql(it.key, table) ?: "?" }})
  """).use { stmt ->
    stmt.set(valuesByIndex)
    stmt.executeUpdate()
  }
}

fun DataSource.exec(query: String, values: List<Any>? = null): Int = withConnection {
  prepareStatement(query).use { stmt ->
    if (values != null) stmt.set(values)
    stmt.executeUpdate()
  }
}

fun <R> DataSource.query(table: String, where: Map<String, Any?>, mapper: ResultSet.() -> R): List<R> = query(table, where, "", mapper)

fun <R> DataSource.query(table: String, where: Map<String, Any?>, suffix: String, mapper: ResultSet.() -> R): List<R> =
  select("select * from $table", where, suffix, mapper)

fun <R> DataSource.select(select: String, where: Map<String, Any?>, mapper: ResultSet.() -> R) = select(select, where, "", mapper)

fun <R> DataSource.select(select: String, where: Map<String, Any?>, suffix: String, mapper: ResultSet.() -> R): List<R> = withConnection {
  prepareStatement("$select${whereString(where)} $suffix").use { stmt ->
    stmt.set(where.values.filterNotNull())
    stmt.executeQuery().map(mapper)
  }
}

private fun <R> ResultSet.map(mapper: ResultSet.() -> R): List<R> {
  val result = mutableListOf<R>()
  while (next()) result += mapper()
  return result
}

fun <R> DataSource.query(table: String, id: UUID, mapper: ResultSet.() -> R): R =
  query(table, mapOf("id" to id), mapper).first()

fun DataSource.update(table: String, where: Map<String, Any?>, values: Map<String, *>): Int = withConnection {
  val setString = values.keys.joinToString { "$it=?" }
  prepareStatement("update $table set $setString${whereString(where)}").use { stmt ->
    stmt.set(values.values + where.values)
    stmt.executeUpdate()
  }
}

fun DataSource.delete(table: String, where: Map<String, Any?>): Int = withConnection {
  prepareStatement("delete from $table${whereString(where)}").use { stmt ->
    stmt.set(where.values)
    stmt.executeUpdate()
  }
}

private fun whereString(where: Map<String, Any?>) = if (where.isNotEmpty()) " where " +
  where.entries.joinToString(" and ") { (k, v) -> whereExpr(k, v) } else ""

private fun inExpr(k: String, v: Iterable<*>) = "$k in (${v.joinToString { "?" }})"
private fun whereExpr(k: String, v: Any?) = when(v) {
  null -> "$k is null"
  is Iterable<*> -> inExpr(k, v)
  is Array<*> -> inExpr(k, v.toList())
  is SqlOperator -> "$k ${v.op} ?"
  else -> "$k = ?"
}

operator fun PreparedStatement.set(i: Int, value: Any?): Unit = when (value) {
  is SqlOperator -> set(i, value.value)
  else -> setObject(i, toDBType(value))
}

fun PreparedStatement.set(values: Iterable<Any?>) = values
  .flatMap { it.toIterable() }
  .forEachIndexed { i, v -> this[i + 1] = v }

private fun Any?.toIterable(): Iterable<Any?> = when (this) {
  is Array<*> -> toList()
  is Iterable<*> -> this
  else -> listOf(this)
}

private fun toDBType(v: Any?): Any? = when(v) {
  is Enum<*> -> v.name
  is UUID -> v.toString()
  is Instant -> v.atOffset(UTC)
  is Period -> v.toString()
  else -> v
}

fun fromDBType(v: Any?, type: KType): Any? = when(type.jvmErasure) {
  Instant::class -> (v as Timestamp).toInstant()
  else -> v
}

fun ResultSet.getInstant(column: String) = getTimestamp(column).toInstant()
fun ResultSet.getInstantNullable(column: String) = getTimestamp(column)?.toInstant()

fun ResultSet.getLocalDate(column: String) = getDate(column).toLocalDate()
fun ResultSet.getLocalDateNullable(column: String) = getDate(column)?.toLocalDate()
fun ResultSet.getPeriod(column: String) = Period.parse(getString(column))
fun ResultSet.getPeriodNullable(column: String) = getString(column)?.let { Period.parse(it) }

fun ResultSet.getId(column: String = "id") = UUID.fromString(getString(column))
fun ResultSet.getIdNullable(column: String) = getString(column)?.let { UUID.fromString(it) }
fun ResultSet.getIntNullable(column: String) = getObject(column)?.let { (it as Number).toInt() }

fun String.toId(): UUID = UUID.fromString(this)

inline fun <reified T: Enum<T>> ResultSet.getEnum(column: String) = enumValueOf<T>(getString(column))
inline fun <reified T: Enum<T>> ResultSet.getEnumNullable(column: String): T? = getString(column)?.let { enumValueOf<T>(it) }

inline fun <reified T: Any> ResultSet.fromValues() = T::class.primaryConstructor!!.let { constructor ->
  constructor.call(*constructor.parameters.map { fromDBType(getObject(it.name), it.type) }.toTypedArray())
}

data class SelectMax(val by: Pair<String, Any>) {
  fun sql(field: String, table: String) = "(select coalesce(max($field), 0) + 1 from $table where ${by.first} = ?)"
  val value get() = by.second
}

data class SqlOperator(val op: String, val value: Any?)
infix fun Pair<String, String>.op(value: Any?) = first to SqlOperator(second, value)
