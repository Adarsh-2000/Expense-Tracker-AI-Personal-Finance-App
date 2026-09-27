# Ledger Noir — Native Android Companion App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **IMPORTANT — Build environment notice:** This plan was authored in the cloud environment where the FastAPI backend lives. The Android app itself **must be built in Android Studio on the developer's local machine**. This plan cannot be executed in the cloud environment. Requirements:
> - Android Studio Hedgehog (2023.1.1) or newer
> - Android SDK 34 (API 34 target, API 26 minimum)
> - Kotlin 1.9+ (bundled with Android Studio)
> - Gradle 8.x (managed by the Android Studio wrapper — do not run `gradle` manually from a non-Android-Studio shell)
> - An AVD (Android Virtual Device) running API 26+ — the emulator host address `10.0.2.2` maps to your laptop's `localhost`, so `http://10.0.2.2:8000` reaches the FastAPI server

**Goal:** Build a lean native Kotlin/Compose Android app that (1) silently forwards bank SMS messages to the already-running FastAPI backend, and (2) provides a read-only companion dashboard for the expense data — lock screen, dashboard, transactions, manual entry, and budget — styled to match the Ledger Noir web UI.

**Architecture:** A `BroadcastReceiver` intercepts bank SMS, filters by sender ID whitelist (AXISBK/IDFCFB/BOB), and POSTs the raw text to `POST /ingest/sms` — no client-side parsing. A single-activity Compose app with a `NavHost` hosts five screens: Lock, Dashboard, Transactions (+ manual-add bottom sheet), Budget, and Settings. Retrofit + Moshi drive all API calls from a repository layer. DataStore persists the base URL, the bearer token, and (optionally) the PIN-enabled flag. An OkHttp interceptor attaches the bearer token to every request when the lock is enabled.

**Tech Stack:** Kotlin 1.9, Jetpack Compose (BOM 2024.04), Material3, Retrofit 2.9, Moshi 1.15, OkHttp 4.12, kotlinx.coroutines 1.8, DataStore Preferences 1.1, Navigation Compose 2.7, JUnit 4, Robolectric (for SmsFilter unit test).

---

## File Map

```
android/                                    ← root Android Studio project
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   └── java/com/ledgernoir/tracker/
│   │   │       ├── MainActivity.kt
│   │   │       ├── LedgerNoirApp.kt            (Application subclass)
│   │   │       ├── sms/
│   │   │       │   ├── SmsFilter.kt            (pure Kotlin — JUnit-testable)
│   │   │       │   └── SmsReceiver.kt          (BroadcastReceiver)
│   │   │       ├── data/
│   │   │       │   ├── TokenStore.kt           (DataStore: base URL + token)
│   │   │       │   ├── AuthInterceptor.kt      (OkHttp interceptor)
│   │   │       │   ├── ExpenseApi.kt           (Retrofit interface + data classes)
│   │   │       │   └── ExpenseRepository.kt    (suspend funs used by ViewModels)
│   │   │       └── ui/
│   │   │           ├── theme/
│   │   │           │   ├── Theme.kt
│   │   │           │   ├── Color.kt
│   │   │           │   └── Type.kt
│   │   │           ├── LockScreen.kt
│   │   │           ├── DashboardScreen.kt
│   │   │           ├── TransactionsScreen.kt
│   │   │           ├── BudgetScreen.kt
│   │   │           └── SettingsScreen.kt
│   │   └── test/
│   │       └── java/com/ledgernoir/tracker/
│   │           └── sms/
│   │               └── SmsFilterTest.kt
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts                        (root)
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml
```

---

## Phases

- **Phase 1 — Project scaffold + theme**
- **Phase 2 — SmsFilter (TDD)**
- **Phase 3 — Data layer (TokenStore, AuthInterceptor, Retrofit, Repository)**
- **Phase 4 — SmsReceiver**
- **Phase 5 — Permission flow**
- **Phase 6 — Compose screens**
- **Phase 7 — Settings screen**
- **Phase 8 — Build, run, and end-to-end smoke test**

---

# Phase 1 — Project Scaffold + Theme

