package io.max.paperplay.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

// Typed reads of the loosely typed wire messages. A missing or wrongly typed required field throws, like a
// failed cast in the Dart code; the caller (a merge, a message handler) catches and logs the type only.

internal val wireJson = Json

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.reqStr(key: String): String = str(key) ?: throw IllegalArgumentException("bad field $key")
internal fun JsonObject.reqLng(key: String): Long = lng(key) ?: throw IllegalArgumentException("bad field $key")
internal fun JsonObject.reqFlag(key: String): Boolean = flag(key) ?: throw IllegalArgumentException("bad field $key")
internal fun JsonObject.reqArr(key: String): JsonArray = arr(key) ?: throw IllegalArgumentException("bad field $key")

internal fun JsonElement.longAt(): Long = (this as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("bad number")
internal fun JsonElement.stringAt(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("bad string")
internal fun JsonElement.longOrNullAt(): Long? = if (this is JsonNull) null else longAt()
internal fun JsonElement.list(): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("bad list")

internal fun String?.orNull() = this?.let { JsonPrimitive(it) } ?: JsonNull
internal fun Long?.orNull() = this?.let { JsonPrimitive(it) } ?: JsonNull
