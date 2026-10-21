# Design notes

The long version of every choice in Moonlight Pages: why the page turns the way it does, what happens to a FictionBook or a MOBI on the way in, how reading places survive a change of font, and what the reader deliberately does not do. The README is the short version.

Branding: a child reading on a hillside under a starry night sky, and the app is named after
it. The launcher icon is an
adaptive icon — a black tile, the colour of the artwork's own surround, holding the whole
picture inside the 72dp safe zone, so the launcher's mask trims the tile rather than any of
the drawing and nothing of the picture is cropped. There is no separate monochrome layer,
and the legacy per-density icon bitmaps are gone because every supported Android version
(26+) uses the adaptive icon. The artwork is kept as
`branding/reader-mark.png`; the files cut from it are
`drawable-xxxhdpi/ic_launcher_foreground.png` (the picture, centred on a transparent 108dp
canvas) and `drawable-xxxhdpi/reader_mark.png` (the picture whole, for the library header).

Themes: two, cut from that same picture — a daylit page (warm paper, ink that is nearly navy)
and the night sky (deep blue, moonlight for anything tappable). Neither is green: the green
belonged to the old lamp branding and went with it. The reader chooses in **Settings →
Appearance**, the choice is remembered, and until they make one the phone's own day/night
setting decides. The reading page is separate again: it keeps its own paper (white, sepia or
night), and the reader's controls take their colour from the paper they sit on, so a night
page is never framed in daylight chrome.

The shelf: the app is three rooms along the foot of the screen — the notebook, the library and
settings — and a book opened fills all of it. The library is a heading, one shell holding its three
actions (import, search, and whether the shelf is a list or a wall of covers), a single line naming
the book that was put down last, and a sheet that runs past the bottom of the screen with the bar
standing over it. The books are rows by default — cover, title, author, when it was last picked up —
because a shelf is read down rather than scanned like a shop window; the covers are one tap away for
a reader who knows a book by its face before its name. The order (last read, title, author) is the
reader's to choose and is remembered. Each row keeps its three ways in: tap to open, the menu for
**Remove from shelf**, and a long press that says the same thing without needing to be discovered.
The notebook gathers every note in every book, newest first, and a note opens the page it was
written on rather than wherever the book was last left.

The app ID and Kotlin namespace are `com.moonlightpages.app`, and the project folder is

`MoonlightPages` — all of it renamed from the app's first name. Renaming the app ID is what
makes Android treat this as a new app: it installs beside the old one rather than over it, so
the reader's library had to be carried across by hand. A backup (**Settings → Back up your
library**) is the way to do that on a phone without a computer: back up from the old build,
install this one, restore. The folder is named without a space because it is written into
build paths and into every snapshot zip.

**App ID changes:** the app ID has been changed twice while this was built — once from a
different project, and once when the app was given its present name — and Android treats each
app ID as a separate app. A build under an earlier one installs *beside* this one rather than
over it, with its own empty shelf, so books, bookmarks and reading progress are not copied
across. Back up from the old build and restore into this one, or reimport books through
**Import a book**.

## Run

Open this folder in Android Studio, let Gradle sync, select a phone or emulator
(Android 8.0/API 26 or later), and press Run. Tap **Or start with Moby Dick** to read a
whole novel without downloading anything. Use **Import a book** to copy a DRM-free EPUB or
FictionBook from the Android file picker into the app's private storage.

If the emulator reports missing hardware acceleration, use an Android phone with
USB debugging enabled, or configure emulator acceleration in Windows first:
https://developer.android.com/studio/run/emulator-acceleration#vm-windows

## Included

- Local library with covers and title/author search.
- Responsive, fully scrollable shelf, cached cover thumbnails, multi-word search,
  clear/no-results states, and shelf/search state retained when returning from a book.
  Only the cover art carries the rounded shape, so a long title can wrap to three lines
  underneath it without ever being clipped or shadowed by the cover.
- Imports and library state survive Activity recreation; failed index loads offer
  retry without overwriting data, and interrupted atomic index writes can recover.
