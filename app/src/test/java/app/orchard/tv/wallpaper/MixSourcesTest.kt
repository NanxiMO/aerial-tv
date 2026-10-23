package app.orchard.tv.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixSourcesTest {
    private fun fixture(name: String) = javaClass.getResource("/mix/$name")!!.readText()

    @Test
    fun bingMakesUhdUrl() {
        val first = MixSources.parseBing(fixture("bing.json")).first()
        assertEquals("bing:20261022", first.id)
        assertEquals(MixSource.Bing, first.source)
        assertEquals("https://cn.bing.com/th?id=OHR.NaejangsanNP_EN-US7010419904_UHD.jpg", first.url)
        assertEquals(3840, first.width)
        assertEquals(2160, first.height)
        assertEquals("Still waters, vivid colors", first.title)
        assertEquals("Autumn in Naejangsan National Park, South Korea (© Twenty47studio/Getty Images)", first.credit)
    }

    @Test
    fun nasaKeepsOriginalsAndDropsPortraitsAndMissingOnes() {
        val list = MixSources.parseNasa(fixture("nasa.json"))
        assertEquals(
            listOf("GSFC_20171208_Archive_e001465", "hubble-sees-the-wings-of-a-butterfly-the-twin-jet-nebula_20283986193_o", "GSFC_20171208_Archive_e001518", "photog")
                .map { "nasa:$it" },
            list.map { it.id },
        )
        assertTrue(list.all { it.url.startsWith("https://") && it.url.endsWith("~orig.jpg") })
        assertEquals(0 to 0, list[0].width to list[0].height)
        assertEquals(1280 to 1070, list[1].width to list[1].height)
    }

    @Test
    fun nasaCreditNamesThePhotographerWhenThereIsOne() {
        val list = MixSources.parseNasa(fixture("nasa.json"))
        assertEquals("NASA", list[0].credit)
        assertEquals("Jane Doe / NASA", list.last().credit)
    }

    @Test
    fun commonsKeepsOnlyWideJpegsInRange() {
        val list = MixSources.parseCommons(fixture("commons.json"))
        assertEquals(3, list.size)
        assertTrue(list.all { it.id.startsWith("commons:File:") && it.width >= 3840 })
        assertTrue(list.all { it.url.startsWith("https://thumb.wikimedia.org") })
    }

    @Test
    fun commonsStripsHtmlFromArtist() {
        val first = MixSources.parseCommons(fixture("commons.json")).first { it.id.startsWith("commons:File:S-charl") }
        assertEquals("Dominicus Johannes Bergsma. · CC BY-SA 4.0", first.credit)
    }

    @Test
    fun commonsWithoutArtistUsesLicenseOnly() {
        val obersaxen = MixSources.parseCommons(fixture("commons.json")).first { it.id.startsWith("commons:File:Obersaxen") }
        assertEquals("CC BY-SA 4.0", obersaxen.credit)
    }

    @Test
    fun plainTextDecodesEntitiesAndDropsTags() {
        assertEquals("A & B", MixSources.plainText("<b>A &amp; B</b>"))
    }

    @Test
    fun commonsUrlCarriesTheCursor() {
        assertTrue(MixSources.commonsUrl("Featured pictures of landscapes", null).contains("iiurlwidth=3840"))
        assertTrue(MixSources.commonsUrl("Featured pictures of landscapes", "20260608|1").contains("gcmcontinue="))
    }

    @Test
    fun themesPickCommonsCategories() {
        assertEquals(5, MixSources.commonsCategories(emptySet()).size)
        assertEquals(listOf("Featured pictures of landscapes"), MixSources.commonsCategories(setOf(MixTheme.Nature)))
        assertEquals(
            listOf("Featured pictures of cityscapes", "Featured pictures of architecture"),
            MixSources.commonsCategories(setOf(MixTheme.City)),
        )
        assertEquals(listOf("Featured pictures of beaches"), MixSources.commonsCategories(setOf(MixTheme.Sea)))
    }

    @Test
    fun themesWithNoMatchGiveNoSource() {
        assertEquals(emptyList<String>(), MixSources.commonsCategories(setOf(MixTheme.Space)))
        assertEquals(emptyList<String>(), MixSources.nasaQueries(setOf(MixTheme.Nature)))
        assertEquals(MixSources.NASA_QUERIES, MixSources.nasaQueries(setOf(MixTheme.Space)))
        assertEquals(MixSources.NASA_QUERIES, MixSources.nasaQueries(emptySet()))
    }

    @Test
    fun unsplashUrlUsesWallpapersTopicWithoutThemes() {
        val plain = MixSources.unsplashUrl(null)
        assertTrue(plain.contains("topics=wallpapers"))
        assertTrue(plain.contains("orientation=landscape"))
        assertTrue(!plain.contains("query="))
        assertTrue(MixSources.unsplashUrl(MixTheme.Mountains).contains("query=mountains"))
        assertTrue(MixSources.unsplashUrl(MixTheme.Space).contains("query=milky+way"))
    }

    @Test
    fun parseUnsplashBuildsUrlsCreditAndPing() {
        val list = MixSources.parseUnsplash(fixture("unsplash.json"))
        assertEquals(3, list.size)
        val first = list.first()
        assertEquals("unsplash:AUPja_8Smd0", first.id)
        assertEquals(MixSource.Unsplash, first.source)
        assertTrue(first.url.startsWith("https://images.unsplash.com/") && first.url.endsWith("&w=3840&fm=jpg&q=85"))
        assertTrue(first.thumb.endsWith("&w=400&fm=jpg"))
        assertEquals(6000 to 4002, first.width to first.height)
        assertEquals("Photo by Daniel Seßler on Unsplash", first.credit)
        assertTrue(first.ping.startsWith("https://api.unsplash.com/photos/AUPja_8Smd0/download"))
        assertTrue(list[1].title.isNotBlank())
    }

    @Test
    fun thumbsAreFilledForEveryParser() {
        assertTrue(MixSources.parseBing(fixture("bing.json")).all { it.thumb.endsWith("_400x240.jpg") })
        assertTrue(MixSources.parseNasa(fixture("nasa.json")).all { it.thumb.endsWith("~thumb.jpg") })
        assertTrue(MixSources.parseCommons(fixture("commons.json")).all { "/500px-" in it.thumb })
    }

    @Test
    fun unsplashWithoutKeyIsSkipped() {
        val all = setOf(MixSource.Bing, MixSource.Unsplash)
        assertEquals(setOf(MixSource.Bing), MixSources.activeSources(emptySet(), all, unsplashKey = ""))
        assertEquals(all, MixSources.activeSources(emptySet(), all, unsplashKey = "k"))
    }

    @Test
    fun sourcesWithNothingForTheThemesDropOut() {
        val all = setOf(MixSource.Bing, MixSource.Commons, MixSource.Nasa, MixSource.Mine)
        assertEquals(setOf(MixSource.Bing, MixSource.Commons, MixSource.Mine), MixSources.activeSources(setOf(MixTheme.Nature), all, "k"))
        assertEquals(setOf(MixSource.Bing, MixSource.Nasa, MixSource.Mine), MixSources.activeSources(setOf(MixTheme.Space), all, "k"))
    }

    @Test fun `an unsplash key is valid when unsplash accepts it, even if it is over its hourly limit`() {
        assertEquals(KeyCheck.Valid, MixSources.unsplashKeyVerdict(200))
        assertEquals(KeyCheck.Valid, MixSources.unsplashKeyVerdict(403))
    }

    @Test fun `an unsplash key is invalid when unsplash refuses it`() {
        assertEquals(KeyCheck.Invalid, MixSources.unsplashKeyVerdict(401))
    }

    @Test fun `an unsplash key cannot be told when there was no answer or a server error`() {
        assertEquals(KeyCheck.Unverified, MixSources.unsplashKeyVerdict(null))
        assertEquals(KeyCheck.Unverified, MixSources.unsplashKeyVerdict(500))
        assertEquals(KeyCheck.Unverified, MixSources.unsplashKeyVerdict(404))
    }
}
