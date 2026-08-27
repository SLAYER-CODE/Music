# AGENTS.md

## Project

Android music player (`com.music.app`) that mixes YouTube audio (via NewPipeExtractor/Innertube API) with local device files. Single Gradle module `:app`. UI is Jetpack Compose + Material3, playback is Media3/ExoPlayer, persistence is Room, DI is Koin.

## Commands

- Verification = successful compile. **There are no test suites in this repo** (no `test/` or `androidTest/` source sets, no CI).
  - Full check: `./gradlew :app:assembleDebug`
  - Fast syntax check after edits: `./gradlew :app:compileDebugKotlin`
- Requires JDK 17 (source/target compatibility pinned in `app/build.gradle.kts`). Gradle 9.4.1 wrapper. Repos: google/mavenCentral/**JitPack** (NewPipeExtractor comes from JitPack).
- Room entities/DAOs use **KSP** codegen — always recompile after touching `data/local/*`; generated sources go to `app/build/generated`.

## Architecture wiring

- Entry points: `MusicApp` (starts Koin with `appModule`) → `MainActivity` → `MainScreen`. Almost all state flows through the single `MainViewModel`.
- `appModule` (`di/AppModule.kt`) includes `cacheModule` (`player/CacheModule.kt`); the latter also initializes NewPipe with a custom OkHttp `Downloader`.
- Two Media3 `SimpleCache` instances are distinguished by the Koin qualifier enum `CacheType`: `CACHE` = streaming cache (NoOpCacheEvictor), `DOWNLOAD` = persistent downloads (LRU 512MB). Resolve as `get<Cache>(CacheType.X)` — never inject `Cache` unqualified.
- Two foreground services in the manifest: `.player.MusicService` (`MediaSessionService`, type `mediaPlayback`) and `.download.MyDownloadService` (Media3 `DownloadService` restart action, type `dataSync`).
- `MainViewModel` receives 10 **positional** constructor arguments from Koin (repository, innertube, downloadHelper, musicScanner, musicServiceConnection, connectivityManager, streamCache, streamResolver, appContext, okHttpClient) — preserve their order when adding/removing dependencies.
- `DownloadHelperImpl.instance` is a static reference assigned during DI graph creation; downloads route through it into `MyDownloadService`.
- **Cache-key split:** Media3 downloads are stored under the **raw** YouTube id (`toggleDownload`/`downloadSong` pass `song.id` straight to `addDownload`, which becomes the `customCacheKey`), but every span read path (`mergedCachedRanges`, `exportToSd`, `purgeCaches`) looks up `"yt://$id"`. Download-cache spans are therefore invisible to those lookups — query both keys or standardize before assuming a span is missing.

## Gotchas & conventions

- **Read `ANCHORED_SUMMARY.md` before touching `StreamResolver.kt`, `CachedSeekBar.kt`, or `MainViewModel.onPlayerError`.** It documents hard-won constraints of the offline playback flow: skip immediately on cache exhaustion (no Loader backoff retry), snap-to-span-start only on the last cached span, no span calculation mid-drag, offline fallback uses a `data:` URI (never an http dummy URL).
- `res/xml/network_security_config.xml` allows cleartext traffic globally — do not remove it without checking stream resolution paths.
- Dependency versions live only in `gradle/libs.versions.toml`; reference them via catalog aliases (`libs.*`), never hardcode versions in module build files.
- Never commit: `local.properties`, `kls_database.db`, `build/`, `.gradle/`, `.kotlin/`, APKs/zips (covered by `.gitignore`).