- EPUB, FictionBook (FB2) and MOBI import, all bounded and validated before anything is kept. A book
  is read from what it actually is rather than from its name or the type the system guessed: a zip
  is an EPUB, one XML file whose root is `FictionBook` is an FB2, and a Palm database whose type is
  `BOOKMOBI` is a MOBI. A book whose files are named
  with Windows-style separators opens just the same.
- An FB2 is **converted into the shape an EPUB arrives in** as it is imported — a package, one
  chapter per titled section, a contents list naming them, the book's own images written out and
  its footnotes linked — so everything else in this list applies to it unchanged: pagination,
  chapter names, search, bookmarks, notes, the page-turn copy, and backups. Sections inside a
  section become chapters of their own, one level in; all of a book's notes live in one chapter,
  each keeping the anchor its own text points at.
- A MOBI is converted the same way: the Palm database is opened record by record, its text is
  decompressed — plain, or with PalmDOC's own compression, which carries back-references, short runs
  of raw bytes and a code of its own for a space followed by a letter — and its markup is turned into
  chapters at the page breaks the book itself marks, with its pictures written out as files and its
  cover found in its metadata. The MOBI header numbers are read in whichever byte order the file used,
  low byte first in most and high byte first in a few, and a shop named as a second author is left off
  the shelf.
- **MOBI:** the older kind, which is what Calibre writes and what most DRM-free MOBIs are. A book
  compressed with Amazon's Huffman dictionary is refused with a plain explanation rather than
  half-read, and **AZW3** — another structure inside a MOBI container — is not attempted at all.
  **DRM-protected** books of any kind cannot be read. Calibre converts all of these to EPUB, which
  this app reads.
- Books arrive straight from the file manager: tap an EPUB or a FictionBook, choose **Moonlight Pages**, and it
  imports and opens without a detour through the shelf. Sharing a book to Moonlight Pages does the
  same. A file that is already on the shelf opens the copy you have rather than importing a
  second one, and a *different* file carrying the same title and author asks first —
  **Open my copy** or **Add a second copy** — then remembers your answer for that file. A
  file that cannot be read is refused with a plain explanation, and your shelf is left
  exactly as it was.
- Putting a book down is the other half of importing: hold a cover and confirm. That
  erases the copy Moonlight Pages made along with the reading position, bookmarks, and
  highlights saved for that book — and nothing belonging to any other book. The file you
  imported from is never touched, so the book can always be imported again.
- Page-only reader: one screenful at a time, left/right swipes, and no vertical
  scrolling. Page turns cross chapter boundaries automatically.
- A page turn lays one sheet of paper over the last. The page arriving travels the whole
  width of the screen from off its far side, while the page being left is shoved a little
  way aside, dims as the sheet passes over it, and carries the soft shadow of the arriving
  edge where it lands — dimming and shadow reaching the screen's own edges. Turning back is
  the same turn, mirrored.
- Turning a page is one choice, not two: either left/right **swipes**, or **edge taps**,
  where tapping the left or right quarter of the page goes back or forward. A swipe turns
  the page only when the finger is lifted, and the turn it gets is the same turn an edge tap
  gets. It does not have to be dragged far: a sixth of the page across is plenty, and a quick
  flick turns the page however short it was. The middle of
  the page is where the controls come and go, and links and highlights keep their own
  taps. Settings switches one gesture on and the other off, so the two never compete for
  the same touch.
- Reading is the page and nothing else: no top or bottom bars, and the controls stay out
  of the way until the middle of the page is tapped. The same tap puts them away again.
  They are the quiet hint counting down the pages left in the chapter being read — the
  book's own chapter, so it reaches zero at the chapter's real end rather than at the end
  of the file that chapter happens to live in — the page number under the text, a floating
  pill holding Contents, Appearance (**Aa**), Bookmarks, and Search, and Close and
  Bookmark at the top right. The page is inset so no line of text ever hides behind them.
  A screen reader gets the controls from the start, since it cannot tap a middle that
  says nothing.
- Whole-book search: **Search** looks through every chapter at once and lists each
  match with the words around it, named by the book's own chapter it sits in — the same
  name the reader shows, even when several chapters share one file. Tapping a result
  reopens that exact passage.
