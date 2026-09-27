# Flip 7 Redesign — Phase 1: Adaptive Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the foldable-first foundation (form-factor detection, design tokens, adaptive navigation, no-restart-on-fold, edge-to-edge insets, shared CRED-grade components) and prove it end-to-end by converting the Dashboard to separate Main/Cover layouts on the Galaxy Z Flip 7.

**Architecture:** A root `BoxWithConstraints` derives a `FormFactor` (Cover / Phone / Wide) provided via `LocalFormFactor`; each screen dispatches to a `…Main()` or `…Cover()` tree over shared, hoisted state. New shared components (`HeroNumber`, `MerchantLogo`, `CreditCardArt`, `CardCarousel`, `AllocationRing`) compose with the existing, kept card system (`CredCard`, `MoneyText`). The Activity enables edge-to-edge and never recreates on fold (`configChanges`).

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.04.01, compiler ext 1.5.11), Material3, `androidx.compose.foundation` Pager (transitive via material3), `androidx.activity` edge-to-edge (1.9.0), JUnit + Robolectric (JVM unit tests).

**Conventions for every task:**
- Build/test from `android/`. Set JDK first:
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH:$HOME/Library/Android/sdk/platform-tools"
  ```
- Compile gate for composables (no UI test harness installed): `./gradlew :app:assembleDebug -q` → expect `BUILD SUCCESSFUL` (printed by a trailing `&& echo BUILD_OK`).
- Compose rule (project CLAUDE / past bug): **never early-return inside a composable** — use if/else blocks (an early `return@Column` caused `IndexOutOfBoundsException` in `Stack.pop`).
- Keep the existing card visual language; do not restyle `CredCard`/`MoneyText`.

---

## File Structure (Phase 1)

**New files (all under `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/`):**
- `FormFactor.kt` — `FormFactor` enum, pure `formFactorFor(w,h)`, `LocalFormFactor`, `ProvideFormFactor`.
- `Tokens.kt` — `Space` spacing constants + form-factor type-size helpers.
- `HeroNumber.kt` — label + big `MoneyText` + optional colored change line.
- `MerchantLogo.kt` — colored brand chip (letter/emoji) with category fallback.
- `CreditCardArt.kt` — gradient credit-card face (bank, network, last4, utilization/due/points overlays).
- `CardCarousel.kt` — `HorizontalPager` with peeking card + page dots.
- `AllocationRing.kt` — donut ring from slices with a center slot.

**New test:**
- `app/src/test/java/com/ledgernoir/tracker/ui/designsystem/FormFactorTest.kt`

**Modified files:**
- `app/src/main/AndroidManifest.xml` — add `configChanges` + `resizeableActivity` to `.MainActivity`.
- `app/src/main/java/com/ledgernoir/tracker/MainActivity.kt` — `enableEdgeToEdge()`.
- `app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt` — wrap in `ProvideFormFactor`, `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`, icon-only nav labels on Cover.
- `app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt` — split into `DashboardScreen` (dispatcher) + `DashboardMain` + `DashboardCover`.

---

## Task 1: FormFactor type + pure breakpoint function (TDD)

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/FormFactor.kt`
- Test: `app/src/test/java/com/ledgernoir/tracker/ui/designsystem/FormFactorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ledgernoir/tracker/ui/designsystem/FormFactorTest.kt`:
```kotlin
package com.ledgernoir.tracker.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class FormFactorTest {
    @Test fun coverWhenShort() {
        // Flip 7 cover ≈ 361x390dp
        assertEquals(FormFactor.Cover, formFactorFor(widthDp = 361, heightDp = 390))
    }

    @Test fun phoneWhenTall() {
        // Flip 7 main ≈ 407x950dp
        assertEquals(FormFactor.Phone, formFactorFor(widthDp = 407, heightDp = 950))
    }

    @Test fun wideWhenBroad() {
        assertEquals(FormFactor.Wide, formFactorFor(widthDp = 720, heightDp = 1000))
    }

    @Test fun wideBeatsCoverAtLargeWidth() {
        // width rule takes precedence over the short-height rule
        assertEquals(FormFactor.Wide, formFactorFor(widthDp = 640, heightDp = 400))
    }

    @Test fun boundaryHeightIsPhone() {
        // exactly 480dp tall is not "short" → Phone
        assertEquals(FormFactor.Phone, formFactorFor(widthDp = 400, heightDp = 480))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.ui.designsystem.FormFactorTest" 2>&1 | tail -20
```
Expected: FAIL — `Unresolved reference: FormFactor` / `formFactorFor`.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/FormFactor.kt`:
```kotlin
package com.ledgernoir.tracker.ui.designsystem

