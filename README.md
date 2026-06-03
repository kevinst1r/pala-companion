# Pala Companion

Android companion app for the **PALA One** e-ink reader. Connect to your reader over Wi‑Fi, open its web manager, and convert books and images into formats the device expects.

## Features

### Connect to reader
- Joins PALA reader Wi‑Fi networks (`PALA-*`) automatically
- Binds the app to the reader network and opens the in-app **Manage Reader** WebView
- **Restore Wi‑Fi** returns your phone to normal network routing when you are done

### Convert book
- Pick a `.txt` or `.epub` file from your device
- EPUB content is extracted and converted to plain text
- Output is saved to **Downloads** as normalized UTF‑8 text (`.txt`)

### Convert image
- Pick any image and open the built-in **Edit image** screen
- Pan, pinch-zoom, and position the image inside a fixed **250×122** preview frame
- Live 1-bit preview shows exactly what will be saved
- Adjust **black tolerance** with the slider, or tap the number to enter a value from `-100` to `100`
- Rotate 90°, invert colors, and optionally set a custom filename
- Saves a packed binary sleep image (`.bin`, 3904 bytes) to **Downloads**

## Requirements

- Android **10+** (API 29+)
- A PALA One reader broadcasting a `PALA-*` Wi‑Fi network
- **Location services** enabled (required by Android for Wi‑Fi scanning on many devices)
- Permissions granted when prompted: location and, on Android 13+, nearby Wi‑Fi devices

## Building from source

1. Clone the repository:
   ```bash
   git clone https://github.com/kevinst1r/pala-companion.git
   cd pala-companion
   ```

2. Open the project in **Android Studio** (Ladybug or newer recommended).

3. Build or run:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows:
   ```powershell
   .\gradlew.bat assembleDebug
   ```

4. Install the debug APK on a connected device, or use **Run** in Android Studio.

### Tech stack

| | |
|---|---|
| Language | Kotlin |
| Min SDK | 29 |
| Target SDK | 35 |
| UI | Material Components, AppCompat |
| Networking | OkHttp |
| EPUB parsing | Jsoup |

## Usage

1. **Connect** — Tap the connect button. The app requests Wi‑Fi permissions if needed, joins your reader, and opens the manager.
2. **Manage reader** — Use the embedded web UI to upload and manage content on the device. Tap **Restore Wi‑Fi** when finished.
3. **Convert book** — Choose a text or EPUB file. The converted `.txt` appears in Downloads.
4. **Convert image** — Choose an image, adjust crop and settings in the editor, then save. The `.bin` file appears in Downloads.

Converted files use names like `my_image_250x122_lsb.bin` or `my_book.txt`.

## Sleep image format

Images for the reader sleep screen are **250×122** pixels, **1 bit per pixel**, packed **LSB first within each byte row**:

| Property | Value |
|---|---|
| Dimensions | 250 × 122 |
| Bytes per row | 32 |
| Total size | 3904 bytes |
| Threshold | Luminance ≥ cutoff → white; default cutoff 128 at 0% tolerance |

Positive black tolerance treats more gray as black; negative tolerance keeps more gray as white.

## Project structure

```
app/src/main/java/com/pala/one/companion/
├── MainActivity.kt          # Home screen, Wi‑Fi connect, book/image pickers
├── ManagerActivity.kt       # In-app WebView for reader manager
├── ImageCropActivity.kt     # Image editor UI
├── CropImageView.kt         # Pan/zoom crop with live 1-bit preview
├── SleepImageConverter.kt   # Thresholding and packed byte output
└── DownloadsWriter.kt       # Saves files to Downloads via MediaStore
```

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Required by Android for Wi‑Fi network requests |
| `NEARBY_WIFI_DEVICES` | Wi‑Fi access on Android 13+ |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` / `CHANGE_NETWORK_STATE` | Connect and bind to reader Wi‑Fi |
| `INTERNET` | Reader manager WebView and API check |

## License

No license file is included yet. Add one if you plan to open-source or distribute the project.