- Illustrated pages are clipped below opaque reader controls. Page changes avoid
  promoting the entire chapter to a 3D layer; images load eagerly to stabilize layout.
- Named table of contents (EPUB 3 navigation and EPUB 2 NCX).
- Chapter-heading fallback for books without a usable navigation document.
- Persistent per-book bookmarks: save, reopen, and remove a reading location. The
  pill's **Bookmarks** button opens the saved list directly.
- Persistent text highlights in yellow, mint, rose, or lavender. Long-press a word,
  adjust the selection handles, choose **Highlight**, then tap a colour to save.
  Tap a saved highlight to recolour/delete it. **Contents → Highlights** lists the
  book's highlights and can jump to a saved passage or open its editor.
- Notes on the passages worth stopping over. The marker that opens when you mark a
  passage carries a **Your note** box, and tapping a saved highlight opens it again with
  what you wrote, ready to correct or take back. A noted passage carries a second, finer
  line under the marked words, so the note is visible on the page itself, and
  **Contents → Notebook** gathers every note in the book, each one listed with the words
  it was written about and a tap back to them. Each mark keeps the chapter name the book
  gives it, so the notebook names the chapter instead of a number from the file.
- The marker rises from the foot of the page like the search sheet, and lifts out of the
  keyboard's way, so the colour choices, the buttons, and the writing stay in view while
  you type.
- White, sepia, and night paper, and a page the reader shapes: text size, line spacing
  (tight, normal, roomy), margins (narrow, medium, wide) and typeface (serif or sans). Every
  one of them is applied to the page that is already open — the chapter is laid out again,
  never reloaded, and the reader's place in the words is kept.
- Night dimming, in the same sheet: it takes the phone's own brightness down, below the
  setting the phone itself offers, which is the point of it after dark. It does that rather
  than laying a film over the page, and the reason is the page turn: a film would end up
  inside the copy the turn holds of the page being left, which would then be dimmed a second
  time while the page arriving beside it was dimmed once — the two would not match as they
  moved. Dimming the screen's own light also dims the page, the controls and everything else
  together, so nothing glares and no contrast is given away.
- A light theme and a dark theme for everything around the page, chosen in Settings.
- Appearance changes reflow the existing renderer in place without reloading the
  chapter. The appearance sheet does not shrink the reading viewport. Night mode
  also styles controls, dialogs, and system-bar icon contrast.
- Text-anchored reading progress and bookmarks that survive font/viewport changes.
- A hairline bar along the foot of each cover shows how far in the reader is, and a
  **Continue reading** card names the book opened most recently and how far through it
  is, so picking the phone back up takes one tap. Both follow the book's own chapters:
  each named chapter counts once, so the percentage moves at the pace of the book rather
  than in the coarse steps of the files it is packed into.
- Books are flagged **Finished** with a check on the cover once the reader reaches the
  last page of the last chapter. Turning back into the book clears the flag again.
- Saved chapter and reading preferences, with backward-compatible older positions.
- Settings, reached from the library header: the swipe/edge-tap page-turn choice, a light
  or dark theme, keeping the screen awake while reading, and the two ways to keep your
  library, plus a note that nothing leaves the device unless you ask it to.
- One-file backup and restore. **Settings → Back up your library** gathers your books,
  where you were in each of them, your bookmarks, highlights and notes, and how you like
  to read into a single zip you choose the home for — a computer, a cloud drive, an SD
  card — so a lost or replaced phone does not take your library with it. **Restore from a
  backup** puts it all back and only ever adds: a book already on the shelf is left
  exactly as it is, a reading place moves only when the backup's is further along, and a
  note written since is never taken back. Restoring the same backup twice changes nothing
  the second time, and a file that is not one of Moonlight Pages' own backups is refused
  plainly rather than half read. Books are written back under their own identifiers, so
  the highlights, notes and reading places that name them keep working.
- A whole free novel waiting on an empty shelf: *Moby Dick; Or, The Whale* by Herman
  Melville, carried in the app's own assets as a real Project Gutenberg EPUB. It is
  imported exactly like a file from the phone, so it behaves like any other book —
  cover, named chapters, highlights, and all.

