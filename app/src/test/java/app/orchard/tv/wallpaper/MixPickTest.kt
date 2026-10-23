package app.orchard.tv.wallpaper

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixPickTest {
    private fun pic(id: String, width: Int = 3840) =
        MixPicture(id, MixSource.Bing, "https://example.test/$id.jpg", width, if (width == 0) 0 else width * 9 / 16, id, "credit")

    private fun pool(n: Int) = (1..n).map { pic("p$it") }

    @Test
    fun neverRepeatsUntilPoolIsExhausted() {
        val pool = pool(5)
        var shown = emptySet<String>()
        var last: String? = null
        val seen = mutableListOf<String>()
        repeat(5) {
            val r = MixPick.next(pool, shown, last, Random(it))!!
            seen += r.picture.id
            shown = r.shown
            last = r.picture.id
        }
        assertEquals(5, seen.toSet().size)
    }

    @Test
    fun resetsAfterFullRound() {
        val pool = pool(5)
        val all = pool.map { it.id }.toSet()
        val r = MixPick.next(pool, all, "p3", Random(1))!!
        assertEquals(setOf(r.picture.id), r.shown)
        assertNotEquals("p3", r.picture.id)
    }

    @Test
    fun singlePictureRepeatsWithoutLoop() {
        val only = pool(1)
        val r = MixPick.next(only, setOf("p1"), "p1", Random(1))!!
        assertEquals("p1", r.picture.id)
    }

    @Test
    fun emptyPoolIsNull() {
        assertNull(MixPick.next(emptyList(), emptySet(), null, Random(1)))
    }

    @Test
    fun poolKeepsOnlyUhdWhenAnyExist() {
        val all = listOf(pic("a", 3840), pic("b", 1920), pic("c", 0))
        assertEquals(listOf("a", "c"), MixPick.pool(all).map { it.id })
    }

    @Test
    fun poolFallsBackTo1080WhenNoUhd() {
        val all = listOf(pic("b", 1920), pic("d", 1280), pic("e", 2560))
        assertEquals(listOf("b", "e"), MixPick.pool(all).map { it.id })
    }

    @Test
    fun isDueAfterInterval() {
        val t0 = 1_000_000L
        assertFalse(MixPick.isDue(t0, t0 + 14 * 60_000L, 15))
        assertTrue(MixPick.isDue(t0, t0 + 15 * 60_000L, 15))
    }

    @Test
    fun isDueWhenClockWentBack() {
        assertTrue(MixPick.isDue(2_000_000L, 1_000_000L, 15))
    }

    @Test
    fun badHistoryResets() {
        assertEquals(emptySet<String>(), MixPick.parseHistory("{not json"))
        assertEquals(emptySet<String>(), MixPick.parseHistory(""))
        assertEquals(emptySet<String>(), MixPick.parseHistory(null))
    }

    @Test
    fun historySurvivesRoundTrip() {
        val ids = setOf("bing:20261022", "commons:File:A b.jpg")
        assertEquals(ids, MixPick.parseHistory(MixPick.writeHistory(ids)))
    }

    @Test
    fun unknownOrEmptySourcesFallBackToBing() {
        assertEquals(setOf(MixSource.Bing), MixPick.parseSources(null))
        assertEquals(setOf(MixSource.Bing), MixPick.parseSources(""))
        assertEquals(setOf(MixSource.Bing), MixPick.parseSources("flickr"))
    }

    @Test
    fun sourcesSurviveRoundTrip() {
        val all = setOf(MixSource.Commons, MixSource.Nasa)
        assertEquals(all, MixPick.parseSources(MixPick.writeSources(all)))
    }

    @Test
    fun toggleAddsAndRemovesButKeepsTheLast() {
        val bing = setOf(MixSource.Bing)
        val both = MixPick.toggled(bing, MixSource.Nasa)
        assertEquals(setOf(MixSource.Bing, MixSource.Nasa), both)
        assertEquals(setOf(MixSource.Nasa), MixPick.toggled(both, MixSource.Bing))
        assertEquals(bing, MixPick.toggled(bing, MixSource.Bing))
    }

    @Test
    fun rejectedDownloadIsRecordedSoItIsNotTriedAgain() {
        assertEquals(setOf("a", "b"), MixPick.afterDownload(ok = false, id = "b", shown = setOf("a")))
        assertEquals(setOf("a"), MixPick.afterDownload(ok = true, id = "b", shown = setOf("a")))
    }

    @Test
    fun emptyPoolFallsBackToDailyThenBuiltIn() {
        assertEquals(listOf("daily:latest", "grad:aurora"), MixPick.fallbackOrder(hasDaily = true))
        assertEquals(listOf("grad:aurora"), MixPick.fallbackOrder(hasDaily = false))
    }

    @Test
    fun pictureSurvivesJsonRoundTrip() {
        val p = MixPicture("commons:File:A.jpg", MixSource.Commons, "https://x.test/a.jpg", 5184, 3456, "A & B", "Jane · CC BY-SA 4.0")
        assertEquals(p, MixPick.readPicture(MixPick.writePicture(p)))
        assertNull(MixPick.readPicture("{broken"))
        assertEquals(listOf(p), MixPick.readPictures(MixPick.writePictures(listOf(p))))
        assertEquals(emptyList<MixPicture>(), MixPick.readPictures("not json"))
    }

    @Test
    fun pictureJsonKeepsThumbAndPing() {
        val p = MixPicture("unsplash:x", MixSource.Unsplash, "https://u.test/x", 6000, 4000, "T", "C", thumb = "https://u.test/t", ping = "https://u.test/p")
        assertEquals(p, MixPick.readPicture(MixPick.writePicture(p)))
    }

    @Test
    fun oldPictureJsonStillReads() {
        val old = """{"id":"bing:1","source":"bing","url":"u","width":1,"height":2,"title":"t","credit":"c"}"""
        val p = MixPick.readPicture(old)!!
        assertEquals("", p.thumb)
        assertEquals("", p.ping)
    }

    @Test
    fun hiddenNeverComesBack() {
        val pool = pool(4)
        val visible = MixPick.withoutHidden(pool, setOf("p2", "p3"))
        assertEquals(listOf("p1", "p4"), visible.map { it.id })
        var shown = emptySet<String>()
        repeat(10) {
            val r = MixPick.next(visible, shown, null, Random(it))!!
            assertTrue(r.picture.id in setOf("p1", "p4"))
            shown = r.shown
        }
    }

    @Test
    fun savedNameIsStableShortAndDistinct() {
        val a = MixPick.savedName("commons:File:A.jpg")
        assertEquals(a, MixPick.savedName("commons:File:A.jpg"))
        assertNotEquals(a, MixPick.savedName("commons:File:B.jpg"))
        assertTrue(Regex("^[0-9a-f]{12}$").matches(a))
    }

    @Test
    fun migratesOnePhotoADay() {
        assertEquals("mix:auto" to 1440, MixPick.migrateSelected("photos:rotate"))
        assertNull(MixPick.migrateSelected("daily:latest"))
        assertNull(MixPick.migrateSelected("mix:auto"))
    }
}