/**
 * Which physical surface we're rendering on. Galaxy Z Flip 7 has two:
 *  - Cover  → 4.1" near-square (≈361x390dp): short, compact layouts.
 *  - Phone  → 6.9" main (≈407x950dp): the tall single-column feed.
 *  - Wide   → ≥600dp width (large/landscape/foldable-open tablets): safety fallback,
 *             renders the Phone tree inside a width-capped container.
 */
enum class FormFactor { Cover, Phone, Wide }

/** Pure breakpoint logic — unit tested. Width rule wins over the short-height rule. */
fun formFactorFor(widthDp: Int, heightDp: Int): FormFactor = when {
    widthDp >= 600 -> FormFactor.Wide
    heightDp < 480 -> FormFactor.Cover
    else -> FormFactor.Phone
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.ui.designsystem.FormFactorTest" 2>&1 | tail -12
```
Expected: PASS (`BUILD SUCCESSFUL`, 5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/FormFactor.kt \
        app/src/test/java/com/ledgernoir/tracker/ui/designsystem/FormFactorTest.kt
git commit -m "feat(android): FormFactor breakpoint logic for Flip 7 surfaces"
```

---

## Task 2: LocalFormFactor + ProvideFormFactor

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/FormFactor.kt`

- [ ] **Step 1: Add the CompositionLocal + provider**

Append to `FormFactor.kt`:
```kotlin
// --- Compose wiring (appended) ---
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Current surface. Defaults to Phone; real value supplied by [ProvideFormFactor]. */
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }

/**
 * Measures the available window and provides [LocalFormFactor]. Because it reads
 * constraints (not the Activity), it recomputes automatically when the window
 * resizes on fold/unfold — no Activity recreation required.
 */
@Composable
fun ProvideFormFactor(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val ff = formFactorFor(widthDp = maxWidth.value.toInt(), heightDp = maxHeight.value.toInt())
        CompositionLocalProvider(LocalFormFactor provides ff, content = content)
    }
}
```
> Note: in Kotlin, file-level `import`s must be at the top of the file. Move these imports up to the import block — they are shown here only to indicate what to add. The final file has all imports grouped at the top.

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/FormFactor.kt
git commit -m "feat(android): LocalFormFactor + ProvideFormFactor (fold-reactive)"
```

---

