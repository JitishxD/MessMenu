package me.jitish.messmenu

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Hardcoded GitHub raw base for public menu data in this repo. */
object MenuRemoteConfig {
    const val BASE_URL = "https://raw.githubusercontent.com/JitishxD/MessMenu/main/data/"
    const val CATALOG_FILE = "catalog.json"
}

data class CatalogMenu(
    val id: String,
    val name: String,
    val file: String
)

data class MenuCatalog(
    val updatedAt: String,
    val menus: List<CatalogMenu>
)

sealed class MenuRefreshResult {
    data class Success(
        val catalog: MenuCatalog,
        val lastSuccessfulFetchAt: Long,
        val menusDownloaded: Boolean
    ) : MenuRefreshResult()

    data class Offline(val hasCache: Boolean) : MenuRefreshResult()

    data class Failed(val message: String, val hasCache: Boolean) : MenuRefreshResult()
}

data class CachedMenuState(
    val catalog: MenuCatalog?,
    val lastSuccessfulFetchAt: Long?
)

private val Context.menuDataStore by preferencesDataStore(name = "menu_cache_prefs")

private val LAST_SUCCESSFUL_FETCH_AT = longPreferencesKey("last_successful_fetch_at")

private val REQUIRED_MEALS = listOf("breakfast", "lunch", "high_tea", "dinner")

class MenuRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "menus").also { it.mkdirs() }
    private val catalogFile = File(cacheDir, MenuRemoteConfig.CATALOG_FILE)

    suspend fun loadCachedState(): CachedMenuState {
        val lastFetch = appContext.menuDataStore.data.map { prefs ->
            prefs[LAST_SUCCESSFUL_FETCH_AT]
        }.first()
        return CachedMenuState(
            catalog = readCachedCatalog(),
            lastSuccessfulFetchAt = lastFetch
        )
    }

    fun listCachedMenuFiles(): List<String> {
        val catalog = readCachedCatalog() ?: return emptyList()
        return catalog.menus
            .map { it.file }
            .filter { fileName ->
                val file = safeCacheFile(fileName) ?: return@filter false
                file.isFile && isValidMenuJson(file.readText())
            }
    }

    fun displayNameFor(fileName: String): String {
        val fromCatalog = readCachedCatalog()?.menus?.firstOrNull { it.file == fileName }?.name
        return fromCatalog ?: fileName.substringBeforeLast('.')
    }

    fun loadMessData(fileName: String): Map<MealType, Map<String, List<String>>> {
        val file = safeCacheFile(fileName) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return parseMessData(file.readText())
    }

    suspend fun refresh(): MenuRefreshResult {
        val hasCache = readCachedCatalog() != null && listCachedMenuFiles().isNotEmpty()
        val remoteCatalogText = try {
            fetchText(catalogUrl())
        } catch (_: IOException) {
            return MenuRefreshResult.Offline(hasCache = hasCache)
        } catch (e: Exception) {
            return MenuRefreshResult.Failed(
                message = e.message ?: "Refresh failed",
                hasCache = hasCache
            )
        }

        val remoteCatalog = try {
            parseCatalog(remoteCatalogText)
        } catch (e: Exception) {
            return MenuRefreshResult.Failed(
                message = e.message ?: "Invalid catalog",
                hasCache = hasCache
            )
        }

        val localCatalog = readCachedCatalog()
        val catalogUnchanged = localCatalog?.updatedAt == remoteCatalog.updatedAt &&
            localCatalog.menus == remoteCatalog.menus &&
            remoteCatalog.menus.all { entry ->
                val cached = safeCacheFile(entry.file)
                cached != null && cached.isFile && isValidMenuJson(cached.readText())
            }

        if (catalogUnchanged) {
            val now = System.currentTimeMillis()
            setLastSuccessfulFetchAt(now)
            return MenuRefreshResult.Success(
                catalog = remoteCatalog,
                lastSuccessfulFetchAt = now,
                menusDownloaded = false
            )
        }

        return try {
            downloadAndReplaceCache(remoteCatalog, remoteCatalogText)
            val now = System.currentTimeMillis()
            setLastSuccessfulFetchAt(now)
            MenuRefreshResult.Success(
                catalog = remoteCatalog,
                lastSuccessfulFetchAt = now,
                menusDownloaded = true
            )
        } catch (e: IOException) {
            MenuRefreshResult.Offline(hasCache = hasCache)
        } catch (e: Exception) {
            MenuRefreshResult.Failed(
                message = e.message ?: "Download failed",
                hasCache = hasCache
            )
        }
    }

    private fun downloadAndReplaceCache(catalog: MenuCatalog, catalogText: String) {
        val staging = File(appContext.cacheDir, "menus_staging_${System.currentTimeMillis()}")
        if (!staging.mkdirs()) {
            throw IOException("Could not create staging directory")
        }
        try {
            val stagedCatalog = File(staging, MenuRemoteConfig.CATALOG_FILE)
            stagedCatalog.writeText(catalogText)

            for (entry in catalog.menus) {
                val safeName = sanitizeFileName(entry.file)
                    ?: throw IOException("Unsafe menu filename: ${entry.file}")
                val body = fetchText(menuUrl(safeName))
                if (!isValidMenuJson(body)) {
                    throw IOException("Invalid menu JSON: $safeName")
                }
                File(staging, safeName).writeText(body)
            }

            // Replace cache: clear old menu files, then copy staged files in.
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.mkdirs()
            staging.listFiles()?.forEach { staged ->
                staged.copyTo(File(cacheDir, staged.name), overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun readCachedCatalog(): MenuCatalog? {
        if (!catalogFile.isFile) return null
        return runCatching { parseCatalog(catalogFile.readText()) }.getOrNull()
    }

    private suspend fun setLastSuccessfulFetchAt(epochMillis: Long) {
        appContext.menuDataStore.edit { prefs ->
            prefs[LAST_SUCCESSFUL_FETCH_AT] = epochMillis
        }
    }

    private fun catalogUrl(): String = MenuRemoteConfig.BASE_URL + MenuRemoteConfig.CATALOG_FILE

    private fun menuUrl(fileName: String): String {
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return MenuRemoteConfig.BASE_URL + encoded
    }

    private fun safeCacheFile(fileName: String): File? {
        val safeName = sanitizeFileName(fileName) ?: return null
        val file = File(cacheDir, safeName).canonicalFile
        if (!file.path.startsWith(cacheDir.canonicalPath + File.separator) &&
            file.canonicalPath != cacheDir.canonicalPath
        ) {
            return null
        }
        return file
    }

    private fun sanitizeFileName(fileName: String): String? {
        val name = File(fileName).name
        if (name.isBlank() || name != fileName) return null
        if (name.contains("..")) return null
        if (!name.endsWith(".json", ignoreCase = true)) return null
        return name
    }

    private fun fetchText(urlString: String): String {
        // Only allow our hardcoded HTTPS host — never follow user-controlled URLs.
        val url = URL(urlString)
        require(url.protocol.equals("https", ignoreCase = true)) { "HTTPS required" }
        require(url.host.equals("raw.githubusercontent.com", ignoreCase = true)) {
            "Unexpected host"
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for $urlString")
            }
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

fun parseCatalog(json: String): MenuCatalog {
    val root = JSONObject(json)
    val updatedAt = root.getString("updatedAt")
    val menusArray = root.getJSONArray("menus")
    val menus = buildList {
        for (i in 0 until menusArray.length()) {
            val obj = menusArray.getJSONObject(i)
            add(
                CatalogMenu(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    file = obj.getString("file")
                )
            )
        }
    }
    require(menus.isNotEmpty()) { "Catalog has no menus" }
    return MenuCatalog(updatedAt = updatedAt, menus = menus)
}

fun isValidMenuJson(json: String): Boolean = runCatching {
    val meals = JSONObject(json).getJSONObject("meals")
    REQUIRED_MEALS.all { meals.optJSONObject(it) != null }
}.getOrDefault(false)

fun parseMessData(jsonString: String): Map<MealType, Map<String, List<String>>> {
    return try {
        val jsonObject = JSONObject(jsonString)
        val mealsObject = jsonObject.getJSONObject("meals")
        val result = mutableMapOf<MealType, Map<String, List<String>>>()
        val mealMapping = mapOf(
            "breakfast" to MealType.BREAKFAST,
            "lunch" to MealType.LUNCH,
            "high_tea" to MealType.HIGH_TEA,
            "dinner" to MealType.DINNER
        )
        mealMapping.forEach { (jsonKey, mealType) ->
            val mealObj = mealsObject.getJSONObject(jsonKey)
            val dayMenu = mutableMapOf<String, List<String>>()
            mealObj.keys().forEach { day ->
                val itemsArray = mealObj.getJSONArray(day)
                dayMenu[day] = itemsArray.toStringList()
            }
            result[mealType] = dayMenu
        }
        result
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun JSONArray.toStringList(): List<String> {
    val items = mutableListOf<String>()
    for (i in 0 until length()) {
        items.add(getString(i))
    }
    return items
}

/** Relative age for the refresh chip, e.g. "just now", "10 days ago". */
fun formatLastRefreshed(epochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis == null) return "not yet"
    val delta = (nowMillis - epochMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        else -> "$days day${if (days == 1L) "" else "s"} ago"
    }
}

fun messNameFromFile(fileName: String): String = fileName.substringBeforeLast('.')

fun defaultMessFile(messFiles: List<String>): String? {
    return messFiles.firstOrNull { it.equals("Mayuri.json", ignoreCase = true) }
        ?: messFiles.firstOrNull()
}

fun sortedMessFiles(files: List<String>): List<String> =
    files.sortedBy { it.lowercase(Locale.ROOT) }
