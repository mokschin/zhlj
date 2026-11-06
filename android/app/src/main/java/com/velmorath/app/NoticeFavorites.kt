package com.velmorath.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Keep a snapshot so saved notices remain available after the feed's sync window expires. */
internal class NoticeFavorites(context: Context) {
    private val prefs = context.getSharedPreferences("notice_favorites", Context.MODE_PRIVATE)
    fun items(): List<CampusItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            CampusItem(title = o.getString("title"), id = o.optString("id"), url = o.optString("url"),
                source = o.optString("source"), sourceId = o.optString("sourceId"), publishedAt = o.optString("publishedAt"),
                subtitle = o.optString("subtitle"), detail = o.optString("detail"), content = o.optString("content"))
        }
    }.getOrDefault(emptyList())
    fun contains(item: CampusItem) = items().any { key(it) == key(item) }
    fun toggle(item: CampusItem): Boolean {
        val all = items().associateBy(::key).toMutableMap()
        val saved = all.remove(key(item)) == null
        if (saved) all[key(item)] = item
        prefs.edit().putString("items", JSONArray(all.values.map { value ->
            JSONObject().put("title", value.title).put("id", value.id).put("url", value.url)
                .put("source", value.source).put("sourceId", value.sourceId).put("publishedAt", value.publishedAt)
                .put("subtitle", value.subtitle).put("detail", value.detail).put("content", value.content)
        }).toString()).apply()
        return saved
    }
    companion object {
        fun key(item: CampusItem): String = JSONArray(listOf(item.sourceId.ifBlank { item.source },
            when { item.id.isNotBlank() -> "id:${item.id}"; item.url.isNotBlank() -> "url:${item.url}"; else -> "${item.title}|${item.publishedAt}" })).toString()
    }
}
