package io.max.paperplay.core.recs

import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class WeatherPlace(val name: String, val latitude: Double, val longitude: Double)

/** Open-Meteo needs no key. Only a chosen city's coordinates are ever sent. */
class WeatherService(private val http: HttpFetcher) {
    private fun obj(text: String) = Json.parseToJsonElement(text) as? JsonObject

    private suspend fun search(name: String): List<WeatherPlace> {
        val q = name.trim().replace(" ", "%20")
        val r = http.send(HttpRequest("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&format=json"))
        if (r.status != 200) return emptyList()
        val results = obj(r.text)?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            WeatherPlace(listOfNotNull(s("name"), s("admin1"), s("country")).joinToString(", "), lat, lon)
        }
    }

    /** The service only matches a bare place name, so "Kochi, India" is tried whole, then word by word. */
    suspend fun searchPlaces(query: String): List<WeatherPlace> {
        val whole = search(query)
        if (whole.isNotEmpty()) return whole
        val words = query.split(Regex("[,\\s]+")).filter { it.length > 1 }
        for (w in words) {
            val found = search(w)
            if (found.isEmpty()) continue
            val rest = words.filter { it != w }.map { it.lowercase() }
            return found.sortedByDescending { p -> rest.count { p.name.lowercase().contains(it) } }
        }
        return emptyList()
    }

    suspend fun kindAt(p: WeatherPlace): WeatherKind? {
        val r = http.send(HttpRequest("https://api.open-meteo.com/v1/forecast?latitude=${p.latitude}&longitude=${p.longitude}&current=weather_code"))
        if (r.status != 200) return null
        val code = ((obj(r.text)?.get("current") as? JsonObject)?.get("weather_code") as? JsonPrimitive)?.intOrNull ?: return null
        return weatherKindOf(code)
    }
}