### Task 1: Android Studio project setup

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`

- [ ] **Step 1: Create a new Android Studio project**

  In Android Studio: **File → New → New Project → Empty Activity**
  - Name: `Ledger Noir`
  - Package name: `com.ledgernoir.tracker`
  - Save location: `<expense-tracker repo root>/android`
  - Language: Kotlin
  - Minimum SDK: API 26 (Android 8.0)
  - Build configuration language: Kotlin DSL

  Click **Finish**. Android Studio generates the scaffold and syncs Gradle.

- [ ] **Step 2: Replace `android/gradle/libs.versions.toml` with the version catalog**

```toml
[versions]
agp = "8.3.2"
kotlin = "1.9.23"
compose-bom = "2024.04.01"
compose-navigation = "2.7.7"
coroutines = "1.8.0"
retrofit = "2.9.0"
moshi = "1.15.1"
okhttp = "4.12.0"
datastore = "1.1.0"
lifecycle = "2.7.0"
activity-compose = "1.9.0"
junit = "4.13.2"
robolectric = "4.12.1"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-navigation = { group = "androidx.navigation", name = "navigation-compose", version.ref = "compose-navigation" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-moshi = { group = "com.squareup.retrofit2", name = "converter-moshi", version.ref = "retrofit" }
moshi-kotlin = { group = "com.squareup.moshi", name = "moshi-kotlin", version.ref = "moshi" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
```

- [ ] **Step 3: Replace `android/app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.ledgernoir.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ledgernoir.tracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.datastore.preferences)

    kapt(libs.moshi.kotlin)  // generates Moshi adapters at compile time

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
```

- [ ] **Step 4: Sync Gradle**

  In Android Studio: click **Sync Now** in the notification bar, or **File → Sync Project with Gradle Files**.
  Expected: BUILD SUCCESSFUL with no errors in the Build output pane.

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "chore: scaffold Android Studio project with Compose + Retrofit"
```

---

### Task 2: Color palette, typography, and theme

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/theme/Color.kt`
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/theme/Type.kt`
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/theme/Theme.kt`

The color values mirror the Ledger Noir web UI: dark canvas, teal accent, green for credit, rose for debit.

- [ ] **Step 1: Create `Color.kt`**

```kotlin
package com.ledgernoir.tracker.ui.theme

import androidx.compose.ui.graphics.Color

// Canvas
val CanvasBlack   = Color(0xFF0A0D12)
val SurfaceDark   = Color(0xFF111827)
val SurfaceMid    = Color(0xFF1F2937)
val BorderGray    = Color(0xFF374151)

// Accent (teal gradient)
val AccentTeal    = Color(0xFF2DD4BF)
val AccentGreen   = Color(0xFF34D399)

// Semantic
val CreditGreen   = Color(0xFF34D399)
val DebitRose     = Color(0xFFFB7185)

// Text
val TextPrimary   = Color(0xFFF9FAFB)
val TextSecondary = Color(0xFF9CA3AF)
val TextMuted     = Color(0xFF6B7280)
```

- [ ] **Step 2: Create `Type.kt`**

```kotlin
package com.ledgernoir.tracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tabular numbers for amounts — uses default monospace; swap for a downloaded font if desired
val TabularMono = FontFamily.Monospace

val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = TabularMono,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)
```

- [ ] **Step 3: Create `Theme.kt`**

```kotlin
package com.ledgernoir.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary          = AccentTeal,
    onPrimary        = CanvasBlack,
    primaryContainer = SurfaceMid,
    secondary        = AccentGreen,
    background       = CanvasBlack,
    surface          = SurfaceDark,
    surfaceVariant   = SurfaceMid,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline          = BorderGray,
    error            = DebitRose,
)

@Composable
fun LedgerNoirTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = AppTypography,
        content     = content,
    )
}
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/theme/
git commit -m "feat: add Ledger Noir color palette and Material3 theme"
```

---

# Phase 2 — SmsFilter (TDD)

### Task 3: SmsFilter — pure Kotlin, JUnit-testable

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/sms/SmsFilter.kt`
- Create: `android/app/src/test/java/com/ledgernoir/tracker/sms/SmsFilterTest.kt`

`SmsFilter` has zero Android dependencies — it is a pure Kotlin class so it can be tested with plain JUnit on the JVM (no emulator required). Its sole job: given a raw SMS originating address, decide whether it is from one of the three whitelisted banks.

- [ ] **Step 1: Write the failing test in `SmsFilterTest.kt`**

```kotlin
package com.ledgernoir.tracker.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsFilterTest {

    private val filter = SmsFilter()

    // --- normalizeSender ---

    @Test fun `bare AXISBK is normalized unchanged`() {
        assertEquals("AXISBK", filter.normalizeSender("AXISBK"))
    }

    @Test fun `AX-AXISBK operator prefix is stripped`() {
        assertEquals("AXISBK", filter.normalizeSender("AX-AXISBK"))
    }

    @Test fun `VM-IDFCFB is normalized to IDFCFB`() {
        assertEquals("IDFCFB", filter.normalizeSender("VM-IDFCFB"))
    }

    @Test fun `VK-BOBTXN is normalized to BOBTXN`() {
        assertEquals("BOBTXN", filter.normalizeSender("VK-BOBTXN"))
    }

    @Test fun `three-char prefix with dash is stripped`() {
        assertEquals("AXISBK", filter.normalizeSender("AD2-AXISBK"))
    }

    @Test fun `lowercase sender is uppercased`() {
        assertEquals("AXISBK", filter.normalizeSender("ax-axisbk"))
    }

    // --- isWhitelisted ---

    @Test fun `AX-AXISBK is whitelisted`() {
        assertTrue(filter.isWhitelisted("AX-AXISBK"))
    }

    @Test fun `VM-IDFCFB is whitelisted`() {
        assertTrue(filter.isWhitelisted("VM-IDFCFB"))
    }

    @Test fun `VK-BOBTXN is whitelisted (starts with BOB)`() {
        assertTrue(filter.isWhitelisted("VK-BOBTXN"))
    }

    @Test fun `bare AXISBK is whitelisted`() {
        assertTrue(filter.isWhitelisted("AXISBK"))
    }

    @Test fun `bare IDFCFB is whitelisted`() {
        assertTrue(filter.isWhitelisted("IDFCFB"))
    }

    @Test fun `HDFC is not whitelisted`() {
        assertFalse(filter.isWhitelisted("VD-HDFCBK"))
    }

    @Test fun `empty string is not whitelisted`() {
        assertFalse(filter.isWhitelisted(""))
    }

    @Test fun `unknown bank prefix is not whitelisted`() {
        assertFalse(filter.isWhitelisted("XX-RANDOM"))
    }

    @Test fun `numeric-only sender is not whitelisted`() {
        assertFalse(filter.isWhitelisted("1234567890"))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

  In Android Studio: right-click `SmsFilterTest.kt` → **Run 'SmsFilterTest'**.
  Expected: compilation error — `SmsFilter` does not exist yet.

- [ ] **Step 3: Create `SmsFilter.kt`**

```kotlin
package com.ledgernoir.tracker.sms

