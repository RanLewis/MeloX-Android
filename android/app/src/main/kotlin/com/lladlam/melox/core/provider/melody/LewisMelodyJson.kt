package com.lladlam.melox.core.provider.melody

import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端字段的取值助手。
 *
 * `org.json` 的两个坑决定了这些扩展必须存在：
 *   1. `optString` 对显式 `null` 返回字符串 `"null"`，直接拿去当歌手名会显示成 "null"；
 *   2. `JSONArray` 不是 `Iterable`，`for (item in array)` 编译不过。
 */
internal fun JSONObject.str(key: String): String {
    if (isNull(key)) return ""
    return optString(key).trim()
}

internal fun JSONObject.longOrNull(key: String): Long? {
    if (isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

internal fun JSONObject.intOrNull(key: String): Int? = longOrNull(key)?.let { value ->
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) null else value.toInt()
}

internal fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

internal fun JSONObject.array(key: String): JSONArray? = if (isNull(key)) null else optJSONArray(key)

internal fun JSONArray.objects(): List<JSONObject> {
    val result = ArrayList<JSONObject>(length())
    for (index in 0 until length()) optJSONObject(index)?.let(result::add)
    return result
}

internal fun JSONArray.longs(): List<Long> {
    val result = ArrayList<Long>(length())
    for (index in 0 until length()) {
        when (val value = opt(index)) {
            is Number -> result.add(value.toLong())
            is String -> value.trim().toLongOrNull()?.let(result::add)
            else -> Unit
        }
    }
    return result
}
