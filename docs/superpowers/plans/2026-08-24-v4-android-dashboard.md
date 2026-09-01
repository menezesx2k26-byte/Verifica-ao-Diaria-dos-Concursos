# Concursos Watch v4 Android Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** substituir apenas a Home Compose por um dashboard HTML/CSS local, baixado e validado pelo código nativo, mantendo todas as demais telas Android como estão.

**Architecture:** a camada HTTP nativa baixa manifest/HTML/CSS, valida host, MIME, tamanho, compatibilidade e SHA-256 e promove o bundle para `last_known_good`. A WebView nunca acessa a rede e não executa JavaScript. Deep links conhecidos retornam às telas nativas; URLs HTTPS oficiais abrem navegador externo.

**Tech Stack:** Kotlin, Jetpack Compose, Android WebView/WebKit, Room/DataStore existentes, coroutines, WorkManager, JUnit/AndroidX test.

**Spec:** `docs/superpowers/specs/2026-08-24-concursos-watch-v4-final-design.md`

## Global Constraints

- Somente Home muda para HTML/CSS dinâmico.
- JavaScript, DOM storage, file/content access e network loads da WebView ficam desativados.
- Bundle novo nunca substitui `last_known_good` antes de validação completa.
- Falha de rede ou validação mantém o bundle anterior.
- Sem bundle válido, mostrar fallback Compose mínimo.
- Alertas, Concursos, Favoritos, Detalhes e Configurações continuam nativos.

---

### Task 1: Modelos e validador do dashboard

**Files:**
- Create: `android/app/src/main/java/com/menezes/concursoswatch/model/DashboardModels.kt`
- Create: `android/app/src/main/java/com/menezes/concursoswatch/data/DashboardValidator.kt`
- Test: `android/app/src/test/java/com/menezes/concursoswatch/data/DashboardValidatorTest.kt`

**Interfaces:**
- Produces: `DashboardManifest`
- Produces: `DashboardBundle`
- Produces: `DashboardValidator.validateManifest(...)`
- Produces: `DashboardValidator.validateBundle(...)`

- [ ] **Step 1: Write failing validation tests**

```kotlin
@Test
fun rejectsHashMismatch() {
    val manifest = fixtureManifest(htmlSha256 = "00".repeat(32))
    val result = DashboardValidator.validateBundle(manifest, "<main>ok</main>".toByteArray(), "body{}".toByteArray())
    assertTrue(result is DashboardValidation.Invalid)
}

@Test
fun rejectsJavascriptAndRemoteResources() {
    val html = "<script>alert(1)</script><img src=\"https://evil.example/x.png\">"
    val result = DashboardValidator.validateHtml(html)
    assertTrue(result is DashboardValidation.Invalid)
}
```

- [ ] **Step 2: Define models**

```kotlin
data class DashboardManifest(
    val schemaVersion: Int,
    val dashboardVersion: Long,
    val styleVersion: Long,
    val minAppVersion: String,
    val publishedAt: String,
    val htmlUrl: String,
    val cssUrl: String,
    val htmlSha256: String,
    val cssSha256: String,
    val etag: String,
)

data class DashboardBundle(
    val manifest: DashboardManifest,
    val html: ByteArray,
    val css: ByteArray,
)

sealed interface DashboardValidation {
    data object Valid : DashboardValidation
    data class Invalid(val reason: String) : DashboardValidation
}
```

- [ ] **Step 3: Implement fail-closed validation**

Validation rules:

```kotlin
schemaVersion == 1
minAppVersion <= BuildConfig.VERSION_NAME without -dev suffix
htmlUrl/cssUrl use https and the configured official host
html <= 512 KiB
css <= 256 KiB
sha256 bytes match manifest
HTML rejects script, iframe, object, embed, form, meta refresh and remote src/href outside the local stylesheet contract
CSS rejects @import, javascript:, url(http, url(//
```

- [ ] **Step 4: Run tests**