/**
 * Pure Kotlin — no Android imports. Safe to unit-test on the JVM.
 *
 * Bank sender IDs as received by Android (originating address field):
 *   Axis Bank:       AX-AXISBK  (operator prefix "AX-")
 *   IDFC First Bank: VM-IDFCFB  (operator prefix "VM-")
 *   Bank of Baroda:  VK-BOBTXN  (operator prefix "VK-"; body ID starts with "BOB")
 *
 * Operator prefixes are 2–3 characters followed by a hyphen. They vary by telecom
 * operator and circle but the alphanumeric tail (after the hyphen) is stable.
 */
class SmsFilter {

    private val whitelist = setOf("AXISBK", "IDFCFB", "BOBTXN", "BOB")

    /**
     * Strips the operator prefix (e.g. "AX-", "VM-", "AD2-") and uppercases.
     * If there is no hyphen the raw value is returned uppercased.
     */
    fun normalizeSender(raw: String): String {
        val upper = raw.trim().uppercase()
        val hyphen = upper.indexOf('-')
        return if (hyphen != -1) upper.substring(hyphen + 1) else upper
    }

    /**
     * Returns true when the normalized sender matches one of the three
     * whitelisted bank IDs or starts with "BOB" (Bank of Baroda variants).
     */
    fun isWhitelisted(sender: String): Boolean {
        if (sender.isBlank()) return false
        val normalized = normalizeSender(sender)
        return whitelist.any { id -> normalized == id || normalized.startsWith(id) }
    }
}
```

- [ ] **Step 4: Run the tests and verify they pass**

  In Android Studio: right-click `SmsFilterTest.kt` → **Run 'SmsFilterTest'**.
  Expected: all 14 tests PASS (green bar).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/sms/SmsFilter.kt
git add android/app/src/test/java/com/ledgernoir/tracker/sms/SmsFilterTest.kt
git commit -m "feat: add SmsFilter with whitelist for Axis/IDFC/BOB — fully unit tested"
```

---

# Phase 3 — Data Layer

### Task 4: TokenStore (DataStore)

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/data/TokenStore.kt`

`TokenStore` persists two values to DataStore Preferences: the backend base URL (default `http://10.0.2.2:8000`) and the bearer token (empty string = not locked). It exposes `Flow`s and suspend setters — callers never touch DataStore directly.

- [ ] **Step 1: Create `TokenStore.kt`**

```kotlin
package com.ledgernoir.tracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ledger_prefs")

class TokenStore(private val context: Context) {

    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_TOKEN    = stringPreferencesKey("bearer_token")
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
    }

    val baseUrl: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL }

    val token: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_TOKEN] ?: "" }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_BASE_URL] = url.trimEnd('/') }
    }

    suspend fun setToken(token: String) {
        context.dataStore.edit { it[KEY_TOKEN] = token }
    }

    suspend fun clearToken() {
        context.dataStore.edit { it.remove(KEY_TOKEN) }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/data/TokenStore.kt
git commit -m "feat: add TokenStore with DataStore for base URL and bearer token"
```

---