## Task 3: Design tokens (spacing + type ramp)

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/Tokens.kt`

- [ ] **Step 1: Create the tokens**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 8pt-based spacing scale used across the redesign. */
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner radii. */
object Radii {
    val card: Dp = 20.dp
    val cardArt: Dp = 18.dp
    val pill: Dp = 20.dp
}

/** Type sizes that ramp down on the small cover screen. Read inside composables. */
@Composable
fun heroFontSize(): TextUnit = when (LocalFormFactor.current) {
    FormFactor.Cover -> 27.sp
    else -> 36.sp
}

@Composable
fun titleFontSize(): TextUnit = when (LocalFormFactor.current) {
    FormFactor.Cover -> 18.sp
    else -> 22.sp
}
```

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/Tokens.kt
git commit -m "feat(android): spacing/radii tokens + cover-aware type ramp"
```

---

## Task 4: No restart on fold (manifest)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (the `<activity android:name=".MainActivity">` element)

- [ ] **Step 1: Add configChanges + resizeableActivity**

Change the opening `<activity>` tag for `.MainActivity` from:
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
```
to:
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:resizeableActivity="true"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden|density|uiMode">
```

- [ ] **Step 2: Compile + install + verify no restart on fold (device)**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
adb -s 192.168.1.2:33041 install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1
# Watch the activity lifecycle while folding/unfolding the phone by hand:
adb -s 192.168.1.2:33041 logcat -c
adb -s 192.168.1.2:33041 logcat ActivityManager:I MainActivity:I '*:S' &
# (fold and unfold the device a few times, then Ctrl-C)
```
Expected: **no** `Destroying`/`Start proc` lines for `com.ledgernoir.tracker` when folding — the activity is not recreated. (Device serial may differ; use `adb devices` to confirm. If wireless adb is disconnected, reconnect per the project's wireless flow.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(android): MainActivity configChanges so fold/unfold never restarts"
```

---

## Task 5: Edge-to-edge in MainActivity

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/MainActivity.kt`

- [ ] **Step 1: Enable edge-to-edge before setContent**

Add the import at the top with the other `androidx.activity` imports:
```kotlin
import androidx.activity.enableEdgeToEdge
```
In `onCreate`, immediately after `super.onCreate(savedInstanceState)` and before `smsPermissionGranted = hasSmsPermission()`, add:
```kotlin
        enableEdgeToEdge()
```

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/MainActivity.kt
git commit -m "feat(android): enable edge-to-edge"
```

---

## Task 6: Adaptive navigation + safe-area insets in AppShell

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt`

**Context:** `AppShell` holds a `Scaffold` whose `bottomBar` renders a `NavigationBar` over `bottomNavItems` (6 items). On the cover screen, labels won't fit; we render **icon-only** items there. We also wrap the whole shell in `ProvideFormFactor` and switch the Scaffold to `safeDrawing` insets so all screens stay clear of the status bar and the cover camera cutout.

- [ ] **Step 1: Add imports**

Add to the import block of `AppShell.kt`:
```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import com.ledgernoir.tracker.ui.designsystem.FormFactor
import com.ledgernoir.tracker.ui.designsystem.LocalFormFactor
import com.ledgernoir.tracker.ui.designsystem.ProvideFormFactor
```

- [ ] **Step 2: Wrap the shell body in ProvideFormFactor**

In `AppShell(...)`, wrap the existing top-level composable content. Find the line that creates the nav controller / starts the UI tree and enclose the body. Concretely, wrap the `Scaffold(...) { ... }` call:
```kotlin
    ProvideFormFactor {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = { /* existing bottomBar lambda unchanged except Step 3 */ }
        ) { innerPadding ->
            // existing NavHost(...) unchanged
        }
    }
```
> Keep all existing `navController`, `token`, `LaunchedEffect`, route logic exactly as-is; only the `Scaffold` gains `contentWindowInsets = WindowInsets.safeDrawing` and is nested inside `ProvideFormFactor { … }`.

- [ ] **Step 3: Make nav labels icon-only on Cover**

Inside the `bottomNavItems.forEach { (screen, icon) -> NavigationBarItem( ... ) }`, read the form factor once above the `NavigationBar` (e.g. `val ff = LocalFormFactor.current`) and change the `label` + add `alwaysShowLabel`:
```kotlin
                        label = if (ff == FormFactor.Cover) null else {
                            {
                                Text(
                                    screen.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.sp,
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                        alwaysShowLabel = ff != FormFactor.Cover,
```
(Leave `icon`, `selected`, `colors`, `onClick` exactly as they are.)

- [ ] **Step 4: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt
git commit -m "feat(android): adaptive nav (icon-only on cover) + safeDrawing insets"
```

---

## Task 7: HeroNumber component

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/HeroNumber.kt`

- [ ] **Step 1: Create the component + preview**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * The dominating hero block: a small uppercase [label], a big [amount] in the
 * display typeface (size ramps by form factor), and an optional colored [change]
 * line (green up / rose down). Reuses [MoneyText] for the amount.
 */
@Composable
fun HeroNumber(
    label: String,
    amount: Double,
    modifier: Modifier = Modifier,
    change: String? = null,
    changePositive: Boolean = true,
    showPaise: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.height(Space.s))
        MoneyText(amount = amount, fontSize = heroFontSize(), color = TextPrimary, showPaise = showPaise)
        if (change != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = change,
                style = MaterialTheme.typography.labelMedium,
                color = if (changePositive) AccentGreen else DebitRose,
            )
        }
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun HeroNumberPreview() {
    Column(Modifier.background(CanvasBlack).padding(16.dp)) {
        HeroNumber(label = "Net worth", amount = 1842300.0, change = "▲ 2.1%  ₹38,400 · this month")
    }
}
```
> Add `import androidx.compose.ui.unit.dp` for the preview padding.

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/HeroNumber.kt
git commit -m "feat(android): HeroNumber component (cover-aware hero block)"
```

---

