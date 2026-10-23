package app.orchard.tv.wallpaper

import java.net.URLEncoder
import org.json.JSONObject

/**
 * What each source's answer says about pictures worth putting on the wall. No Android and no
 * network here (the callers fetch), so it is tested on the JVM against saved answers.
 */
object MixSources {
    private val COMMONS_BY_THEME = mapOf(
        MixTheme.Nature to listOf("landscapes"),
        MixTheme.City to listOf("cityscapes", "architecture"),
        MixTheme.Mountains to listOf("mountains"),
        MixTheme.Sea to listOf("beaches"),
    )

    /** The Commons categories for the chosen themes; none chosen means all. A theme with no category gives none. */
    fun commonsCategories(themes: Set<MixTheme>): List<String> =
        MixTheme.entries.filter { themes.isEmpty() || it in themes }
            .flatMap { COMMONS_BY_THEME[it].orEmpty() }.map { "Featured pictures of $it" }

    fun nasaQueries(themes: Set<MixTheme>): List<String> =
        if (themes.isEmpty() || MixTheme.Space in themes) NASA_QUERIES else emptyList()

    /** The chosen sources that can give something now: Unsplash needs a key; Commons and NASA need a category or search for the themes. */
    fun activeSources(themes: Set<MixTheme>, sources: Set<MixSource>, unsplashKey: String): Set<MixSource> = sources.filter {
        when (it) {
            MixSource.Unsplash -> unsplashKey.isNotBlank()
            MixSource.Commons -> commonsCategories(themes).isNotEmpty()
            MixSource.Nasa -> nasaQueries(themes).isNotEmpty()
            else -> true
        }
    }.toSet()

    private val UNSPLASH_QUERY = mapOf(
        MixTheme.Nature to "landscape", MixTheme.City to "cityscape", MixTheme.Mountains to "mountains",
        MixTheme.Space to "milky way", MixTheme.Sea to "sea",
    )

    /** Any call that needs the key and costs little: the list of topics. */
    const val UNSPLASH_CHECK_URL = "https://api.unsplash.com/topics?per_page=1"

    /**
     * What Unsplash's answer to [UNSPLASH_CHECK_URL] says of a key: accepted (a key over its hourly
     * limit answers 403 and is still the owner's), refused (401), or nothing to go on (no answer,
     * a server error): the owner is asked to try again rather than told the key is wrong.
     */
    fun unsplashKeyVerdict(code: Int?): KeyCheck = when (code) {
        in 200..299, 403 -> KeyCheck.Valid
        401 -> KeyCheck.Invalid
        else -> KeyCheck.Unverified
    }

    /** Without a theme the curated "wallpapers" topic: a plain search for "nature" also finds people. */
    fun unsplashUrl(theme: MixTheme?, count: Int = 30): String =
        "https://api.unsplash.com/photos/random?count=$count&orientation=landscape" +
            (theme?.let { "&query=${enc(UNSPLASH_QUERY.getValue(it))}" } ?: "&topics=wallpapers")

    /** NASA's image library has no "best of"; these searches give pictures that suit a wall. */
    val NASA_QUERIES = listOf("nebula", "galaxy", "earth from space", "aurora", "milky way", "james webb")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun bingUrl(idx: Int, market: String) =
        "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=${enc(market)}"

    fun nasaUrl(query: String, page: Int) =
        "https://images-api.nasa.gov/search?q=${enc(query)}&media_type=image&year_start=2000&page_size=100&page=$page"

    fun commonsUrl(category: String, cursor: String?): String =
        "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers" +
            "&gcmtitle=${enc("Category:$category")}&gcmtype=file&gcmlimit=50&prop=imageinfo" +
            "&iiprop=${enc("url|size|mime|extmetadata")}&iiextmetadatafilter=${enc("Artist|LicenseShortName|ObjectName")}" +
            "&iiurlwidth=3840" + (cursor?.let { "&gcmcontinue=${enc(it)}" } ?: "")

    fun parseBing(json: String): List<MixPicture> {
        val images = JSONObject(json).getJSONArray("images")
        return (0 until images.length()).map { images.getJSONObject(it) }.map { img ->
            val copyright = img.optString("copyright")
            MixPicture(
                id = "bing:" + img.getString("startdate"),
                source = MixSource.Bing,
                url = "https://cn.bing.com" + img.getString("urlbase") + "_UHD.jpg",
                width = 3840, height = 2160,
                title = img.optString("title").ifBlank { copyright.substringBefore(" (") },
                credit = copyright,
                thumb = "https://cn.bing.com" + img.getString("urlbase") + "_400x240.jpg",
            )
        }
    }