Run: `gradle -p android :app:testDebugUnitTest --tests '*DashboardValidatorTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/menezes/concursoswatch/model/DashboardModels.kt \
        android/app/src/main/java/com/menezes/concursoswatch/data/DashboardValidator.kt \
        android/app/src/test/java/com/menezes/concursoswatch/data/DashboardValidatorTest.kt
git commit -m "feat(android): validate dynamic dashboard bundles"
```

### Task 2: Native downloader with ETag and strict host checks

**Files:**
- Create: `android/app/src/main/java/com/menezes/concursoswatch/data/DashboardRemoteDataSource.kt`
- Modify: `android/app/src/main/java/com/menezes/concursoswatch/data/RemoteDataSource.kt`
- Test: `android/app/src/test/java/com/menezes/concursoswatch/data/DashboardRemoteDataSourceTest.kt`

**Interfaces:**
- Produces: `suspend fun fetchManifest(etag: String?): DashboardManifestResult`
- Produces: `suspend fun fetchBundle(manifest: DashboardManifest): DashboardBundle`

- [ ] **Step 1: Write tests for host and MIME checks**

```kotlin
@Test
fun rejectsManifestFromUnexpectedHost() {
    val manifest = fixtureManifest(htmlUrl = "https://evil.example/dashboard")
    assertFailsWith<IllegalArgumentException> { source.validateManifestUrls(manifest) }
}

@Test
fun acceptsOnlyHtmlAndCssMimeTypes() {
    assertTrue(source.isAllowedMime("text/html; charset=utf-8", AssetKind.HTML))
    assertFalse(source.isAllowedMime("application/octet-stream", AssetKind.HTML))
}
```

- [ ] **Step 2: Implement native HTTP methods**

Reuse the current URL/HTTP pattern from `RemoteDataSource.kt`, but isolate dashboard logic. Never expose these bytes directly to WebView until `DashboardValidator` passes.

- [ ] **Step 3: Implement ETag short-circuit**

Contract:

```kotlin
sealed interface DashboardManifestResult {
    data object NotModified : DashboardManifestResult
    data class Modified(val manifest: DashboardManifest) : DashboardManifestResult
}
```

Send `If-None-Match` when local ETag exists and map HTTP 304 to `NotModified`.

- [ ] **Step 4: Run tests and commit**

```bash
gradle -p android :app:testDebugUnitTest --tests '*DashboardRemoteDataSourceTest'
git add android/app/src/main/java/com/menezes/concursoswatch/data/DashboardRemoteDataSource.kt \
        android/app/src/main/java/com/menezes/concursoswatch/data/RemoteDataSource.kt \
        android/app/src/test/java/com/menezes/concursoswatch/data/DashboardRemoteDataSourceTest.kt
git commit -m "feat(android): download dashboard through native network layer"
```

### Task 3: Last-known-good store in app-private storage

**Files:**
- Create: `android/app/src/main/java/com/menezes/concursoswatch/data/DashboardStore.kt`
- Test: `android/app/src/test/java/com/menezes/concursoswatch/data/DashboardStoreTest.kt`

**Interfaces:**
- Produces: `suspend fun current(): StoredDashboard?`
- Produces: `suspend fun promote(bundle: DashboardBundle): StoredDashboard`
- Produces: `data class StoredDashboard(...)`

- [ ] **Step 1: Write atomic promotion test**

```kotlin
@Test
fun failedWriteDoesNotReplaceLastKnownGood() = runTest {
    store.promote(validBundle(version = 1))
    store.simulateFailureAfterHtmlWrite = true
    runCatching { store.promote(validBundle(version = 2)) }
    assertEquals(1, store.current()!!.dashboardVersion)
}
```

Use a small injected filesystem abstraction in tests rather than mutable production flags.

- [ ] **Step 2: Implement staging directory + atomic rename**

Layout:

```text
files/dashboard/current/manifest.json
files/dashboard/current/index.html
files/dashboard/current/dashboard.css
files/dashboard/staging-<version>/...
```

Promotion sequence: write staging -> fsync/close -> validate again -> rename current to backup -> rename staging to current -> remove backup only after success.

- [ ] **Step 3: Persist metadata**

`StoredDashboard` includes:

