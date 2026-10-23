# 🔍 File SPY

An Android app that reveals the hidden information inside a **PDF** or **image**: who made it, when, where, and with what.

## Features
- Pick a PDF or image from your phone
- Shows **who** made it (author, camera, software)
- Shows **when** it was created / modified
- Shows **where** it was made (GPS + address, for photos that have it)
- Copy the report with one tap

## What it reads
| File | Information |
|------|-------------|
| Image | EXIF: camera make/model, artist, software, date taken, GPS, exposure, ISO |
| PDF | Author, creator app, producer, title, created/modified dates, pages, custom fields |

## Build
1. Install [Android Studio](https://developer.android.com/studio)
2. Open the `FileSpy` folder and wait for Gradle sync
3. Press ▶ Run (phone or emulator), or **Build → Build APK(s)**

## APK
[APk]

## Notes
- Requires Android 8.0+ (API 26)
- Internet is only used to turn GPS coordinates into an address
- Files are read on-device and never uploaded
- Apps like LINE or WhatsApp strip metadata, so some fields may show "—"

## Tech
Kotlin · ExifInterface · pdfbox-android
