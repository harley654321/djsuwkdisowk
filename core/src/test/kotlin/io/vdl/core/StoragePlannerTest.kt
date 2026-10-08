package io.vdl.core

import io.vdl.core.internal.storage.StoragePlan
import io.vdl.core.internal.storage.StoragePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Storage decisions are pure and JVM-tested: which collection, whether
 * IS_PENDING gives an atomic publish on this device, MIME resolution
 * (server-provided wins, extension map second, octet-stream last) and
 * the legacy public dir for pre-Q devices.
 */
class StoragePlannerTest {

    @Test
    fun mimeServerProvidedWinsOverExtension() {
        assertEquals("application/x-custom", StoragePlanner.mimeFor("clip.mp4", "application/x-custom"))
        // octet-stream from the server is NOT a decision: fall through
        assertEquals("video/mp4", StoragePlanner.mimeFor("clip.mp4", "application/octet-stream"))
    }

    @Test
    fun mimeExtensionMapAndFallback() {
        assertEquals("video/mp4", StoragePlanner.mimeFor("a.mp4", null))
        assertEquals("video/mp2t", StoragePlanner.mimeFor("a.TS", null))
        assertEquals("video/x-matroska", StoragePlanner.mimeFor("a.mkv", null))
        assertEquals("audio/mp4", StoragePlanner.mimeFor("a.m4a", null))
        assertEquals("audio/mpeg", StoragePlanner.mimeFor("a.mp3", null))
        assertEquals("application/octet-stream", StoragePlanner.mimeFor("a.bin", null))
        assertEquals("application/octet-stream", StoragePlanner.mimeFor("noext", null))
    }

    @Test
    fun galleryVideoGoesToMoviesWithPendingOnQPlus() {
        val p = StoragePlanner.plan(Destination.Gallery(), "movie.mp4", null, sdkInt = 29)
        assertEquals(StoragePlan.VIDEO, p.collection)
        assertEquals("Movies", p.relativeBase)
        assertTrue(p.atomicPending)
        assertEquals("Movies", p.legacyDir)
    }

    @Test
    fun galleryAudioGoesToMusicAudioCollection() {
        val p = StoragePlanner.plan(Destination.Gallery("MyApp"), "song.m4a", null, sdkInt = 29)
        assertEquals(StoragePlan.AUDIO, p.collection)
        assertEquals("Music/MyApp", p.relativeBase)
        assertTrue(p.atomicPending)
    }

    @Test
    fun galleryPreQIsLegacyDirWithoutPending() {
        val p = StoragePlanner.plan(Destination.Gallery(), "movie.mp4", null, sdkInt = 24)
        assertFalse(p.atomicPending)
        assertEquals("Movies", p.legacyDir)
    }

    @Test
    fun downloadsPlanWithSanitizedSubfolder() {
        val p = StoragePlanner.plan(
            Destination.PublicDownloads("a b/c..d"), "f.bin", null, sdkInt = 29
        )
        assertEquals(StoragePlan.DOWNLOADS, p.collection)
        assertTrue(p.atomicPending)
        assertTrue(p.relativeBase!!.startsWith("Download/"))
    }

    @Test
    fun safIsNeverAtomicPending() {
        val p = StoragePlanner.plan(
            Destination.SafTree("content://tree/primary%3ADownloads"), "movie.mp4", null, sdkInt = 33
        )
        assertEquals(StoragePlan.SAF, p.collection)
        assertFalse(p.atomicPending)
    }

    @Test
    fun appPrivateIsPlainFileCollection() {
        val p = StoragePlanner.plan(Destination.AppPrivate(), "x.bin", null, sdkInt = 33)
        assertEquals(StoragePlan.FILE, p.collection)
        assertFalse(p.atomicPending)
    }

    @Test
    fun destinationCodecRoundTripsNewTypes() {
        // the subfolder column carries the tree uri for SAF
        val saf = Destination.fromStrings("SAF", "content://tree/abc")
        assertTrue(saf is Destination.SafTree)
        assertEquals("content://tree/abc", (saf as Destination.SafTree).treeUri)
        val gallery = Destination.fromStrings("GALLERY", "MyApp")
        assertTrue(gallery is Destination.Gallery)
        assertEquals("MyApp", (gallery as Destination.Gallery).subfolder)
        // unknown type still maps to AppPrivate (forward compatibility)
        assertTrue(Destination.fromStrings("FUTURE_TYPE", null) is Destination.AppPrivate)
    }
}