## Task 8: MerchantLogo component

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/MerchantLogo.kt`

- [ ] **Step 1: Create the component + preview**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * A 36dp rounded chip showing a merchant's initial over a stable per-merchant
 * color (hash-derived). A deterministic fallback when no brand asset exists —
 * gives the transaction feed instant visual recognition (Fi/Jupiter style).
 */
@Composable
fun MerchantLogo(merchant: String, modifier: Modifier = Modifier) {
    val palette = listOf(
        Color(0xFFFC8019), Color(0xFF5b8def), Color(0xFF34D399),
        Color(0xFFE94F8A), Color(0xFFFBBF24), Color(0xFF9C6ADE), Color(0xFF22B8CF),
    )
    val color = palette[abs(merchant.hashCode()) % palette.size]
    val initial = merchant.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun MerchantLogoPreview() {
    Row {
        MerchantLogo("Swiggy")
        MerchantLogo("Uber")
        MerchantLogo("Amazon")
    }
}
```

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/MerchantLogo.kt
git commit -m "feat(android): MerchantLogo chip for the transaction feed"
```

---

## Task 9: CreditCardArt component

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/CreditCardArt.kt`

- [ ] **Step 1: Create the component + preview**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bank → 3-stop gradient for the card face. Falls back to a teal gradient. */
private fun bankGradient(bank: String): List<Color> = when (bank.uppercase()) {
    "AXIS" -> listOf(Color(0xFFC43170), Color(0xFF7D1C46), Color(0xFF3F0F25))
    "IDFC" -> listOf(Color(0xFFB8232E), Color(0xFF7C151C), Color(0xFF400A0E))
    "BOB" -> listOf(Color(0xFFF26522), Color(0xFFB14413), Color(0xFF5E230A))
    else -> listOf(Color(0xFF2DD4BF), Color(0xFF0F766E), Color(0xFF053B35))
}

/**
 * CRED-style credit-card face. Renders the bank gradient with network + last4 and
 * an optional bottom overlay: a utilization bar ([utilizationPct] 0..100) with a
 * [trailing] caption (e.g. "₹3,389 due" or "no dues"). Used on Dashboard, Bills,
 * Rewards. Keeps the credit-card aesthetic the user approved.
 */
