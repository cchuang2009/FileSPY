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

## 360° photo
Tap **Take 360° photo**, hold the phone upright, press Start and turn slowly
to the right in a full circle. The app auto-captures 18 shots, stitches them
and saves one panorama to `Pictures/FileSPY` (tagged so Google Photos opens it as 360°).
Tips: stand still, turn on the spot, keep the phone level, use good light.

## Build
1. Install [Android Studio](https://developer.android.com/studio)
2. Open the `FileSpy` folder and wait for Gradle sync
3. Press ▶ Run (phone or emulator), or **Build → Build APK(s)**

## Notes
- Requires Android 10+ (API 29)
- Internet is only used to turn GPS coordinates into an address
- Files are read on-device and never uploaded
- Apps like LINE or WhatsApp strip metadata, so some fields may show "—"

## Tech
Kotlin · ExifInterface · pdfbox-android