No account, analytics, or network permission: nothing is uploaded, and a backup is only
ever written where you point it. Uninstalling the app removes its imported copies and
progress, which is exactly what the backup is for; original source files are untouched.

## Prototype limitations

The reader uses Android WebView and bundled app-owned pagination code, not
Readium yet. Imported HTML is sanitized; book scripts, event handlers, frames,
external requests, and native JavaScript bridges are blocked. A restrictive CSP
disallows document scripts; the app evaluates only its own local pagination code.
It supports basic left-to-right reflowable EPUBs and FictionBooks, not DRM, encrypted
fonts, fixed-layout books, EPUB scripting, or every EPUB layout feature. Files
must be UTF-8 for chapter rendering. Text positions use a chapter text-node index,
UTF-16 offset, and validation quote. Changing the font or screen size opens the
page containing the saved text, not necessarily a page starting at the same line.
Old fractional positions are approximated on first use; opening an older bookmark
upgrades it to a text anchor. Image-only pages still use a fractional fallback.
Search matches within a single paragraph, so a phrase broken up by inline styling
(for example split by italics) may not be found.
Page numbers count within the file being read — *56 of 189* — and change with text
size, while the chapter name and the pages-left hint follow the book's own chapters.
A page turn takes a copy of the page being left off the screen it is shown on, turns the page,
and then slides the arriving page in from off the screen over the top of that copy — the words
on it travel with their paper instead of being revealed by a sweeping line. The copy is read
back from the screen itself rather than asked of the page, because a page drawing itself into a
bitmap lays its words out on its own terms, at its own size, which are not the terms and the size
they are shown at, and the held page would jump as the turn began. One clock drives both, so the
page travelling in and the copy it covers can never fall out of step. The copy is shoved a little
way aside and dimmed as it is covered, and carries the shadow of the arriving edge. The dimming
and that shadow run on past the page to the screen's own edges, so a turn reads as the whole
screen rather than a panel of it. One copy is kept per page for the reading session, and a page
that cannot be copied, or that has nothing on it, simply turns. While a turn is running the
arriving page is what sits under the reader's finger, so a tap on the edge it has already covered
is the one that waits for the next turn.
Highlights use stable text-node ranges and quote validation, painted without inserting
wrappers into the text. Modern WebView uses the CSS Highlight API; older versions use a
clipped overlay. Selections are limited to 10,000 characters, and notes to 2,000.
Highlight and note files are stored separately from the imported EPUB and are written
atomically. The notebook lists notes only; there are no free-standing notes, tags, or
folders. PDF and sync are not implemented yet. Read-aloud/listening is deliberately out
of scope. Import limit: 200 MB unpacked, 32 MB per file, 10,000 archive entries.
A backup is a zip of the app's own files — its shelf index, the imported books, highlights,
notes, reading places and settings — in a format marked `format: 1`. Only Moonlight Pages reads
them: a restore adds to what is on the phone and never removes or overwrites, a backup file
larger than 400 MB (or a newer format) is refused, and nothing inside a backup is trusted
with a path outside its own book's folder.

## Checks

On Windows, from this folder:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

> **Test safety:** Android's instrumented-test task installs this app, runs the
> tests, and by default **uninstalls it afterwards**. Uninstalling erases the app's
> private storage, which is where imported books, reading progress, bookmarks, and
> highlights are kept. `gradle.properties` therefore sets
> `android.injected.androidTest.leaveApksInstalledAfterRun=true`. Keep that line:
> without it, every `connectedDebugAndroidTest` run deletes your library. Android's own
> automatic backup stays switched off on purpose (`allowBackup` is `false`), so
> **Settings → Back up your library** is the way to keep this data — do that before any
> uninstall or reinstall, and before wiping a phone.

