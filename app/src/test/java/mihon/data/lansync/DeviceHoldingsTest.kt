package mihon.data.lansync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Synthetic sources, series and devices only. */
class DeviceHoldingsTest {

    private val pc = "fake-pc-0001"
    private val other = "fake-pc-0002"

    private fun ref(chapter: String, manga: String = "/m/1") = RemoteQueue.ChapterRef("100", manga, chapter)

    private fun file(
        chapter: String,
        kind: String = "download",
        path: String = "downloads/S/M/c${chapter.last()}.cbz",
    ) =
        LanSyncProtocol.ManifestFile(
            path = path,
            size = 10,
            lastModified = 0,
            kind = kind,
            sourceId = "100",
            mangaUrl = "/m/1",
            chapterUrl = chapter,
        )

    @Test
    fun `a manifest keeps chapters with safe paths only`() {
        val snapshot = DeviceHoldings.fromManifest(
            listOf(
                file("/c/1"),
                file("/c/2", kind = "backup"),
                file("/c/3", path = "downloads/../etc/c.cbz"),
                file("/c/4", path = "/abs/c.cbz"),
                file("/c/1"),
            ),
            fetchedAt = 50,
        )
        assertEquals(listOf(ref("/c/1")), snapshot.files.map { it.ref })
        assertEquals(50, snapshot.fetchedAt)
    }

    @Test
    fun `manifest and newer done events are merged, with paths where known`() {
        val snapshots = mapOf(pc to DeviceHoldings.fromManifest(listOf(file("/c/1")), fetchedAt = 100))
        val held = listOf(
            RemoteQueue.Held(ref("/c/2"), pc, at = 150),
            RemoteQueue.Held(ref("/c/1"), pc, at = 160),
        )
        val index = DeviceHoldings.index(snapshots, held, linked = setOf(pc))
        assertEquals(setOf(ref("/c/1"), ref("/c/2")), index.keys)
        // Listed once, with the manifest's path rather than the event's lack of one
        assertEquals(1, index.getValue(ref("/c/1")).size)
        assertEquals("downloads/S/M/c1.cbz", index.getValue(ref("/c/1")).single().path)
        assertEquals(DeviceHoldings.Holder(pc, null), index.getValue(ref("/c/2")).single())
    }

    @Test
    fun `a done event older than the manifest is overruled by it`() {
        // The manifest fetched at 200 no longer lists /c/2, so the PC deleted it
        val snapshots = mapOf(pc to DeviceHoldings.fromManifest(listOf(file("/c/1")), fetchedAt = 200))
        val held = listOf(RemoteQueue.Held(ref("/c/2"), pc, at = 150))
        assertEquals(setOf(ref("/c/1")), DeviceHoldings.index(snapshots, held, setOf(pc)).keys)
    }

    @Test
    fun `done events count when there is no manifest yet`() {
        val held = listOf(RemoteQueue.Held(ref("/c/9"), pc, at = 1))
        assertEquals(setOf(ref("/c/9")), DeviceHoldings.index(emptyMap(), held, setOf(pc)).keys)
    }

    @Test
    fun `devices that are no longer linked drop out`() {
        val snapshots = mapOf(
            pc to DeviceHoldings.fromManifest(listOf(file("/c/1")), fetchedAt = 100),
            other to DeviceHoldings.fromManifest(listOf(file("/c/2")), fetchedAt = 100),
        )
        val held = listOf(RemoteQueue.Held(ref("/c/3"), other, at = 500))
        assertEquals(setOf(ref("/c/1")), DeviceHoldings.index(snapshots, held, linked = setOf(pc)).keys)
    }

    @Test
    fun `two devices holding one chapter are both listed, and counted once per series`() {
        val snapshots = mapOf(
            pc to DeviceHoldings.fromManifest(listOf(file("/c/1"), file("/c/2")), fetchedAt = 100),
            other to DeviceHoldings.fromManifest(listOf(file("/c/1")), fetchedAt = 100),
        )
        val index = DeviceHoldings.index(snapshots, emptyList(), linked = setOf(pc, other))
        assertEquals(listOf(pc, other), index.getValue(ref("/c/1")).map { it.deviceId })
        assertEquals(mapOf(DeviceHoldings.MangaRef("100", "/m/1") to 2), DeviceHoldings.counts(index))
    }
}
