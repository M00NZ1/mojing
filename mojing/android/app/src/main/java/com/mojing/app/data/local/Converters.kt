package com.mojing.app.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        try { gson.fromJson(value, object : TypeToken<List<String>>() {}.type) ?: emptyList() }
        catch (_: Exception) { emptyList() }

    @TypeConverter
    fun fromMapStringAny(value: Map<String, Any>): String = gson.toJson(value)

    @TypeConverter
    fun toMapStringAny(value: String): Map<String, Any> =
        try { gson.fromJson(value, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap() }
        catch (_: Exception) { emptyMap() }
}