With a connected phone/emulator, run `:app:connectedDebugAndroidTest` for isolated
Import, navigation, and bookmark persistence tests � EPUB, FictionBook and MOBI, the last two
covering the conversions themselves: a chapter per titled section, a section inside one a chapter of
its own, the images written out, a book in windows-1251 read as written, footnotes keeping an anchor to reach, the contents naming every chapter, the same file recognised twice, a file that is neither read refused, and the imported book opening in the reader as a page with words on it � plus a reader UI test
covering bookmark creation, chapter navigation, returning to a bookmark, and
deletion. Reader-chrome and search tests cover the floating controls — that they stay
hidden while reading and answer the middle tap — plus the page
inset, the chapter page number, whole-book search results, reopening the matched
passage, and the no-matches state. These tests use separate sample libraries, not
your imported books. The book the app ships with is covered too: a test imports it
from the app's assets and checks that it arrives with its own cover, that the novel's
own navigation names its chapters, and that the text of chapter one is really there.
Pagination device tests cover horizontal gestures, blocked vertical scrolling,
page/chapter boundaries, text anchors across font/viewport changes and reopening,
and blocking untrusted document scripts. Page-turn tests cover the page being left held
underneath while the page arriving travels the whole width of the screen in from the far
side — forward and back — that it lands exactly where it belongs, that the turn's layer
spans the whole screen rather than the panel the page sits in, and that the page held for a
turn sits exactly where that page sat, so nothing on it jumps as the turn starts; that the
copy of the page being left carries that page's own words and paper; the turn's own
arithmetic — which way each page moves and how dark the covered one goes — is unit-tested. A
filmstrip of one turn, four frames across it, is rendered and saved for looking at by eye.
Generated JPEG/SVG fixtures also check image
bounds, stable page counts, and screenshot pixels for the toolbar/footer while turning
illustrated pages.
Highlight tests cover native long-press selection, saving/reopening, recolouring,
deletion, font changes, inline formatting, unchanged pagination, per-book storage,
and preserving malformed files instead of silently overwriting them. Note tests cover
writing a note while marking a passage, writing one on a highlight already saved,
gathering both in the notebook with the chapter name the book gives them, tapping a
notebook entry back to the page it was written about, removing a note again, reading
highlights saved before notes existed, and trimming a note to what will be read back.
The unit tests cover archive path safety, HTML sanitization, chapter text-node
indexing, text/page-aware bookmark matching, the book's own chapter plan, the way
a marked passage is named, and the names and sentence a backup is described with. Progress tests cover a shelf percentage that follows the
book's chapters rather than the files it is packed into, and the chapter plan that
counts them.
Polish checks cover short-screen library layouts, search retention and feedback,
imports across Activity recreation, index recovery/retry, in-place appearance
updates, stable page bounds while settings are open, and night-mode contrast. Appearance checks
cover the page being set in another face, with looser lines and a wider margin, the page script
laying the chapter out again around them (more room, more pages, the same words), the reader's
sheet offering every choice and keeping it, night dimming being remembered and coming back from a
backup, and the screen's own brightness being taken down — and left completely alone — as the
reader asks, rather than anything being laid over the page for the page turn to catch hold of.
Settings checks cover the header gear entry point, the two page-turn choices staying
exclusive, both toggles persisting, and back navigation, plus the two library rows
offering a backup and asking before a restore, with the rows disabled and a progress
line shown while one is running. Reader page-turn checks cover
both edge zones, the middle tap that shows and hides the controls without turning the page,
edge taps off again, and a swipe that
must not turn the page once edge taps are the chosen way. A swipe turns the page exactly as an
edge tap does: nothing travels with the finger, and the turn only begins once the finger is lifted.
A short unhurried swipe turns the page on distance alone, a quick short flick on speed alone, and
a touch that barely moved turns nothing. Shelf checks cover the progress
bar and finished flag on a cover, the **Continue reading** card naming a book and its
percentage and opening it, a percentage that follows the book's own chapters rather than
the files they are packed into, and a reader run that finishes a book by reading to the end.
Incoming-file checks cover the system offering Moonlight Pages for a tapped or shared EPUB, a new
book being added and opened, the very same file opening the copy already on the shelf
instead of importing a second one, a lookalike asking before a second copy is made, both
answers, the answer being remembered for that file, and a file that is not an EPUB being
refused with the shelf left untouched. The duplicate prompt is also covered by its own
Compose test.
Removal checks cover holding a cover to raise the prompt, **Keep it** changing nothing,
the import prompt naming the book that would go, deleting erasing only that book's copy,
position, bookmarks, highlights, and remembered file, the other book on the shelf keeping
everything it had, the file the book was imported from still being on disk afterwards,
resuming handing over to another book once the most recent one is gone, and a refusal to
remove a book that is not on the shelf.
Backup checks build a library on one phone (its own private storage) and put it back on
another: the books arrive with their own folders and cover image intact, and so do the
reading places, bookmarks, highlights, notes, remembered files and reading settings. They
also cover a backup adding to a shelf rather than replacing it, restoring the same backup
twice bringing nothing new the second time, an older backup never moving a reader
backwards or taking back a note written since, a file that is not one of Moonlight Pages'
backups being refused however it is shaped, a backup from a newer version saying so, a
book that did not arrive whole not being put on the shelf, and an entry trying to write
outside its own book's folder being refused. The shelf's own import buttons are checked to
stay untappable while a backup or a restore is running.

