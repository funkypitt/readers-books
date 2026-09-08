# Reader's Books

A black-and-white e-book reader for Android, in the family of
[Reader's Launcher](https://github.com/funkypitt/readers-launcher), whose built-in reader it
extends into a shelf of books.

The shelf is a list: title, how far you are, when you last opened it. Tap to read. The page
is plain text, cut at whole lines, never through a line: the right half of the screen turns
forward, the left half back, a long press opens the menu (chapters, larger or smaller text,
back to the shelf, remove). The reading position is remembered per book and survives a
change of text size or screen. The screen stays on while a page is open (fifteen minutes
after the last turn).

Formats: EPUB, MOBI (PalmDoc), FB2 and plain text. Images, styles and footnote links are
dropped; only the text remains. Open a book from the shelf, from a file manager ("open
with"), or by sharing the file to the app. Books are copied into the app, so the original
can move.

## Install

From the [F-Droid repo](https://funkypitt.github.io/fdroid-repo/) or the APK attached to a
release. Build with `./gradlew assembleDebug` (JDK 17+, Android SDK 35).

## Licence

MIT.