```kotlin
val dashboardVersion: Long
val schemaVersion: Int
val styleVersion: Long
val etag: String
val htmlSha256: String
val cssSha256: String
val rootDir: File
```

- [ ] **Step 4: Run tests and commit**

```bash
gradle -p android :app:testDebugUnitTest --tests '*DashboardStoreTest'
git add android/app/src/main/java/com/menezes/concursoswatch/data/DashboardStore.kt \
        android/app/src/test/java/com/menezes/concursoswatch/data/DashboardStoreTest.kt
git commit -m "feat(android): persist last-known-good dashboard atomically"
```

### Task 4: Dashboard repository and background refresh

**Files:**
- Create: `android/app/src/main/java/com/menezes/concursoswatch/data/DashboardRepository.kt`
- Modify: `android/app/src/main/java/com/menezes/concursoswatch/ui/AppViewModel.kt`
- Test: `android/app/src/test/java/com/menezes/concursoswatch/data/DashboardRepositoryTest.kt`

**Interfaces:**
- Produces: `suspend fun refresh(): DashboardRefreshResult`
- Produces: `DashboardUiState`

- [ ] **Step 1: Write last-known-good fallback tests**

```kotlin
@Test
fun networkFailureKeepsCachedDashboard() = runTest {
    store.promote(validBundle(version = 7))
    remote.failWith(IOException("offline"))
    val result = repo.refresh()
    assertTrue(result is DashboardRefreshResult.KeptCached)
    assertEquals(7, store.current()!!.dashboardVersion)
}
```

- [ ] **Step 2: Implement refresh transaction**

```kotlin
suspend fun refresh(): DashboardRefreshResult {
    val cached = store.current()
    return when (val manifestResult = remote.fetchManifest(cached?.etag)) {
        DashboardManifestResult.NotModified -> DashboardRefreshResult.Unchanged(cached)
        is DashboardManifestResult.Modified -> {
            val bundle = remote.fetchBundle(manifestResult.manifest)
            require(validator.validateBundle(bundle) is DashboardValidation.Valid)
            DashboardRefreshResult.Updated(store.promote(bundle))
        }
    }
}
```

Catch network/validation errors and return `KeptCached(cached, reason)` rather than deleting cache.

- [ ] **Step 3: Add state to AppViewModel**

Add:

```kotlin
val dashboard: DashboardUiState = DashboardUiState.Loading
```

Refresh dashboard at app startup alongside current contest sync, but do not block navigation to other tabs.

- [ ] **Step 4: Run tests and commit**

```bash
gradle -p android :app:testDebugUnitTest --tests '*DashboardRepositoryTest'
git add android/app/src/main/java/com/menezes/concursoswatch/data/DashboardRepository.kt \
        android/app/src/main/java/com/menezes/concursoswatch/ui/AppViewModel.kt \
        android/app/src/test/java/com/menezes/concursoswatch/data/DashboardRepositoryTest.kt
git commit -m "feat(android): refresh dashboard with cached fallback"
```

### Task 5: Locked-down local WebView

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/menezes/concursoswatch/ui/DashboardWebView.kt`
- Test: `android/app/src/androidTest/java/com/menezes/concursoswatch/ui/DashboardWebViewSecurityTest.kt`

**Interfaces:**
- Produces: `@Composable fun DashboardWebView(stored: StoredDashboard, onNativeRoute: (Uri) -> Unit, onExternalUrl: (Uri) -> Unit)`

- [ ] **Step 1: Add WebKit dependency**

```kotlin
implementation("androidx.webkit:webkit:1.12.1")
```

- [ ] **Step 2: Configure WebView before any load**

```kotlin
settings.javaScriptEnabled = false
settings.domStorageEnabled = false
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
settings.blockNetworkLoads = true
```

Never call `addJavascriptInterface`.

- [ ] **Step 3: Serve local files through WebViewAssetLoader**

Map the private dashboard directory to a controlled local HTTPS-like origin. Only `index.html` and `dashboard.css` are resolvable.

- [ ] **Step 4: Intercept navigation**

`shouldOverrideUrlLoading` rules:

```kotlin
when {
    uri.scheme == "concursoswatch" && isKnownNativeRoute(uri) -> onNativeRoute(uri)
    uri.scheme == "https" && isOfficialExternalUrl(uri) -> onExternalUrl(uri)
    else -> Unit // block
}
return true
```

- [ ] **Step 5: Add instrumentation assertions**

Test that JavaScript is false, network loads are blocked, unknown HTTPS does not load inside WebView, and no JS bridge exists.

- [ ] **Step 6: Run instrumentation/emulator test and commit**

```bash
gradle -p android :app:connectedDebugAndroidTest
git add android/app/build.gradle.kts \
        android/app/src/main/java/com/menezes/concursoswatch/ui/DashboardWebView.kt \
        android/app/src/androidTest/java/com/menezes/concursoswatch/ui/DashboardWebViewSecurityTest.kt
