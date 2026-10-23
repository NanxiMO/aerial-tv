package app.orchard.tv.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfRowsTest {
    private fun prog(
        title: String,
        intent: String? = "app://$title",
        channel: Long = 0,
        weight: Int = 0,
        engaged: Long = 0,
        position: Long = 0,
        duration: Long = 0,
        aspect: Int = -1,
        poster: String? = null,
        thumb: String? = null,
        episode: String? = null,
        description: String? = null,
    ) = RawProgram(title, episode, description, poster, thumb, aspect, intent, channel, weight, engaged, position, duration)

    @Test fun `watch next comes first, most recently engaged first`() {
        val items = ShelfRows.arrange(
            watchNext = listOf(prog("old", engaged = 1), prog("new", engaged = 9)),
            previews = listOf(prog("rec", channel = 1)),
            channelOrder = listOf(1),
        )
        assertEquals(listOf("new", "old", "rec"), items.map { it.title })
    }

    @Test fun `previews follow channel order, heavier first within a channel`() {
        val items = ShelfRows.arrange(
            watchNext = emptyList(),
            previews = listOf(
                prog("b-light", channel = 2, weight = 1),
                prog("a", channel = 1),
                prog("b-heavy", channel = 2, weight = 5),
            ),
            channelOrder = listOf(1, 2),
        )
        assertEquals(listOf("a", "b-heavy", "b-light"), items.map { it.title })
    }

    @Test fun `previews of hidden or unknown channels are left out`() {
        val items = ShelfRows.arrange(emptyList(), listOf(prog("x", channel = 7)), channelOrder = listOf(1))
        assertEquals(emptyList<ShelfItem>(), items)
    }

    @Test fun `programs that cannot be opened are left out`() {
        val items = ShelfRows.arrange(listOf(prog("no link", intent = null), prog("blank", intent = " ")), emptyList(), emptyList())
        assertEquals(emptyList<ShelfItem>(), items)
    }

    @Test fun `the same link appears once`() {
        val items = ShelfRows.arrange(
            watchNext = listOf(prog("ep", intent = "app://1")),
            previews = listOf(prog("ep again", intent = "app://1", channel = 1)),
            channelOrder = listOf(1),
        )
        assertEquals(listOf("ep"), items.map { it.title })
    }

    @Test fun `at most the limit`() {
        val many = (1..30).map { prog("p$it", channel = 1) }
        assertEquals(20, ShelfRows.arrange(emptyList(), many, listOf(1)).size)
    }

    @Test fun `progress only with a position inside a known duration`() {
        val items = ShelfRows.arrange(
            listOf(
                prog("half", position = 50, duration = 100, engaged = 3),
                prog("none", engaged = 2),
                prog("over", position = 150, duration = 100, engaged = 1),
            ),
            emptyList(), emptyList(),
        )
        assertEquals(0.5f, items[0].progress!!, 0.001f)
        assertNull(items[1].progress)
        assertNull(items[2].progress)
    }

    @Test fun `poster preferred over thumbnail, with its own aspect`() {
        val a = ShelfRows.arrange(listOf(prog("a", poster = "p", aspect = 4, thumb = "t")), emptyList(), emptyList()).single()
        assertEquals("p", a.image)
        assertEquals(2f / 3f, a.aspect, 0.001f)
        val b = ShelfRows.arrange(listOf(prog("b", thumb = "t")), emptyList(), emptyList()).single()
        assertEquals("t", b.image)
        assertEquals(16f / 9f, b.aspect, 0.001f)
    }

    @Test fun `subtitle is the episode title, else the description`() {
        val items = ShelfRows.arrange(
            listOf(prog("a", episode = "Ep 1", description = "d", engaged = 2), prog("b", description = "only d", engaged = 1)),
            emptyList(), emptyList(),
        )
        assertEquals("Ep 1", items[0].subtitle)
        assertEquals("d", items[0].description)
        assertEquals("only d", items[1].subtitle)
    }

    @Test fun `aspect codes follow TvContract`() {
        assertEquals(16f / 9f, ShelfRows.aspect(0), 0.001f)
        assertEquals(3f / 2f, ShelfRows.aspect(1), 0.001f)
        assertEquals(4f / 3f, ShelfRows.aspect(2), 0.001f)
        assertEquals(1f, ShelfRows.aspect(3), 0.001f)
        assertEquals(2f / 3f, ShelfRows.aspect(4), 0.001f)
        assertEquals(1f / 1.441f, ShelfRows.aspect(5), 0.001f)
        assertEquals(16f / 9f, ShelfRows.aspect(99), 0.001f)
    }
}
