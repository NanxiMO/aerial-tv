package app.orchard.tv.wallpaper

import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

enum class MixSource(val key: String) {
    Bing("bing"), Commons("commons"), Nasa("nasa"), Unsplash("unsplash"), Mine("mine");

    companion object {
        fun of(key: String): MixSource? = entries.firstOrNull { it.key == key }
    }
}

/** What the pictures are of; the sources that have such pictures are asked for them (see [MixSources]). */
enum class MixTheme(val key: String) {
    Nature("nature"), City("city"), Mountains("mountains"), Space("space"), Sea("sea");

    companion object {
        fun of(key: String): MixTheme? = entries.firstOrNull { it.key == key }
    }
}

/**
 * [width] and [height] are 0 when the source does not say how big the picture is. [thumb] is a small
 * version for the rows of pictures; [ping] is Unsplash's address to tell that the picture was used.
 */
data class MixPicture(
    val id: String,
    val source: MixSource,
    val url: String,
    val width: Int,
    val height: Int,
    val title: String,
    val credit: String,
    val thumb: String = "",
    val ping: String = "",
)

/** Which picture the auto wallpaper shows next, and when. No Android here, so it is tested on the JVM. */
object MixPick {
    private const val UHD = 3840
    private const val HD = 1920

    class Result(val picture: MixPicture, val shown: Set<String>)

    /** Pictures at 3840 px or more (or of unknown size); if there are none, those of 1920 px or more. */
    fun pool(all: List<MixPicture>): List<MixPicture> {
        val uhd = all.filter { it.width == 0 || it.width >= UHD }
        return uhd.ifEmpty { all.filter { it.width >= HD } }
    }

    /**
     * A random picture of [pool] not in [shown]. When all have been shown the round starts again,
     * and the picture just shown ([last]) is not the first of it (unless it is the only one).
     */
    fun next(pool: List<MixPicture>, shown: Set<String>, last: String?, random: Random): Result? {
        if (pool.isEmpty()) return null
        val fresh = pool.filter { it.id !in shown }
        if (fresh.isNotEmpty()) return fresh.random(random).let { Result(it, shown + it.id) }
        val again = pool.filter { it.id != last }.ifEmpty { pool }
        return again.random(random).let { Result(it, setOf(it.id)) }
    }

    /** True once [intervalMinutes] have passed since the last change, or when the clock went back. */
    fun isDue(lastChangeMs: Long, nowMs: Long, intervalMinutes: Int): Boolean =
        nowMs < lastChangeMs || nowMs - lastChangeMs >= intervalMinutes * 60_000L

    fun parseHistory(text: String?): Set<String> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun writeHistory(shown: Set<String>): String = JSONArray(shown.toList()).toString()

    /** The chosen sources as kept in the settings; anything unreadable or empty means Bing alone. */
    fun parseSources(text: String?): Set<MixSource> =
        text.orEmpty().split(',').mapNotNull { MixSource.of(it.trim()) }.toSet().ifEmpty { setOf(MixSource.Bing) }

    fun writeSources(sources: Set<MixSource>): String = sources.joinToString(",") { it.key }

    /** [source] added or removed, except that the last one stays: with none there would be nothing to show. */
    fun toggled(sources: Set<MixSource>, source: MixSource): Set<MixSource> =
        if (source in sources) (sources - source).ifEmpty { sources } else sources + source

    /** A picture that would not download or decode goes into the history too, so it is not tried again. */
    fun afterDownload(ok: Boolean, id: String, shown: Set<String>): Set<String> = if (ok) shown else shown + id

    /** What the wallpaper is until the auto wallpaper has a picture: the daily photo, then a built-in one. */
    fun fallbackOrder(hasDaily: Boolean): List<String> =
        (if (hasDaily) listOf("daily:latest") else emptyList()) + "grad:aurora"

    fun writePicture(p: MixPicture): String = pictureJson(p).toString()

    fun readPicture(text: String?): MixPicture? = runCatching { pictureOf(JSONObject(text)) }.getOrNull()

    fun writePictures(list: List<MixPicture>): String = JSONArray(list.map(::pictureJson)).toString()

    fun readPictures(text: String?): List<MixPicture> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { runCatching { pictureOf(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun pictureJson(p: MixPicture) = JSONObject()
        .put("id", p.id).put("source", p.source.key).put("url", p.url)
        .put("width", p.width).put("height", p.height).put("title", p.title).put("credit", p.credit)
        .put("thumb", p.thumb).put("ping", p.ping)

    private fun pictureOf(o: JSONObject) = MixPicture(
        o.getString("id"), MixSource.of(o.getString("source")) ?: error("source"), o.getString("url"),
        o.getInt("width"), o.getInt("height"), o.getString("title"), o.getString("credit"),
        o.optString("thumb"), o.optString("ping"),
    )

    fun withoutHidden(pool: List<MixPicture>, hidden: Set<String>): List<MixPicture> = pool.filter { it.id !in hidden }

    /** The file name (without extension) a picture kept in "My Photos" gets: the same for the same picture, so it is kept once. */
    fun savedName(pictureId: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(pictureId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

    /** "One a Day" is gone; where it was chosen the auto wallpaper takes over with "My Photos" once a day. */
    fun migrateSelected(selected: String): Pair<String, Int>? = if (selected == "photos:rotate") "mix:auto" to 1440 else null
}