Two things to know before running device tests:

1. Keep the phone awake and unlocked. Compose device tests fail with
   `Cannot run onActivity since Activity has been destroyed already` if the
   screen turns off mid-run. Before a run, either keep tapping the phone or run
   `adb shell svc power stayon true` and
   `adb shell settings put system screen_off_timeout 3600000`.
2. Because the APKs are deliberately left installed, `com.moonlightpages.app.test`
   stays on the device afterwards. It has no launcher icon and can be ignored,
   or removed with `adb uninstall com.moonlightpages.app.test` (this does not touch
   `com.moonlightpages.app` or its books).

Manual checks: import a book, open it, swipe left/right, try swiping up/down (the
page must not scroll), switch chapters, change text size and paper, search for a
word, return to the library, rotate the phone, and reopen the app. In the page's own
**Aa** sheet, set the lines roomy, the margins wide and the face to sans: the same page
must reappear leaving the same words at the top, with more of them to the page, and never a
reload. Then take the night dimming down to 60% in a lit room: the whole screen must go
darker, and turning a page must leave the page being left and the page arriving at the same
brightness — no dark rectangle where the old page is. Verify the saved
reading location and imported book remain available offline. Tap the bookmark circle
at the top right to save/remove the current place. Tap the pill's **Bookmarks** icon
to return to or delete a saved place, or **Contents** for the chapter list; the
**Highlights** tab there lists saved passages. Long-press a word, choose **Highlight**,
write a line in **Your note**, then check that the marked words keep a finer line under
them, that **Contents → Notebook** lists the note under the chapter name the book gives
it, and that tapping the entry returns to those words. With the keyboard up, the colours
and **Save note** must stay in view. Named chapter links also support
in-chapter anchors. Open **Settings** from the header gear to choose how pages turn —
swiping or tapping the screen edges, never both at once — and to switch the screen-awake
option on or off. Then check in the reader that the chosen way turns the page — the arriving page travelling
in from off the screen, the page being left dimming and slipping aside under it, both pages
keeping their own words as they move — that a turn takes about half a second,
that tapping the middle of the page shows and hides the controls without turning anything,
and that tapping a link or a highlight still does its own thing. Back on the shelf, confirm the cover carries a progress bar whose percentage
follows the book's own chapters — a novel that packs a dozen chapters into one file should
creep forward, not jump in eighths — and that the
**Continue reading** card follows the book you had open most recently; read a short book
to its last page and confirm the cover gains its **Finished** flag, then turn back a page
and confirm the flag clears. To try the file-manager route, tap an EPUB in Files and choose
**Moonlight Pages**: the book should import and open straight away. Tap that same file again and it
should open the copy you already have; hand it a different file with the same title and
author and it should ask before adding anything. To try the backup, open
**Settings → Back up your library**, save the file somewhere you can reach — Downloads or a
cloud folder — and confirm the summary names your books; unzip it if you are curious and you
should find your books, your highlights and notes, and where you were in each one. Then
restore that same file: it should say everything is already on your phone, with your reading
places, bookmarks and notes exactly as they were. Restoring a backup made elsewhere is safe
to try too — nothing already on the phone is removed.