### Task 5: AuthInterceptor (OkHttp)

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/data/AuthInterceptor.kt`

The interceptor reads the current token synchronously (DataStore `runBlocking`) and adds `Authorization: Bearer <token>` when the token is non-empty. It is registered on the OkHttp client used by Retrofit.

- [ ] **Step 1: Create `AuthInterceptor.kt`**

```kotlin
package com.ledgernoir.tracker.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the bearer token to every outgoing request when a token is present.
 * Uses runBlocking because OkHttp interceptors are synchronous; the DataStore
 * read is fast (in-memory after first load).
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenStore.token.first() }
        val request = if (token.isNotBlank()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/data/AuthInterceptor.kt
git commit -m "feat: add OkHttp AuthInterceptor attaching bearer token"
```

---

### Task 6: ExpenseApi — Retrofit interface and data classes

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/data/ExpenseApi.kt`

All JSON field names use snake_case to match FastAPI's default serialization. Moshi's `@Json` annotation overrides Kotlin property names where needed.

- [ ] **Step 1: Create `ExpenseApi.kt`**

```kotlin
package com.ledgernoir.tracker.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// ── Request bodies ────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class SmsPayload(
    val sender: String,
    val body: String,
    @Json(name = "received_at") val receivedAt: String,   // ISO 8601
    @Json(name = "external_id") val externalId: String,
)

@JsonClass(generateAdapter = true)
data class PinPayload(val pin: String)

@JsonClass(generateAdapter = true)
data class ManualTxnPayload(
    val amount: Double,
    val direction: String,
    val merchant: String,
    @Json(name = "occurred_at") val occurredAt: String,   // ISO 8601
    @Json(name = "account_id") val accountId: Int? = null,
    @Json(name = "category_id") val categoryId: Int? = null,
    val note: String? = null,
)

@JsonClass(generateAdapter = true)
data class IncomePayload(
    val month: String,  // "YYYY-MM"
    val amount: Double,
)

@JsonClass(generateAdapter = true)
data class AssignPayload(
    @Json(name = "category_id") val categoryId: Int,
    val month: String,
    @Json(name = "assigned_amount") val assignedAmount: Double,
)

// ── Response bodies ───────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TokenResponse(val token: String)

@JsonClass(generateAdapter = true)
data class TransactionDto(
    val id: Int,
    val amount: Double,
    val direction: String,
    val merchant: String,
    @Json(name = "occurred_at") val occurredAt: String,
    @Json(name = "account_id") val accountId: Int,
    @Json(name = "category_id") val categoryId: Int?,
    val note: String? = null,
    @Json(name = "entry_type") val entryType: String? = null,
)

@JsonClass(generateAdapter = true)
data class AccountDto(
    val id: Int,
    val bank: String,
    val type: String,
    val last4: String,
    @Json(name = "current_balance") val currentBalance: Double?,
)

@JsonClass(generateAdapter = true)
data class SummaryItem(
    val category: String,
    val total: Double,
)

@JsonClass(generateAdapter = true)
data class NetWorthResponse(
    @Json(name = "net_worth") val netWorth: Double,
)

@JsonClass(generateAdapter = true)
data class BudgetCategoryStatus(
    val name: String,
    val assigned: Double,
    val spent: Double,
    val available: Double,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class BudgetResponse(
    val month: String,
    @Json(name = "ready_to_assign") val readyToAssign: Double,
    val categories: List<BudgetCategoryStatus>,
)

// ── API interface ─────────────────────────────────────────────────────────────

interface ExpenseApi {

    @POST("auth/unlock")
    suspend fun unlock(@Body payload: PinPayload): TokenResponse

    @POST("ingest/sms")
    suspend fun ingestSms(@Body payload: SmsPayload)

    @GET("transactions")
    suspend fun listTransactions(): List<TransactionDto>

    @GET("transactions/search")
    suspend fun searchTransactions(
        @Query("q")           q: String?   = null,
        @Query("account_id")  accountId: Int? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("start")       start: String? = null,
        @Query("end")         end: String?   = null,
        @Query("min_amount")  minAmount: Double? = null,
        @Query("max_amount")  maxAmount: Double? = null,
    ): List<TransactionDto>

    @POST("transactions/manual")
    suspend fun createManual(@Body payload: ManualTxnPayload): TransactionDto

    @GET("reports/summary")
    suspend fun summary(): List<SummaryItem>

    @GET("budget/{month}")
    suspend fun budget(@Path("month") month: String): BudgetResponse

    @PUT("budget/income")
    suspend fun setIncome(@Body payload: IncomePayload)

    @PUT("budget/assign")
    suspend fun assign(@Body payload: AssignPayload)

    @GET("accounts")
    suspend fun accounts(): List<AccountDto>

    @GET("networth")
    suspend fun netWorth(): NetWorthResponse
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/data/ExpenseApi.kt
git commit -m "feat: add Retrofit ExpenseApi interface and Moshi data classes"
```

---

### Task 7: ExpenseRepository + Retrofit factory

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/data/ExpenseRepository.kt`

The repository owns the Retrofit instance, rebuilds it when the base URL changes, and exposes suspend functions that the ViewModels call. It is the single place where API error handling lives.

- [ ] **Step 1: Create `ExpenseRepository.kt`**

```kotlin
package com.ledgernoir.tracker.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ExpenseRepository(private val tokenStore: TokenStore) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private fun buildClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(logging)
            .build()
    }

    private suspend fun api(): ExpenseApi {
        val baseUrl = tokenStore.baseUrl.first().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ExpenseApi::class.java)
    }

    /** Returns the bearer token string or null if unlock is needed. */
    suspend fun unlock(pin: String): Result<String> = runCatching {
        val response = api().unlock(PinPayload(pin))
        tokenStore.setToken(response.token)
        response.token
    }

    suspend fun listTransactions(): Result<List<TransactionDto>> =
        runCatching { api().listTransactions() }

    suspend fun searchTransactions(
        q: String? = null,
        accountId: Int? = null,
        categoryId: Int? = null,
        start: String? = null,
        end: String? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
    ): Result<List<TransactionDto>> = runCatching {
        api().searchTransactions(q, accountId, categoryId, start, end, minAmount, maxAmount)
    }

    suspend fun createManual(payload: ManualTxnPayload): Result<TransactionDto> =
        runCatching { api().createManual(payload) }

    suspend fun summary(): Result<List<SummaryItem>> =
        runCatching { api().summary() }

    suspend fun budget(month: String): Result<BudgetResponse> =
        runCatching { api().budget(month) }

    suspend fun setIncome(month: String, amount: Double): Result<Unit> =
        runCatching { api().setIncome(IncomePayload(month, amount)) }

    suspend fun assign(categoryId: Int, month: String, assignedAmount: Double): Result<Unit> =
        runCatching { api().assign(AssignPayload(categoryId, month, assignedAmount)) }

    suspend fun accounts(): Result<List<AccountDto>> =
        runCatching { api().accounts() }

    suspend fun netWorth(): Result<Double> =
        runCatching { api().netWorth().netWorth }

    suspend fun ingestSms(payload: SmsPayload): Result<Unit> =
        runCatching { api().ingestSms(payload) }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/data/ExpenseRepository.kt
git commit -m "feat: add ExpenseRepository with Retrofit factory and suspend wrappers"
```

---

### Task 8: Application class and dependency wiring

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/LedgerNoirApp.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

The `Application` subclass holds the singleton `TokenStore` and `ExpenseRepository` so every screen and the `SmsReceiver` can access them without a DI framework.

- [ ] **Step 1: Create `LedgerNoirApp.kt`**

```kotlin
package com.ledgernoir.tracker

import android.app.Application
import com.ledgernoir.tracker.data.ExpenseRepository
import com.ledgernoir.tracker.data.TokenStore

class LedgerNoirApp : Application() {

    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val repository: ExpenseRepository by lazy { ExpenseRepository(tokenStore) }
}
```

- [ ] **Step 2: Register the Application class in `AndroidManifest.xml`**

  In the existing `<application>` tag, add `android:name=".LedgerNoirApp"`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.READ_SMS" />

    <application
        android:name=".LedgerNoirApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.LedgerNoir"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".sms.SmsReceiver"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS">
            <intent-filter android:priority="999">
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

> **Note on `android:usesCleartextTraffic="true"`:** This allows HTTP (not HTTPS) traffic to `10.0.2.2:8000` during development. In production replace with an HTTPS backend and remove this flag (or use a `network_security_config.xml` to restrict it to the emulator IP only).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/LedgerNoirApp.kt
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat: add Application class, permissions, and SMS receiver manifest entry"
```

