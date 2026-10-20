package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The backup's own words. The parts that read and write JSON are checked on a device instead, where
 * the real parser is; these are the names, the sentence, and the day it was made.
 */
class BackupTest {
    @Test fun aBooksFilesStayInsideThatBooksFolderInABackup() {
        assertEquals("books/9f7e940e/chapter one.xhtml", BackupFormat.bookEntry("9f7e940e", "chapter one.xhtml"))
    }

    @Test fun aTallyReadsBackAsASentence() {
        assertEquals("nothing new", BackupTally().describe())
        assertTrue(BackupTally().nothing)
        assertEquals("1 note", BackupTally(notes = 1).describe())
        assertEquals("2 books, 5 highlights and 2 notes", BackupTally(books = 2, highlights = 5, notes = 2).describe())
        assertEquals("1 book, 1 reading place, 1 bookmark, 1 highlight and 1 note",
            BackupTally(books = 1, places = 1, bookmarks = 1, highlights = 1, notes = 1).describe())
        assertFalse(BackupTally(books = 1).nothing)
    }

    @Test fun aBackupIsNamedForTheDayItWasMade() {
        assertEquals("moonlight-pages-backup-2026-10-18.zip", backupFileName(LocalDate.of(2026, 10, 18)))
        assertTrue(backupFileName().startsWith("moonlight-pages-backup-"))
        // A zip is described differently by different file managers, so all of those stay choosable.
        assertTrue(SUPPORTED_BACKUP_TYPES.contains("application/zip"))
        assertTrue(SUPPORTED_BACKUP_TYPES.contains("application/octet-stream"))
        assertEquals(1, BackupFormat.VERSION)
    }
}
