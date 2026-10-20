package com.multiviewer.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderListingTest {
    private fun tempDir(): File = Files.createTempDirectory("folder-listing").toFile().apply { deleteOnExit() }

    private fun File.file(name: String) = File(this, name).apply { writeBytes(ByteArray(1)); deleteOnExit() }
    private fun File.dir(name: String) = File(this, name).apply { mkdir(); deleteOnExit() }

    @Test
    fun `lists supported media and visible subfolders sorted case-insensitively`() {
        val dir = tempDir()
        dir.file("b.MP4")
        dir.file("A.jpg")
        dir.file("c.heic")
        dir.file("notes.txt")
        dir.file("noext")
        dir.file(".hidden.jpg")
        dir.dir("Zeta")
        dir.dir("alpha")
        dir.dir(".git")
        dir.dir("folder.jpg") // a directory with a media extension is a subfolder, not a file

        val listing = listFolder(dir)

        assertTrue(listing.available)
        assertEquals(listOf("A.jpg", "b.MP4", "c.heic"), listing.mediaFiles.map { it.name })
        assertEquals(listOf("alpha", "folder.jpg", "Zeta"), listing.subfolders.map { it.name })
        assertEquals(listing.mediaFiles, listFolderMediaFiles(dir))
    }

    private fun File.copyOfFixture(fixture: String, name: String) =
        File(this, name).also { File(FolderListingTest::class.java.getResource("/format-sniff/$fixture")!!.toURI()).copyTo(it); it.deleteOnExit() }

    @Test
    fun `files whose content is recognized media are listed despite their extension`() {
        val dir = tempDir()
        dir.copyOfFixture("a.heic", "photo.bin")
        dir.copyOfFixture("a.mov", "clip")
        dir.copyOfFixture("a.png", "image.txt") // known non-media extension: not sniffed
        dir.file("random.xyz")
        dir.copyOfFixture("a.jpg", "real.jpg")

        val listing = listFolder(dir)

        assertEquals(listOf("clip", "photo.bin", "real.jpg"), listing.mediaFiles.map { it.name })
        assertEquals("heic", listing.kindExtension(File(dir, "photo.bin")))
        assertEquals("mov", listing.kindExtension(File(dir, "clip")))
        assertEquals("jpg", listing.kindExtension(File(dir, "real.jpg")))
        assertEquals(listing.contentFormats, listing.matching("o").contentFormats)
    }

    @Test
    fun `missing folder, a plain file and null are unavailable and empty`() {
        val dir = tempDir()
        val plain = dir.file("x.jpg")
        for (target in listOf(File(dir, "missing"), plain, null)) {
            val listing = listFolder(target)
            assertFalse(listing.available)
            assertEquals(emptyList(), listing.mediaFiles)
            assertEquals(emptyList(), listing.subfolders)
        }
    }

    @Test
    fun `existing folder that cannot be read is unreadable, a missing one is not`() {
        val dir = tempDir()
        val locked = dir.dir("locked")
        try {
            assertTrue(locked.setReadable(false) && locked.setExecutable(false))
            if (locked.listFiles() != null) return // running as a user that ignores permissions (root)
            val listing = listFolder(locked)
            assertFalse(listing.available)
            assertTrue(listing.unreadable)
        } finally {
            locked.setReadable(true); locked.setExecutable(true)
        }
        assertFalse(listFolder(File(dir, "missing")).unreadable)
        assertFalse(listFolder(null).unreadable)
        assertFalse(listFolder(dir).unreadable)
    }

    @Test
    fun `search filter matches names case-insensitively in memory`() {
        val dir = tempDir()
        dir.file("Holiday.JPG")
        dir.file("work.mp4")
        dir.dir("HOLIDAY-raw")
        dir.dir("misc")
        val listing = listFolder(dir)

        assertEquals(listing, listing.matching(""))
        assertEquals(listing, listing.matching("  "))
        val hit = listing.matching("holi")
        assertEquals(listOf("Holiday.JPG"), hit.mediaFiles.map { it.name })
        assertEquals(listOf("HOLIDAY-raw"), hit.subfolders.map { it.name })
    }
}
