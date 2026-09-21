# ScribbleX — Rules for AI Agents

## Project

ScribbleX = fork of Crustack/NotallyX (upstream: https://github.com/Crustack/NotallyX). FOSS minimalist note-taking app for Android (rich-text notes, task lists with subtasks, reminders, file/image attachments, labels/colors/pins, biometric lock, auto-backup, import from Keep/Evernote/Quillpad, home-screen widget, audio notes). Fully independent from upstream (no sync; upstream history dropped at fork). GitHub Releases + F-Droid ONLY.

**License:** GPL-3.0 (inherited from Notally via NotallyX) — keep it, keep attribution to OmGodse (Notally) and Philkes (NotallyX) in LICENSE/CONTRIBUTORS.

**Scope:** Android-only. Min SDK 21, Compile/Target SDK 36, JVM target 1.8. No iOS/desktop/CLI targets exist or are planned.

## Tech Stack & Conventions (inherited from upstream — do not fight it)

### Language & Tooling
- **Language**: Kotlin (standard idioms, Coroutines, Serialization).
- **Build System**: Gradle Kotlin DSL (`build.gradle.kts`), Android Gradle Plugin, KSP for Room compiler.
- **Code Formatter**: `ktfmt` with Kotlin standard style (`kotlinLangStyle`) — run `./gradlew ktfmtFormat` before committing.

### UI Framework & Conventions
- **UI System**: Android Views (XML layouts) with MaterialComponents / Material 3 styling — NOT Compose (do not introduce casually; ADR-level decision).
- **View Binding**: ViewBinding and DataBinding enabled.
- **Navigation**: Jetpack Navigation Component with NavHostFragment and XML navigation graphs (`navigation.xml`).
- **Lists**: `RecyclerView` with custom ViewHolders (`*VH`), adapters (`*Adapter`), `ItemTouchHelper` for drag/swipe.

### State Management & Concurrency
- **State Holders**: `AndroidViewModel` subclasses (`BaseNoteModel`, `NotallyModel`) exposing `LiveData`/`NotNullLiveData`.
- **Async**: Kotlin Coroutines (`viewModelScope`), `Dispatchers.IO` for DB/file/parsing work. Handle exceptions via `CoroutineExceptionHandler` or try-catch around suspend calls.
- ⚠️ `BaseNoteModel` is a god class (~1,080 lines) — decompose into use-case/repository classes opportunistically (see docs/plan.md F2).

### Database & Storage
- **Database**: Room (`NotallyDatabase`, versioned, KSP schema exports in `app/schemas/`).
- **Encryption**: SQLCipher (`net.zetetic:sqlcipher-android`) optional DB encryption; EncryptedSharedPreferences for sensitive settings.
- **Storage**: internal storage default; optional `dataInPublicFolder` mode. Always `database.checkpoint()` (WAL flush) before any DB export/backup/replacement.
- **Type Converters**: `Converters.kt` (spans, attachments, folders, reminders, labels → JSON).

### i18n
- Crowd-sourced translations (TRANSLATIONS.md) — all UI text via `R.string.*`, never hardcoded.

## Build / Verify

```bash
./gradlew test            # unit tests (JUnit + Robolectric)
./gradlew testDebugUnitTest
./gradlew ktfmtFormat     # format (ktfmtCheck = check only)
./gradlew lint            # Android lint
./gradlew assembleDebug   # debug APK — CI verifies; avoid locally while iterating
```

**Remote-first verification:** full gates on CI, not local. Local tiered: targeted `./gradlew test --tests "..."` / lint while iterating → scoped checks before commit → CI before merge. Never run `assembleDebug` locally while iterating. Full local builds only for on-device APKs or build-system debugging.

**IMPORTANT — small-batch local builds (mandatory):** Gradle+Android toolchain is heavy. One task per invocation; scoped tests > whole-suite runs while iterating. Let Gradle cache work; don't re-run green tasks. Long build → background + poll.

## Gotchas

- Upstream had real data-loss reports (#1066 — app temporarily pulled from Play Store): **backup/restore and Room migration code is the most sensitive area**. Never weaken auto-backup; always write Room migrations + migration tests; no destructive schema changes.
- BETA releases use a different applicationId (separate app/data) — preserve that split if touching build config.
- Renaming applicationId/namespace (`com.philkes.notallyx`) to a ScribbleX identity is a deliberate ADR decision — do NOT rename casually (breaks update path + user data).
- `simple-xml` (Evernote import) is unmaintained — pin and keep sandboxed to the importer.

## Agent Guidelines & Constraints

### Do's
- **Preserve Data Integrity**: never drop tables destructively without valid migration paths.
- **Run Checkpoints Before DB Copy**: `database.checkpoint()` before exporting/backing up/copying SQLite files.
- **Use ViewBinding**: `ActivityEditBinding.inflate(layoutInflater)` etc.
- **Offload Heavy Work**: file I/O, parsing, DB queries on `Dispatchers.IO`.
- **Format with ktfmt**: `./gradlew ktfmtFormat`.
- **Follow Existing Patterns**: naming `*Activity`, `*Fragment`, `*Model`, `*VH`, `*Adapter`, `*Dao`.
- **Write Unit & Robolectric Tests** under `app/src/test/` for business logic, migrations, parsers.

### Don'ts
- **NO Blocking Main Thread**: no synchronous DB queries, heavy regex, or file ops on main.
- **NO Raw SQL Injections**: use Room DAO annotations or parameterized `SupportSQLiteQuery`.
- **NO Hardcoded Strings**: string resources for all UI-visible text (30+ languages).
- **NO Unapproved Third-Party Libraries**: avoid heavy deps unless explicitly requested; respect FOSS/F-Droid (no GMS/proprietary deps).

## Common Tasks & Workflows

### 1. Adding a New Screen / Feature
1. **Layout**: XML layout in `app/src/main/res/layout/` with ViewBinding support.
2. **ViewModel**: expose `LiveData`/`NotNullLiveData` in an existing or new `AndroidViewModel`.
3. **Activity/Fragment**: create in `presentation/activity/` or `presentation/activity/main/fragment/`; bind via ViewBinding; observe LiveData in `onCreate()`/`onViewCreated()`.
4. **Navigation**: declare destination in `res/navigation/navigation.xml`; register menu actions in `MainActivity`.
5. **Manifest**: register new Activities in `AndroidManifest.xml`.

#### Example ViewModel Pattern:
```kotlin
class FeatureViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableLiveData<FeatureUiState>(FeatureUiState.Loading)
    val uiState: LiveData<FeatureUiState> = _uiState

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = fetchData()
                _uiState.postValue(FeatureUiState.Success(data))
            } catch (e: Exception) {
                _uiState.postValue(FeatureUiState.Error(e.message ?: "Unknown error"))
            }
        }
    }
}
```

#### Example Activity ViewBinding Pattern:
```kotlin
class FeatureActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFeatureBinding
    private val viewModel: FeatureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        observeViewModel()
        viewModel.loadData()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is FeatureUiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                is FeatureUiState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    binding.textViewContent.text = state.data
                }
                is FeatureUiState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showToast(state.message)
                }
            }
        }
    }
}
```

### 2. Creating a Database Migration
1. Update entity classes in `com.philkes.notallyx.data.model`.
2. Increment `version` in `@Database(entities = [...], version = NEW_VERSION)` in `NotallyDatabase.kt`.
3. Define the `Migration` object:
   ```kotlin
   val MIGRATION_11_12 = object : Migration(11, 12) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE notes ADD COLUMN new_feature_column TEXT DEFAULT NULL")
       }
   }
   ```
4. Add it to the `MIGRATIONS` array in `NotallyDatabase.kt`.
5. Run `./gradlew kspDebugKotlin` to regenerate the Room schema JSON in `app/schemas/`.
6. Write migration verification tests in `app/src/test/kotlin/com/philkes/notallyx/data/` (MigrationTestHelper — mandatory, see Phase 6).

### 3. Writing and Running Tests
Unit + Robolectric tests (`testOptions.unitTests.isIncludeAndroidResources = true`).

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteHelperTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun testNoteTransformation() {
        val note = BaseNote(
            id = 1L,
            title = "Test Title",
            body = "Test Body",
            type = Type.NOTE,
            folder = Folder.NOTES
        )
        val result = note.toSearchResult()
        assertThat(result.title).isEqualTo("Test Title")
    }
}
```

## Code Style & Quality Rules

1. **Formatting**: ktfmt Kotlin style (4-space indent, strict import ordering). `./gradlew ktfmtFormat` before committing.
2. **Naming**: entities/models PascalCase (`BaseNote`, `Label`); DAOs `*Dao`; ViewModels `*Model`/`*ViewModel`; UI `*Activity`/`*Fragment`; adapters `*Adapter`/`*VH`; prefs in `NotallyXPreferences`.
3. **Error Handling**: catch specific exceptions; user feedback via `showToast`/`showSnackbar`/dialogs; background jobs update progress observers.

## Devin Entry Point

Auto-loaded by Devin every session. Entry to prompt system in `.devin/prompt/`. Read `.devin/prompt/map.md` before any task — system map.

## Resource Discipline (mandatory, non-trivial tasks)

Before any non-trivial task:
1. Read `docs/toolset.md` intent-map (task type → resources)
2. Invoke every skill + sub-agent in that row
3. Read every rule for that task type (`.devin/rules/`)
4. At task end: `code-reviewer` sub-agent on final diff (non-negotiable)
5. Append learnings via `/ce-compound` if durable lesson

**Background sub-agents:** run sub-agents background when result not immediately needed; keep working while they run. Launch independent sub-agents parallel. Block only when sub-agent output = hard dependency for next step.

Phase implementations (task completes a docs/plan.md row): /ce-work mandatory.

Skip all this for single-line edits, pure Q&A, reading files.

## Project-Type Filter (native Android app, Kotlin)

Not a website. Per `docs/toolset.md` intent-map:
- **Skip web-only:** frontend-designer, css-architect, pwa-engineer, seo-specialist, search-optimization, playwright-design-clone.
- **Keep universal:** code-reviewer, debugger, test-engineer, security-auditor (biometric lock + backups are security-critical), performance-engineer, git-master, migration-specialist, docs-writer, i18n-specialist, build-optimizer, caveman-compressor, pixel-analyst, vibe-coding-auditor, type-safety-engineer, database-engineer (Room), state-manager (ViewModel/Flow).
- **Quality gates:** `./gradlew test`, `./gradlew lint`, `./gradlew ktfmtCheck` — no browser tooling. Respect FOSS: no GMS/proprietary deps (F-Droid compatible).

## Communication Style

Default **caveman-lite** (lightly compressed, readable, technically accurate). `/caveman` skill for full/ultra/wenyan modes.

## Quick Task Flow

Quick tasks: `.devin/prompt/quick.md` (commandments) + `.devin/prompt/rules.md` (scoping, verification, escalation). Phased work: `.devin/prompt/phase.md`.

## Key References

- `docs/toolset.md` — intent map (task type → skills, sub-agents, rules)
- `docs/plan.md` — phased plan + status (Phase 7 = Features F1–F7)
- `docs/project.md` — project state/structure
- `docs/tools-log.md` — .devin resources invoked per session
- `docs/CONCEPTS.md` — project vocabulary
- `docs/research.md` — research, ADRs, gotchas, open questions
- `docs/idea.md` — competitive analysis + Feature Gap List
- Upstream docs — https://crustack.github.io/NotallyX/ for feature reference (no code sync)