---

# Phase 4 — SmsReceiver

### Task 9: SmsReceiver — filter and forward

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/sms/SmsReceiver.kt`

The receiver: (1) is called by Android when any SMS arrives, (2) delegates to `SmsFilter.isWhitelisted`, (3) builds the `external_id` as `"sms-<timestampMillis>-<bodyHashHex>"` (idempotent on the server), (4) POSTs to `/ingest/sms` on a coroutine via `goAsync()`.

- [ ] **Step 1: Create `SmsReceiver.kt`**

```kotlin
package com.ledgernoir.tracker.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ledgernoir.tracker.LedgerNoirApp
import com.ledgernoir.tracker.data.SmsPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Receives all incoming SMS broadcasts and forwards bank messages to the backend.
 *
 * ── Reliability note ──────────────────────────────────────────────────────────
 * On Android 8+ (API 26) static BroadcastReceivers for implicit broadcasts were
 * restricted. SMS_RECEIVED is an EXEMPT implicit broadcast (listed in the
 * system exemption list) so it still reaches statically-declared receivers even
 * when the app is not running — no foreground service or WorkManager needed.
 *
 * However, some OEM launchers (Xiaomi, Oppo, Vivo) will kill background
 * processes aggressively. If SMS forwarding misses messages on such devices,
 * the fallback is to use a persistent foreground notification service. The
 * current implementation handles the common case (stock AOSP / emulator).
 * ──────────────────────────────────────────────────────────────────────────────
 */
class SmsReceiver : BroadcastReceiver() {

    private val filter = SmsFilter()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        .withZone(ZoneOffset.UTC)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group PDUs by originating address into full message bodies
        val grouped = messages.groupBy { it.originatingAddress ?: "" }

