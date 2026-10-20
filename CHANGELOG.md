# Release notes

## 1.0 — 21 October 2026

The first release.

**Reading**

- One page at a time, turned by a swipe or by tapping the page edges — whichever you choose, never
  both. A swipe acts when the finger lifts, so the page never drags along under it.
- A page turn that reads like paper: the arriving page travels in from the screen's edge over the
  page being left, which darkens under a shadow and slips aside.
- Contents, text size, line spacing, margins and typeface (serif or sans), applied to the page
  already open, with a **Continue reading** card on the shelf and a place kept for every book.
- White, sepia and night paper; night dimming that takes the screen's own brightness below what the
  phone normally offers; a daylit theme and a moonlight one, or following the phone.
- Bookmarks per chapter, search inside a book, highlights with notes, all kept with the book and
  carried in a backup.

**Formats**

- **EPUB 2 and 3** as it is: the book's own package, chapters, cover and navigation.
- **FictionBook (FB2)** converted at import: a chapter per titled section, contents, images, linked
  notes; UTF-8 and windows-1251.
- **MOBI** (PalmDOC) converted at import: plain text, back-references, raw runs and the space-plus-
  letter code, both byte orders of the MOBI header, pictures and cover.

Refused with a plain explanation rather than half-read: DRM, AZW3 (KF8), MOBI compressed with
Amazon's Huffman dictionary, a MOBI whose header claims more text than the file could hold,
encrypted fonts, and fixed-layout or scripted EPUBs.

**Keeping a library**

- A single zip backup of the shelf, reading places, bookmarks, highlights, notes and settings, which
  restores by merging: a place moves only when the backup's is further along, a note written since
  is never taken back.

**Privacy**

- No account, no analytics, no ads, no network permission at all.

**Under the hood**

- Kotlin and Jetpack Compose, `minSdk 26`, `targetSdk 37`, GPL-3.0.
- 92 unit tests and 145 tests that run on a phone, including hand-built EPUB, FB2 and MOBI files put
  through the real import path.