    /** The original of each hit; its size is in the answer only for some, and 0 where it is not. */
    fun parseNasa(json: String): List<MixPicture> {
        val items = JSONObject(json).getJSONObject("collection").getJSONArray("items")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val data = item.getJSONArray("data").getJSONObject(0)
            val links = item.optJSONArray("links") ?: return@mapNotNull null
            val orig = (0 until links.length()).map { links.getJSONObject(it) }
                .firstOrNull { it.optString("href").endsWith("~orig.jpg") } ?: return@mapNotNull null
            val w = orig.optInt("width", 0)
            val h = orig.optInt("height", 0)
            // Portrait and very wide pictures do not fit a TV.
            if (w > 0 && h > 0 && w.toDouble() / h !in 1.0..2.4) return@mapNotNull null
            val photographer = data.optString("photographer").trim()
            MixPicture(
                id = "nasa:" + data.getString("nasa_id"),
                source = MixSource.Nasa,
                url = orig.getString("href").replace("http://", "https://"),
                width = w, height = h,
                title = data.optString("title"),
                credit = if (photographer.isEmpty()) "NASA" else "$photographer / NASA",
                thumb = orig.getString("href").replace("http://", "https://").replace("~orig.jpg", "~thumb.jpg"),
            )
        }
    }

    /** Photos from `/photos/random`, with the big picture and a small one cut from the same raw address. */
    fun parseUnsplash(json: String): List<MixPicture> {
        val photos = org.json.JSONArray(json)
        return (0 until photos.length()).map { photos.getJSONObject(it) }.map { photo ->
            val raw = photo.getJSONObject("urls").getString("raw")
            val author = photo.getJSONObject("user").getString("name")
            MixPicture(
                id = "unsplash:" + photo.getString("id"),
                source = MixSource.Unsplash,
                url = "$raw&w=3840&fm=jpg&q=85",
                width = photo.optInt("width"), height = photo.optInt("height"),
                title = listOf("description", "alt_description").map { photo.optString(it).takeIf { s -> s != "null" }.orEmpty() }
                    .firstOrNull { it.isNotBlank() } ?: "Unsplash",
                credit = "Photo by $author on Unsplash",
                thumb = "$raw&w=400&fm=jpg",
                ping = photo.getJSONObject("links").optString("download_location"),
            )
        }
    }

    /** JPEGs of 3840 px or more in a shape a TV can show; panoramas and tall ones stay out. */
    fun parseCommons(json: String): List<MixPicture> {
        val pages = JSONObject(json).getJSONObject("query").getJSONObject("pages")
        return pages.keys().asSequence().map { pages.getJSONObject(it) }.mapNotNull { page ->
            val info = page.optJSONArray("imageinfo")?.getJSONObject(0) ?: return@mapNotNull null
            val w = info.optInt("width")
            val h = info.optInt("height")
            if (info.optString("mime") != "image/jpeg" || w < 3840 || h <= 0 || w.toDouble() / h !in 1.3..2.0) return@mapNotNull null
            val meta = info.optJSONObject("extmetadata")
            fun field(name: String) = plainText(meta?.optJSONObject(name)?.optString("value").orEmpty())
            val title = page.getString("title")
            MixPicture(
                id = "commons:$title",
                source = MixSource.Commons,
                url = info.optString("thumburl").ifBlank { info.getString("url") },
                width = w, height = h,
                title = field("ObjectName").ifBlank { title.removePrefix("File:").substringBeforeLast('.') },
                credit = listOf(field("Artist"), field("LicenseShortName")).filter { it.isNotBlank() }.joinToString(" · "),
                thumb = info.optString("thumburl").replace("/3840px-", "/500px-"),
            )
        }.sortedBy { it.id }.toList()
    }

    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#\\d+|amp|lt|gt|quot|apos|nbsp);")

    /** Text without markup: Commons gives credits as HTML. */
    fun plainText(html: String): String = TAG.replace(html, "").let { text ->
        ENTITY.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> e.drop(1).toInt().toChar().toString()
            }
        }
    }.trim()
}

/** What a check of an Unsplash key found. */
enum class KeyCheck { Valid, Invalid, Unverified }