        grouped.forEach { (sender, pdus) ->
            if (!filter.isWhitelisted(sender)) return@forEach

            val body        = pdus.joinToString(separator = "") { it.messageBody }
            val timestampMs = pdus.first().timestampMillis
            val externalId  = "sms-$timestampMs-${sha256Short(body)}"
            val receivedAt  = isoFormatter.format(Instant.ofEpochMilli(timestampMs))

            val app = context.applicationContext as LedgerNoirApp
            val pendingResult = goAsync()

            scope.launch {
                try {
                    app.repository.ingestSms(
                        SmsPayload(
                            sender     = sender,
                            body       = body,
                            receivedAt = receivedAt,
                            externalId = externalId,
                        )
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    /** First 8 hex chars of SHA-256 — enough for idempotency, not sensitive. */
    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/sms/SmsReceiver.kt
git commit -m "feat: add SmsReceiver forwarding whitelisted bank SMS to /ingest/sms"
```

---

# Phase 5 — Runtime Permission Flow

### Task 10: Request RECEIVE_SMS and READ_SMS at runtime

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/MainActivity.kt`

Android requires dangerous permissions to be granted at runtime even when declared in the manifest. `RECEIVE_SMS` and `READ_SMS` are both dangerous permissions (protection level `dangerous`).

- [ ] **Step 1: Replace `MainActivity.kt` with the permission-requesting version**

```kotlin
package com.ledgernoir.tracker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ledgernoir.tracker.ui.AppShell
import com.ledgernoir.tracker.ui.theme.LedgerNoirTheme

class MainActivity : ComponentActivity() {

    private var smsPermissionGranted by mutableStateOf(false)

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        smsPermissionGranted = results.getOrDefault(Manifest.permission.RECEIVE_SMS, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        smsPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
            )
        )

        setContent {
            LedgerNoirTheme {
                AppShell(
                    app = application as LedgerNoirApp,
                    smsPermissionGranted = smsPermissionGranted,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/MainActivity.kt
git commit -m "feat: request RECEIVE_SMS and READ_SMS permissions at runtime"
```

---

# Phase 6 — Compose Screens

### Task 11: Navigation shell (AppShell + NavHost)

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/AppShell.kt`

All five screens share a `NavHost` inside a `Scaffold` with a bottom navigation bar.

- [ ] **Step 1: Create `AppShell.kt`**

```kotlin
package com.ledgernoir.tracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ledgernoir.tracker.LedgerNoirApp

sealed class Screen(val route: String, val label: String) {
    object Lock         : Screen("lock",         "Lock")
    object Dashboard    : Screen("dashboard",    "Dashboard")
    object Transactions : Screen("transactions", "Transactions")
    object Budget       : Screen("budget",       "Budget")
    object Settings     : Screen("settings",     "Settings")
}

@Composable
fun AppShell(app: LedgerNoirApp, smsPermissionGranted: Boolean) {
    val navController = rememberNavController()
    val token by app.tokenStore.token.collectAsState(initial = "")

    val startDestination = if (token.isBlank()) Screen.Lock.route else Screen.Dashboard.route

    val bottomNavItems = listOf(
        Screen.Dashboard    to Icons.Default.Home,
        Screen.Transactions to Icons.Default.List,
        Screen.Budget       to Icons.Default.PieChart,
        Screen.Settings     to Icons.Default.Settings,
    )

    Scaffold(
        bottomBar = {
            val navBackStack by navController.currentBackStackEntryAsState()
            val currentDest = navBackStack?.destination
            if (currentDest?.route != Screen.Lock.route) {
                NavigationBar {
                    bottomNavItems.forEach { (screen, icon) ->
                        NavigationBarItem(
                            icon     = { Icon(icon, contentDescription = screen.label) },
                            label    = { Text(screen.label) },
                            selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                            onClick  = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = startDestination,
            modifier         = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Lock.route) {
                LockScreen(
                    repository = app.repository,
                    tokenStore = app.tokenStore,
                    onUnlocked = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Lock.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    repository          = app.repository,
                    smsPermissionGranted = smsPermissionGranted,
                )
            }
            composable(Screen.Transactions.route) {
                TransactionsScreen(repository = app.repository)
            }
            composable(Screen.Budget.route) {
                BudgetScreen(repository = app.repository)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(tokenStore = app.tokenStore)
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/AppShell.kt
git commit -m "feat: add AppShell with NavHost and bottom navigation bar"
```

---

### Task 12: Lock screen

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/LockScreen.kt`

The Lock screen is shown when the app has a PIN set and no valid token. The user enters the PIN; on success the token is stored and the caller navigates away.

- [ ] **Step 1: Create `LockScreen.kt`**

```kotlin
package com.ledgernoir.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.data.ExpenseRepository
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    repository: ExpenseRepository,
    tokenStore: TokenStore,
    onUnlocked: () -> Unit,
) {
    var pin     by remember { mutableStateOf("") }
    var error   by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope   = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
        ) {
            Text(
                text  = "Ledger Noir",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text  = "Enter PIN to unlock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value         = pin,
                onValueChange = { pin = it; error = null },
                label         = { Text("PIN") },
                singleLine    = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError       = error != null,
                modifier      = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Text(text = error!!, color = DebitRose, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                enabled  = pin.isNotBlank() && !loading,
                onClick  = {
                    loading = true
                    scope.launch {
                        repository.unlock(pin).fold(
                            onSuccess = { onUnlocked() },
                            onFailure = { error = "Invalid PIN"; loading = false },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "Unlocking…" else "Unlock")
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/LockScreen.kt
git commit -m "feat: add Lock screen with PIN entry and /auth/unlock integration"
```

---

### Task 13: Dashboard screen

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/DashboardScreen.kt`

Shows two hero figures matching the web: **Spent this month** (sum of debits in current calendar month from the summary endpoint) and **Net Worth**. A banner warns when SMS permission is missing.

- [ ] **Step 1: Create `DashboardScreen.kt`**

```kotlin
package com.ledgernoir.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.data.ExpenseRepository
import com.ledgernoir.tracker.data.SummaryItem
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
) {
    var summary  by remember { mutableStateOf<List<SummaryItem>>(emptyList()) }
    var netWorth by remember { mutableStateOf<Double?>(null) }
    var loading  by remember { mutableStateOf(true) }
    var error    by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        repository.summary().onSuccess { summary = it }.onFailure { error = it.message }
        repository.netWorth().onSuccess { netWorth = it }.onFailure { /* non-fatal */ }
        loading = false
    }

    // Compute spent-this-month from category summary totals (all are debit totals)
    val spentThisMonth = summary.sumOf { it.total }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text  = "Dashboard",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (!smsPermissionGranted) {
            SmsBanner()
        }

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
            return@Column
        }

        error?.let {
            Text(text = "Error: $it", color = DebitRose, style = MaterialTheme.typography.bodyMedium)
        }

        // Hero card — Spent this month
        HeroCard(
            label  = "Spent this month",
            amount = spentThisMonth,
            color  = DebitRose,
        )

        // Net worth card
        netWorth?.let {
            HeroCard(
                label  = "Net Worth",
                amount = it,
                color  = AccentTeal,
            )
        }

        // Per-category breakdown
        if (summary.isNotEmpty()) {
            Text(
                text  = "By category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            summary.forEach { item ->
                CategoryRow(item)
            }
        }
    }
}

@Composable
private fun HeroCard(label: String, amount: Double, color: androidx.compose.ui.graphics.Color) {
    Card(
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Text(
                text  = "₹ %,.2f".format(amount),
                style = MaterialTheme.typography.headlineLarge,
                color = color,
            )
        }
    }
}

@Composable
private fun CategoryRow(item: SummaryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMid, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = item.category.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text  = "₹ %,.2f".format(item.total),
            style = MaterialTheme.typography.bodyMedium,
            color = DebitRose,
        )
    }
}

@Composable
private fun SmsBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMid, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "SMS permission not granted — bank messages won't be forwarded automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = DebitRose,
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/DashboardScreen.kt
git commit -m "feat: add Dashboard screen with spent-this-month and net worth hero cards"
```

---

### Task 14: Transactions screen + manual-add bottom sheet

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/TransactionsScreen.kt`

Lists transactions newest-first. A floating action button opens a `ModalBottomSheet` for manual entry.

- [ ] **Step 1: Create `TransactionsScreen.kt`**

```kotlin
package com.ledgernoir.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.data.ExpenseRepository
import com.ledgernoir.tracker.data.ManualTxnPayload
import com.ledgernoir.tracker.data.TransactionDto
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(repository: ExpenseRepository) {
    var transactions by remember { mutableStateOf<List<TransactionDto>>(emptyList()) }
    var loading      by remember { mutableStateOf(true) }
    var showSheet    by remember { mutableStateOf(false) }
    val sheetState   = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope        = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            repository.listTransactions().onSuccess {
                transactions = it.sortedByDescending { t -> t.occurredAt }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick          = { showSheet = true },
                containerColor   = AccentTeal,
                contentColor     = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add transaction")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Text(
                text  = "Transactions",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(transactions) { txn ->
                        TransactionRow(txn)
                    }
                }
            }
        }

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState       = sheetState,
                containerColor   = SurfaceDark,
            ) {
                ManualAddSheet(
                    repository = repository,
                    onAdded    = {
                        scope.launch { sheetState.hide() }
                        showSheet = false
                        reload()
                    }
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(txn: TransactionDto) {
    val amountColor = if (txn.direction == "credit") CreditGreen else DebitRose
    val sign        = if (txn.direction == "credit") "+" else "−"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMid, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = txn.merchant,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = txn.occurredAt.take(10),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text  = "$sign ₹ %,.2f".format(txn.amount),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = amountColor,
        )
    }
}

@Composable
private fun ManualAddSheet(repository: ExpenseRepository, onAdded: () -> Unit) {
    var merchant  by remember { mutableStateOf("") }
    var amount    by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("debit") }
    var note      by remember { mutableStateOf("") }
    var saving    by remember { mutableStateOf(false) }
    var error     by remember { mutableStateOf<String?>(null) }
    val scope     = rememberCoroutineScope()

    val isoNow: String
        get() = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text  = "Add Transaction",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value         = merchant,
            onValueChange = { merchant = it },
            label         = { Text("Merchant") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = amount,
            onValueChange = { amount = it },
            label         = { Text("Amount (₹)") },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier      = Modifier.fillMaxWidth(),
        )
        // Direction toggle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("debit", "credit").forEach { d ->
                val selected = direction == d
                Button(
                    onClick = { direction = d },
                    colors  = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (selected) AccentTeal else SurfaceMid,
                    ),
                ) {
                    Text(d.replaceFirstChar { it.uppercase() })
                }
            }
        }
        OutlinedTextField(
            value         = note,
            onValueChange = { note = it },
            label         = { Text("Note (optional)") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(text = it, color = DebitRose, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            enabled  = merchant.isNotBlank() && amount.isNotBlank() && !saving,
            onClick  = {
                val amtDouble = amount.toDoubleOrNull()
                if (amtDouble == null) { error = "Invalid amount"; return@Button }
                saving = true
                scope.launch {
                    repository.createManual(
                        ManualTxnPayload(
                            amount     = amtDouble,
                            direction  = direction,
                            merchant   = merchant,
                            occurredAt = isoNow,
                            note       = note.ifBlank { null },
                        )
                    ).fold(
                        onSuccess = { onAdded() },
                        onFailure = { error = it.message; saving = false },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "Saving…" else "Save")
        }
        Spacer(Modifier.height(8.dp))
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/TransactionsScreen.kt
git commit -m "feat: add Transactions screen with list and manual-add bottom sheet"
```

---

### Task 15: Budget screen

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/BudgetScreen.kt`

Shows the zero-based budget for the current month: **Ready to Assign** hero, then per-category rows with assigned / spent / available and a color-coded status pill.

- [ ] **Step 1: Create `BudgetScreen.kt`**

```kotlin
package com.ledgernoir.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.data.BudgetCategoryStatus
import com.ledgernoir.tracker.data.BudgetResponse
import com.ledgernoir.tracker.data.ExpenseRepository
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.LocalDate

@Composable
fun BudgetScreen(repository: ExpenseRepository) {
    val currentMonth = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))

    var budget  by remember { mutableStateOf<BudgetResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentMonth) {
        loading = true
        repository.budget(currentMonth)
            .onSuccess { budget = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text  = "Budget · $currentMonth",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
            return@Column
        }

        error?.let {
            Text(text = "Error: $it", color = DebitRose, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        budget?.let { b ->
            // Hero — Ready to Assign
            Card(
                shape  = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Ready to Assign", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = "₹ %,.2f".format(b.readyToAssign),
                        style = MaterialTheme.typography.headlineLarge,
                        color = AccentGreen,
                    )
                }
            }

            // Per-category rows
            if (b.categories.isNotEmpty()) {
                Text(
                    text  = "Categories",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                b.categories.forEach { cat -> BudgetCategoryRow(cat) }
            }
        }
    }
}

@Composable
private fun BudgetCategoryRow(cat: BudgetCategoryStatus) {
    val statusColor = when (cat.status) {
        "exceeded" -> DebitRose
        "warning"  -> Color(0xFFFBBF24)   // amber
        else       -> AccentGreen
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMid, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = cat.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "Spent ₹%,.2f of ₹%,.2f".format(cat.spent, cat.assigned),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text  = "₹ %,.2f".format(cat.available),
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape)
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/BudgetScreen.kt
git commit -m "feat: add Budget screen with ready-to-assign hero and per-category rows"
```

---

# Phase 7 — Settings Screen

### Task 16: Settings screen (base URL + PIN note)

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/SettingsScreen.kt`

Lets the user change the base URL and manually clear the stored token (force re-lock). PIN management happens on the server; the app only stores the token it receives from `/auth/unlock`.

- [ ] **Step 1: Create `SettingsScreen.kt`**

```kotlin
package com.ledgernoir.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(tokenStore: TokenStore) {
    val savedUrl by tokenStore.baseUrl.collectAsState(initial = TokenStore.DEFAULT_BASE_URL)
    var draftUrl by remember(savedUrl) { mutableStateOf(savedUrl) }
    var saved    by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text  = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Base URL
        Column {
            Text(
                text  = "Backend URL",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value         = draftUrl,
                onValueChange = { draftUrl = it; saved = false },
                singleLine    = true,
                placeholder   = { Text(TokenStore.DEFAULT_BASE_URL) },
                modifier      = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = "Emulator default: http://10.0.2.2:8000",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        Button(
            onClick = {
                scope.launch {
                    tokenStore.setBaseUrl(draftUrl)
                    saved = true
                }
            },
            enabled  = draftUrl != savedUrl || !saved,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save URL")
        }

        Spacer(Modifier.height(8.dp))

        // Lock / clear token
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = "App Lock",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "PIN is set on the backend (ET_APP_PIN env var). The app stores the session token received after a successful unlock. Clear the token to force re-authentication on next launch.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Button(
                onClick = { scope.launch { tokenStore.clearToken() } },
                colors  = ButtonDefaults.buttonColors(containerColor = DebitRose),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Lock Now (clear token)")
            }
        }

        // SMS permission note
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = "SMS Forwarding",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "The app silently forwards SMS from AXISBK, IDFCFB, and BOBTXN senders to the backend. No parsing happens on-device — the raw text is sent as-is. If permission is missing, restart the app and grant it when prompted.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                text  = "Whitelisted sender IDs: AXISBK · IDFCFB · BOBTXN",
                style = MaterialTheme.typography.labelSmall,
                color = AccentTeal,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/SettingsScreen.kt
git commit -m "feat: add Settings screen with base URL editor and lock/clear token"
```

---

# Phase 8 — Build, Run, and End-to-End Smoke Test

### Task 17: Build and run on emulator

- [ ] **Step 1: Create or open an AVD**

  In Android Studio: **Tools → Device Manager → Create Device**
  - Hardware profile: **Pixel 6** (or any phone)
  - System image: **API 34 (UpsideDownCake), x86_64**
  - Click **Finish**. Start the emulator.

- [ ] **Step 2: Start the FastAPI backend on your laptop**

  In a terminal at the `expense-tracker/` root:
  ```bash
  cd backend && python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
  ```
  Verify it is reachable from the emulator: open the emulator browser and navigate to `http://10.0.2.2:8000/accounts`. You should see `[]` or a JSON array.

- [ ] **Step 3: Run the app**

  In Android Studio: click the **Run** button (▶) with the emulator selected.
  - Expected: the app launches. If a PIN is set (`ET_APP_PIN` env var on the server), the Lock screen appears. Otherwise, the Dashboard screen appears.

- [ ] **Step 4: Grant SMS permissions**

  On first launch Android will show a permission dialog for `RECEIVE_SMS` and `READ_SMS`. Tap **Allow**.
  The Dashboard should no longer show the SMS warning banner.

- [ ] **Step 5: Send a test SMS via emulator Extended Controls**

  In Android Studio emulator: click the **...** (Extended Controls) button → **Phone** tab → **Send Message**.
  - **From:** `AX-AXISBK`
  - **Message:**
    ```
    INR 299 spent on AXIS BANK Credit Card no. XX1234 at NETFLIX on 02-06-2026. Avl Lmt INR 49701
    ```
  - Click **Send Message**.

- [ ] **Step 6: Verify end-to-end**

  a. Check the backend log — you should see a `POST /ingest/sms 201` or `202` line.

  b. In the Android app: navigate to **Transactions**. Pull to refresh (or wait for the next `LaunchedEffect`). The NETFLIX transaction should appear.

  c. Open the web dashboard at `http://localhost:5173` (or wherever the React app runs) and confirm the same transaction appears there.

- [ ] **Step 7: Test the lock screen**

  a. Set `ET_APP_PIN=1234` in `backend/.env` and restart uvicorn.

  b. In the Android app Settings screen: tap **Lock Now (clear token)**.

  c. Kill and relaunch the app. The Lock screen should appear.

  d. Enter PIN `1234` → tap **Unlock**. The Dashboard should appear.

- [ ] **Step 8: Run unit tests**

  In Android Studio: right-click `SmsFilterTest.kt` → **Run 'SmsFilterTest'**.
  Expected: 14 tests PASS with no emulator required.

  Alternatively, from the terminal (requires a local JDK, not the full Android SDK):
  ```bash
  cd android && ./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.sms.SmsFilterTest"
  ```
  Expected:
  ```
  BUILD SUCCESSFUL
  14 tests completed, 0 failed
  ```

- [ ] **Step 9: Test a non-whitelisted SMS (negative case)**

  Send another Extended Controls SMS from sender `VD-HDFCBK` with any body.
  Expected: **no** new transaction appears in the app or backend — the `SmsReceiver` drops it silently because `SmsFilter.isWhitelisted("VD-HDFCBK")` returns `false`.

- [ ] **Step 10: Commit**

```bash
git add android/
git commit -m "feat: Android companion app complete — all screens + SMS forwarding verified"
```

---

## Known Limitations and Production Considerations

| Concern | Current state | Production path |
|---|---|---|
| Cleartext HTTP | `android:usesCleartextTraffic="true"` allows HTTP to `10.0.2.2` | Deploy backend behind TLS; remove flag or restrict to dev host via `network_security_config.xml` |
| Token storage | DataStore Preferences (plaintext on device) | Migrate to Android Keystore-backed EncryptedSharedPreferences for the token key |
| SMS reliability on OEM ROMs | Works on stock AOSP / emulator; Xiaomi/Oppo/Vivo may kill the receiver | Add a persistent foreground notification service as fallback; instruct users to whitelist the app in battery settings |
| No offline queue | If the backend is unreachable when SMS arrives, it is silently dropped | Add a Room-backed outbox with WorkManager retry |
| Retrofit built per-call | `api()` in `ExpenseRepository` calls `tokenStore.baseUrl.first()` on every request | Cache the Retrofit instance; invalidate when the base URL changes in Settings |
| `runBlocking` in AuthInterceptor | Acceptable since DataStore reads are in-memory after first load; avoid if you add slow I/O in `TokenStore` | Switch to a synchronous credential provider pattern if needed |
