# ScribbleX — Minimalist note-taking for Android

<div align="center">

*Rich-text notes, task lists, and reminders — offline-first, FOSS, and yours to keep.*

[Download](https://github.com/alzimerahmed/ScribbleX/releases) • [Features](#features) • [Building](#building)

</div>

---

## Features

- **Rich-text notes** — bold, italics, monospace, and strikethrough with undo/redo
- **Task lists** with subtasks, auto-sorting of checked items, and quick clear actions
- **Reminders** — time-based notifications and location-based geofence reminders
- **Attachments** — images, PDFs, and any file type, plus quick audio notes
- **Organisation** — colors, pins, labels, list or grid layouts, and a home-screen widget
- **Full-text search** — ranked search across titles and note content
- **Self-hosted sync** — optional WebDAV/Nextcloud sync with end-to-end encryption
- **Privacy** — biometric/PIN lock, optional database encryption, no trackers, no network access except your own sync server
- **Auto-backups** — configurable scheduled and on-save backups to local storage
- **Import** from NotallyX, Notally, Evernote, Google Keep, Quillpad, plain text, and markdown
- **Biometric lock**, auto-sorting, clickable links, and extensive view preferences
- Runs on Android 5.0 (Lollipop) and up

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin (Coroutines, Serialization) |
| UI | Android Views (XML) + Material Components, ViewBinding |
| Database | Room + optional SQLCipher encryption, FTS4 search |
| Background work | WorkManager, AlarmManager, LocationManager geofencing |
| Sync | WebDAV over HttpURLConnection, AES-256-GCM payload encryption |
| Build | Gradle Kotlin DSL, KSP, ktfmt |
| Testing | JUnit, Robolectric, MigrationTestHelper |

## Project Structure

```
app/src/main/java/com/philkes/notallyx/
├── data/
│   ├── dao/            # Room DAOs
│   ├── model/          # Entities, FTS, converters
│   ├── repository/     # Repository interfaces + Room impls
│   └── imports/        # Evernote / Keep / Quillpad / markdown importers
├── presentation/
│   ├── activity/       # Edit, reminders, audio recording
│   ├── fragment/       # Main navigation fragments
│   └── viewmodel/      # ViewModels + use-cases
└── utils/
    ├── backup/         # Auto-backup, export, import
    └── sync/           # WebDAV sync engine, crypto, workers
```

## Building

```bash
git clone https://github.com/alzimerahmed/ScribbleX.git
cd ScribbleX
./gradlew assembleDebug
```

The debug APK is output to `app/build/outputs/apk/debug/`. Release builds are produced by CI from version tags; see `.github/workflows/release.yml` for the signing setup.

## Usage

Create a note, add labels and colors, pin it, and set a reminder — everything is stored locally. To sync between devices, enable sync in Settings, point it at any WebDAV-capable server (Nextcloud works), and set a sync password; payloads are end-to-end encrypted so the server only ever stores ciphertext.

Coming from NotallyX? Export a backup there (ZIP or encrypted), then use *Import from other apps* in ScribbleX. ScribbleX is a separate app with its own identity — there is no in-place upgrade path.

## FAQ / Troubleshooting

**Why should I enable auto-backup?**
Backups are your safety net for any note app. Enable daily auto-backup (and ideally backup-on-save) in Settings.

**Is my data sent anywhere?**
No, unless you enable self-hosted sync. Even then, notes are end-to-end encrypted before they leave the device, and only you hold the key.

**Where are releases?**
Signed release APKs are published on the [GitHub Releases](https://github.com/alzimerahmed/ScribbleX/releases) page. BETA builds use a separate applicationId (`.beta`) so they install alongside the release app.

## Contributing

Fork the repo, pick an issue, and open a pull request. The project is a standard Android project in Kotlin — Android Studio recommended. Before opening a PR, run `./gradlew test` and `./gradlew ktfmtFormat` (the latter also runs automatically as a pre-commit hook).

## Roadmap

- [x] Full-text search, location reminders, self-hosted sync
- [ ] Markdown editing mode (parser in place)
- [ ] Drawing notes
- [ ] Voice transcription

## License

GPL-3.0. The original Notally was developed by [OmGodse](https://github.com/OmGodse/Notally) and NotallyX by [Philkes](https://github.com/Philkes) (Crustack), both under GPL-3.0 — this fork keeps that license and their attribution. Maintainer: Alzimer Ahmed (alzimerahmed84@gmail.com).
