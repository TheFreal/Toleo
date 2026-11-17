# Toleo

An Android reader for your tolino cloud library, built for the Pixel 10 Pro Fold: open-book two-page view when unfolded, page-flip animations, warm/sepia/dark/AMOLED-black modes, justified text, highlights and notes that sync with tolino.

Package: `de.freal.toleo` · Kotlin, Jetpack Compose, Readium 3.4, Room, WorkManager, OkHttp.

## Build and install

Requirements: Android SDK with platform 37, JDK 17+ (Android Studio's bundled JBR works).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and run the `app` configuration.

## Using it

1. Pick your bookseller and sign in on its own page (a WebView; Toleo never sees the password).
2. The phone registers as a tolino device (an account allows five; manage them under Account & devices).
3. Tap a book to download and open it. Long-press a book for download/remove, finished, or delete (uploads).
4. In the reader: tap the left/right edge or use the volume keys to turn pages; tap the centre for the toolbar; long-press text for **Highlight** / **Note**; tap a highlight to edit it.
5. EPUBs from other apps can be opened with "Open with Toleo". These are read locally and never synced.

Positions are saved locally as you read and pushed when you leave a book, on pull-to-refresh, and every 30 minutes.

## Known limits

- Adobe DRM and Readium LCP titles are listed but can't be opened (licensed SDK needed); their positions still sync.
- PDFs open in the system PDF viewer.
- Highlight colours are local only; tolino has no colour field.
- Page-flip animation is still a bit glitchy and applies only to taps and volume keys; swiping uses Readium's slide.

## Layout

```
app/src/main/java/de/freal/toleo/
  tolino/     partner config, OAuth (WebView token client), REST API, diagnostics
  db/         Room entities and DAOs
  sync/       SyncEngine (sync-data patches) and SyncWorker
  position/   TextIndex/TolinoPoint (point paths ↔ text offsets), PositionMapper (↔ Readium Locator)
  library/    library/reading repositories, DRM detection, Readium opener
  reader/     ReaderActivity, ReaderScreen, page flip, preferences
  ui/         login, library, account screens
app/src/main/assets/partners.json   partner URLs and IDs (edit here when they change)
```

## Acknowledgements

Toleo wouldn't exist without the people who figured out the tolino cloud API and shared it:

- **[Christian Weiske (cweiske)](https://github.com/cweiske/tolino-api-docs)**: the [Tolino cloud API documentation](http://cweiske.de/tolino-api-docs.htm), including the `sync-data` patch format and the login and sync sequences.
- **[darkphoenix/tolino-calibre-sync](https://github.com/darkphoenix/tolino-calibre-sync)**: `tolinocloud.py`, the web reader client this app's partner OAuth settings, device registration and download flow come from.
- **[ImamAzim/pytolino](https://github.com/ImamAzim/pytolino)**: a current web reader client covering token refresh, collections, the finished state and downloads.
- **[moritzkeller12-beep/calibre-tolinocloud-Plugin#28](https://github.com/moritzkeller12-beep/calibre-tolinocloud-Plugin/pull/28)** and **[TheBlackPrism/Retrospine#2](https://github.com/TheBlackPrism/Retrospine/pull/2)**: recent findings on the shared web reader login.

Rendering is done by the [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit).