git commit -m "feat(android): render dashboard in network-isolated WebView"
```

### Task 6: Replace Home only, preserve native fallback

**Files:**
- Create: `android/app/src/main/java/com/menezes/concursoswatch/ui/DashboardHomeScreen.kt`
- Modify: `android/app/src/main/java/com/menezes/concursoswatch/ui/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/menezes/concursoswatch/ui/ConcursosApp.kt`

**Interfaces:**
- Consumes: `DashboardUiState`, `DashboardWebView`.
- Produces: Home route behavior.

- [ ] **Step 1: Keep current Home as fallback**

Rename current exported composable to:

```kotlin
@Composable
fun NativeHomeFallbackScreen(...)
```

Do not delete the current visual system; it is the bootstrap/error fallback.

- [ ] **Step 2: Add dynamic Home wrapper**

```kotlin
@Composable
fun DashboardHomeScreen(vm: AppViewModel, onNativeRoute: (Uri) -> Unit) {
    when (val dashboard = vm.state.dashboard) {
        is DashboardUiState.Ready -> DashboardWebView(dashboard.stored, onNativeRoute, ::openExternal)
        is DashboardUiState.Loading -> NativeHomeFallbackScreen(...)
        is DashboardUiState.Unavailable -> NativeHomeFallbackScreen(...)
    }
}
```

- [ ] **Step 3: Wire only route `home`**

In `ConcursosApp.kt`, replace `HomeScreen(...)` with `DashboardHomeScreen(...)`. Do not alter Alerts/Contests/Favorites/Settings routes.

- [ ] **Step 4: Build and run Compose smoke test**

Run:

```bash
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/menezes/concursoswatch/ui/HomeScreen.kt \
        android/app/src/main/java/com/menezes/concursoswatch/ui/DashboardHomeScreen.kt \
        android/app/src/main/java/com/menezes/concursoswatch/ui/ConcursosApp.kt
git commit -m "feat(android): make Home dashboard dynamic with native fallback"
```

### Task 7: Version bump and visual regression gate

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `.github/workflows/android-visual-qa.yml`

**Interfaces:**
- Produces: `versionName 4.0.0`, `versionCode 7`.
- Produces: screenshots of dynamic Home plus native tabs.

- [ ] **Step 1: Bump version**

```kotlin
versionCode = 7
versionName = "4.0.0"
```

- [ ] **Step 2: Seed a validated dashboard fixture in visual QA**

Before launching the app, either point debug build at a staging Rust endpoint or inject a fixture through a debug-only test hook. Do not enable WebView network access for QA.

- [ ] **Step 3: Capture six screenshots**

Artifacts:

```text
01-home-dynamic.png
02-home-offline-fallback.png
03-alertas.png
04-concursos.png
05-salvos.png
06-ajustes.png
```

- [ ] **Step 4: Run build + QA**

Run: existing Android Visual QA workflow.

Expected: build success, instrumentation security test success, screenshot artifact uploaded.

- [ ] **Step 5: Commit**

```bash
git add android/app/build.gradle.kts .github/workflows/android-visual-qa.yml
git commit -m "test(android): gate v4 dashboard with emulator visual QA"
```
