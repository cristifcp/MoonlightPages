package com.moonlightpages.app

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The system has to offer Moonlight Pages before a file manager can hand it a book. */
@RunWith(AndroidJUnit4::class)
class IncomingFileTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun offers(intent: Intent) = context.packageManager.queryIntentActivities(intent, 0)
        .any { it.activityInfo.packageName == context.packageName }

    @Test fun tappingAnEpubInAFileManagerOffersTheApp() {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://downloads/all/1"), "application/epub+zip")
        assertTrue("Open with must offer Moonlight Pages for an EPUB.", offers(view))
    }

    @Test fun tappingAFictionBookInAFileManagerOffersTheApp() {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://downloads/all/2"), "application/x-fictionbook+xml")
        assertTrue("Open with must offer Moonlight Pages for a FictionBook.", offers(view))
    }

    @Test fun sharingAnEpubOffersTheApp() {
        assertTrue("Share must offer Moonlight Pages for an EPUB.",
            offers(Intent(Intent.ACTION_SEND).setType("application/epub+zip")))
    }

    @Test fun theAppDoesNotClaimFilesItCannotRead() {
        val pdf = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://downloads/all/2"), "application/pdf")
        assertFalse("Moonlight Pages must not appear for every file on the phone.", offers(pdf))
    }
}