@Composable
fun CreditCardArt(
    bank: String,
    last4: String?,
    modifier: Modifier = Modifier,
    network: String? = null,
    utilizationPct: Float? = null,
    trailing: String? = null,
    height: androidx.compose.ui.unit.Dp = 138.dp,
) {
    val colors = bankGradient(bank)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Radii.cardArt))
            .background(Brush.linearGradient(colors))
            .padding(15.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(bank.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (!network.isNullOrBlank()) {
                Text(network, color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Text("•••• ${last4 ?: "----"}", color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            if (utilizationPct != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("${utilizationPct.toInt()}% used", color = Color.White.copy(alpha = 0.9f),
                        fontSize = 9.sp, modifier = Modifier.weight(1f))
                    if (!trailing.isNullOrBlank()) {
                        Text(trailing, color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(utilizationPct.coerceIn(0f, 100f) / 100f)
                            .height(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun CreditCardArtPreview() {
    Column(Modifier.padding(16.dp)) {
        CreditCardArt(bank = "AXIS", last4 = "6978", network = "VISA",
            utilizationPct = 22f, trailing = "₹3,389 due", modifier = Modifier.width(240.dp))
        Spacer(Modifier.height(12.dp))
        CreditCardArt(bank = "IDFC", last4 = "2198", network = "RuPay",
            utilizationPct = 17f, trailing = "no dues", modifier = Modifier.width(240.dp))
    }
}
```

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/CreditCardArt.kt
git commit -m "feat(android): CreditCardArt gradient card face"
```

---

## Task 10: CardCarousel component

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/CardCarousel.kt`

- [ ] **Step 1: Create the component + preview**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.ui.theme.AccentTeal

/**
 * Horizontal pager of cards with a peeking next card and page dots. Generic over
 * page content. [count] is the number of pages; [page] renders page index i.
 */
@Composable
fun CardCarousel(
    count: Int,
    modifier: Modifier = Modifier,
    page: @Composable (index: Int) -> Unit,
) {
    if (count <= 0) return  // top-level guard (not inside a layout node) — safe
    val state = rememberPagerState(pageCount = { count })
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = state,
            contentPadding = PaddingValues(end = 36.dp),
            pageSpacing = 12.dp,
        ) { i -> page(i) }
        if (count > 1) {
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(count) { i ->
                    val selected = i == state.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (selected) 16.dp else 5.dp, height = 5.dp)
                            .clip(if (selected) RoundedCornerShape(3.dp) else CircleShape)
                            .background(if (selected) AccentTeal else androidx.compose.ui.graphics.Color(0xFF33333D)),
                    )
                }
            }
        }
    }
}
```
> The `if (count <= 0) return` is a guard at the **top of the function before any layout node is emitted** — this is the allowed form of early return (no layout child has been added yet), unlike an early return in the middle of a `Column` body.

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`. (If `HorizontalPager`/`rememberPagerState` are unresolved, add `implementation(libs.compose.foundation)` to `app/build.gradle.kts` with a catalog entry `compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }`; normally it resolves transitively via material3.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/CardCarousel.kt
git commit -m "feat(android): CardCarousel pager with page dots"
```

---

## Task 11: AllocationRing component

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/AllocationRing.kt`

- [ ] **Step 1: Create the component + preview**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One ring slice: a [fraction] of the whole (0..1) drawn in [color]. */
data class RingSlice(val fraction: Float, val color: Color)

/**
 * A donut ring built from [slices] (fractions should sum to ≤ 1). [center] renders
 * inside the hole (e.g. a total). Extends the existing donut idiom for allocation.
 */
@Composable
fun AllocationRing(
    slices: List<RingSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = 78.dp,
    stroke: Dp = 11.dp,
    center: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            var start = -90f
            val sw = stroke.toPx()
            slices.forEach { s ->
                val sweep = s.fraction.coerceIn(0f, 1f) * 360f
                drawArc(
                    color = s.color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = sw, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        center()
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun AllocationRingPreview() {
    AllocationRing(
        slices = listOf(
            RingSlice(0.62f, Color(0xFF2DD4BF)),
            RingSlice(0.31f, Color(0xFFFBBF24)),
            RingSlice(0.07f, Color(0xFFFF5C5C)),
        ),
    )
}
```

- [ ] **Step 2: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/AllocationRing.kt
git commit -m "feat(android): AllocationRing donut component"
```

---

## Task 12: Split Dashboard into Main + Cover (the proof)

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt`

**Context (verified against the real code):** `DashboardScreen.kt` declares
```kotlin
fun DashboardScreen(
    app: LedgerNoirApp,
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
    onOpenRange: (start: String, end: String, title: String) -> Unit = { _, _, _ -> },
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenNetWorth: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenMoneyRadar: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
)
```
The `DashboardViewModel` is created **inside** the composable via `viewModel(factory = ledgerViewModelFactory { … })`. Because both `DashboardMain` and `DashboardCover` are rendered under the **same** nav destination (one `ViewModelStoreOwner`) and call `viewModel<DashboardViewModel>()` with the same key, they receive the **same** VM instance — so state and scroll survive the fold-driven swap.
`DashboardUiState` has (relevant fields): `netWorth: Double?`, `spentThisMonth: Double`, `prevMonthSpent: Double?`, `radarNewCount: Int`, `unreadNotifications: Int`, `loading: Boolean`. **There is no credit-card list on the dashboard**, so the cover layout uses net worth + spend + urgent rows (the card carousel is proven via its `@Preview` in Tasks 9–10 and used for real in Phase 2 / Bills).

- [ ] **Step 1: Rename the existing composable to DashboardMain**

In `DashboardScreen.kt`, rename `fun DashboardScreen(` to `fun DashboardMain(`, keeping the **exact same parameter list** above. Leave its body unchanged.

- [ ] **Step 2: Add the dispatcher**

Add this composable (identical param list, forwarding every argument by name):
```kotlin
@Composable
fun DashboardScreen(
    app: LedgerNoirApp,
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
    onOpenRange: (start: String, end: String, title: String) -> Unit = { _, _, _ -> },
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenNetWorth: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenMoneyRadar: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> DashboardCover(
            app, repository, smsPermissionGranted, onOpenRange, onOpenCategory,
            onOpenSettings, onOpenNetWorth, onOpenNotifications, onOpenMoneyRadar, onOpenRewards,
        )
        else -> DashboardMain(
            app, repository, smsPermissionGranted, onOpenRange, onOpenCategory,
            onOpenSettings, onOpenNetWorth, onOpenNotifications, onOpenMoneyRadar, onOpenRewards,
        )
    }
}
```

- [ ] **Step 3: Add the cover layout**

Add `DashboardCover` with the same param list. It re-creates the VM the same way `DashboardMain` does (copy the exact `viewModel(factory = ledgerViewModelFactory { … })` block from `DashboardMain`), then renders a compact column. Uses only kept components (`CredCard`, `MoneyText`) + `HeroNumber`:
```kotlin
@Composable
private fun DashboardCover(
    app: LedgerNoirApp,
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
    onOpenRange: (start: String, end: String, title: String) -> Unit,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetWorth: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMoneyRadar: () -> Unit,
    onOpenRewards: () -> Unit,
) {
    val vm: DashboardViewModel = viewModel(
        factory = ledgerViewModelFactory {
            DashboardViewModel(
                it.useCases.getSpentRange, it.useCases.getCategories, it.useCases.getMonths,
                it.useCases.getMonthSummary, it.useCases.getSpent, it.useCases.getNetWorth,
                it.useCases.getStats, it.useCases.getNotifications, it.useCases.getRadarSummary,
                it.useCases.getRewards, it,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(smsPermissionGranted) { vm.startSyncIfPermitted(smsPermissionGranted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        com.ledgernoir.tracker.ui.designsystem.HeroNumber(
            label = "Net worth",
            amount = state.netWorth ?: 0.0,
            change = null,
            modifier = Modifier.clickable { onOpenNetWorth() },
        )
        Spacer(Modifier.height(12.dp))
        CredCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Spent this month", color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                MoneyText(amount = state.spentThisMonth, fontSize = 18.sp,
                    color = DebitRose, showPaise = false)
            }
        }
        if (state.radarNewCount > 0) {
            Spacer(Modifier.height(10.dp))
            CredCard(modifier = Modifier.clickable { onOpenMoneyRadar() }) {
                Text("📡 Money Radar · ${state.radarNewCount} new",
                    color = AmberWarn, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.unreadNotifications > 0) {
            Spacer(Modifier.height(10.dp))
            CredCard(modifier = Modifier.clickable { onOpenNotifications() }) {
                Text("🔔 ${state.unreadNotifications} notifications",
                    color = TextPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
```
> Imports needed in `DashboardScreen.kt` (add any not already present): `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.Row`, `androidx.compose.ui.Alignment`, `androidx.compose.ui.text.font.FontWeight`, `androidx.compose.ui.unit.sp`, and the theme colors `AmberWarn`/`DebitRose`/`TextSecondary`/`TextPrimary` (some already imported). Do **not** early-return inside the `Column`.

- [ ] **Step 4 (optional, low-risk): unify the Main hero**

In `DashboardMain`, the existing net-worth display may be replaced with `HeroNumber(label = "Net worth", amount = state.netWorth ?: 0.0, change = null)` for visual consistency. If the existing block already shows a freshness sub-line you want to keep, leave `DashboardMain` unchanged — this step is optional and must not regress the current Main layout.

- [ ] **Step 5: Compile gate**

```bash
./gradlew :app:assembleDebug -q && echo BUILD_OK
```
Expected: `BUILD_OK`.

- [ ] **Step 6: Install + verify on device (main + cover + fold)**

```bash
adb -s 192.168.1.2:33041 install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1
adb -s 192.168.1.2:33041 shell monkey -p com.ledgernoir.tracker -c android.intent.category.LAUNCHER 1
# main screen:
adb -s 192.168.1.2:33041 exec-out screencap -p > /tmp/dash_main.png
```
Manually verify on the device:
- **Main 6.9″:** Dashboard renders with the net-worth hero, content not under the status bar (insets), bottom nav shows labels.
- **Cover 4.1″:** open it on the cover (Good Lock enabled) — Dashboard shows the compact hero + card carousel; bottom nav is icon-only.
- **Fold transition:** fold/unfold while on Dashboard — no restart, scroll/state preserved, layout swaps between Main and Cover.

Expected: all three hold. Capture `/tmp/dash_main.png` (and a cover screenshot) as evidence.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt
git commit -m "feat(android): Dashboard Main/Cover split on FormFactor (Phase 1 proof)"
```

---

## Phase 1 Acceptance
- `FormFactorTest` passes (5 tests); `:app:assembleDebug` builds clean.
- Folding/unfolding never restarts the Activity (Task 4 logcat) and preserves Dashboard state (Task 12).
- Dashboard renders correctly on the **main** and **cover** screens with safe insets; nav adapts (labels on main, icon-only on cover).
- Shared components (`HeroNumber`, `MerchantLogo`, `CreditCardArt`, `CardCarousel`, `AllocationRing`) exist, compile, and are previewable — ready for Phase 2 (Bills/Transactions).

## Notes for later phases (not in scope here)
- Phase 2 wires real per-card utilization into `CreditCardArt` on Bills, and converts Transactions to the merchant-logo feed.
- Other screens keep working unchanged in Phase 1 (they render via the `else → …Main` path implicitly, since only Dashboard is split; the global insets + nav changes are safe for them).
