# File SPY (Android)

Pick a PDF or image -> see who made it, when, where, and with what.

## Build
1. Install Android Studio (Koala or newer).
2. File > Open > select this `FileSpy` folder; let Gradle sync
   (Android Studio creates the Gradle wrapper automatically).
3. Plug in a phone (USB debugging on) or start an emulator, press Run.
4. Installable APK: Build > Build Bundle(s)/APK(s) > Build APK(s).

## Notes
- Images: EXIF (camera, software, artist, dates, GPS + address).
- PDFs: Author, Creator, Producer, created/modified dates, pages, custom fields.
- Many apps/messengers strip metadata, so empty fields (-) are normal.
