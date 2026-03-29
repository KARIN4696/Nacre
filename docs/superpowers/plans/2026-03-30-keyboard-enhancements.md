# Keyboard Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Alt key (sticky), arrow keys (▲▼), and 12-key flick input for Z Fold6 sub-display to the Nacre keyboard.

**Architecture:** Three layered changes: (1) KeyAction + LayerManager additions for Alt/Henkan, (2) Row 4 layout updates across all presets, (3) new FlickInputPad composable with tab system. Flick input bypasses JapaneseEngine romaji conversion, feeding kana directly into the existing prediction/Viterbi pipeline via a new `composingFlickKana` buffer in InputEngine.

**Tech Stack:** Kotlin, Jetpack Compose, Android InputMethodService

**Spec:** `docs/superpowers/specs/2026-03-30-keyboard-enhancements-design.md`

---

### Task 1: Add KeyAction.Alt and KeyAction.Henkan to sealed class

**Files:**
- Modify: `ime-config/src/main/kotlin/space/manus/nacre/config/KeymapConfig.kt:14-30`

- [ ] **Step 1: Add Alt and Henkan to KeyAction sealed class**

In `KeymapConfig.kt`, add two new data objects inside `sealed class KeyAction` (after line 29, before the closing `}`):

```kotlin
data object Alt : KeyAction()
data object Henkan : KeyAction()
```

- [ ] **Step 2: Verify the file compiles**

Run: `cd ~/Nacre && ./gradlew :ime-config:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-config/src/main/kotlin/space/manus/nacre/config/KeymapConfig.kt && git commit -m "feat: add KeyAction.Alt and KeyAction.Henkan"
```

---

### Task 2: Add Alt state to LayerManager

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/LayerManager.kt`

- [ ] **Step 1: Add isAltActive state and methods**

After the `isJapanese` state (line 23), add:

```kotlin
var isAltActive by mutableStateOf(false)
    private set
```

After `requestCommandPalette()` (line 97), add:

```kotlin
fun toggleAlt() {
    isAltActive = !isAltActive
}

fun consumeAlt(): Boolean {
    if (isAltActive) {
        isAltActive = false
        return true
    }
    return false
}
```

- [ ] **Step 2: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/LayerManager.kt && git commit -m "feat: add Alt sticky modifier state to LayerManager"
```

---

### Task 3: Handle Alt and Henkan in InputEngine + Alt meta state

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt`

- [ ] **Step 1: Add Alt and Henkan cases to processAction()**

In `processAction()` (line 242), add these cases inside the `when (action)` block, after the `is KeyAction.Symbols` case (around line 460):

```kotlin
is KeyAction.Alt -> service.layerManager.toggleAlt()

is KeyAction.Henkan -> {
    if (composingFlickKana.isNotEmpty()) {
        if (isConverting) nextCandidate(ic) else startFlickConversion(ic)
    } else if (composingText.isNotEmpty()) {
        if (isConverting) nextCandidate(ic) else startConversion(ic)
    }
}
```

- [ ] **Step 2: Modify sendKeyEvent() to apply Alt meta state**

Replace the existing `sendKeyEvent` method (line 834-838):

```kotlin
private fun sendKeyEvent(keyCode: Int) {
    val ic = service.currentInputConnection ?: return
    val now = System.currentTimeMillis()
    val altMeta = if (service.layerManager.isAltActive) KeyEvent.META_ALT_ON else 0
    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, altMeta))
    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, altMeta))
    if (altMeta != 0) service.layerManager.consumeAlt()
}
```

- [ ] **Step 3: Also apply Alt to Ctrl+key combinations in KeyAction.KeyCode handler**

In the `is KeyAction.KeyCode` handler (line 463-476), modify to combine Alt with Ctrl:

```kotlin
is KeyAction.KeyCode -> {
    val now = System.currentTimeMillis()
    var meta = 0
    if (action.ctrl) meta = meta or KeyEvent.META_CTRL_ON
    if (service.layerManager.isAltActive) meta = meta or KeyEvent.META_ALT_ON
    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, action.code, 0, meta))
    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, action.code, 0, meta))
    if (service.layerManager.isAltActive) service.layerManager.consumeAlt()
}
```

- [ ] **Step 4: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt && git commit -m "feat: handle Alt/Henkan actions and apply Alt meta state to key events"
```

---

### Task 4: Update Row 4 layouts (qwertyBase, fnLayer1, fnLayer2)

**Files:**
- Modify: `ime-config/src/main/kotlin/space/manus/nacre/config/KeymapConfig.kt:70-81` (qwertyBase Row 4)
- Modify: `ime-config/src/main/kotlin/space/manus/nacre/config/KeymapConfig.kt:108-117` (fnLayer1 Row 4)
- Modify: `ime-config/src/main/kotlin/space/manus/nacre/config/KeymapConfig.kt:152-160` (fnLayer2 Row 4)

- [ ] **Step 1: Update qwertyBase Row 4**

Replace lines 72-80 (the Row 4 comment and listOf):

```kotlin
// Row 4: ▲▼ Fn ⇧ [⎵⎵] Alt [↵↵]
// Arrow keys left-right split, Space reduced, Alt sticky modifier
listOf(
    KeyDef("▲", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
    KeyDef("▼", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
    KeyDef("Fn", action = KeyAction.Fn),
    KeyDef("⇧", action = KeyAction.Shift),
    KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 2f,
        swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
    KeyDef("Alt", action = KeyAction.Alt),
    KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.5f),
),
```

- [ ] **Step 2: Update fnLayer1 Row 4**

Replace lines 108-117:

```kotlin
listOf(
    KeyDef("▲", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
    KeyDef("▼", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
    KeyDef("😀", action = KeyAction.Emoji),
    KeyDef("#+", label = "#+", action = KeyAction.Symbols),
    KeyDef("Fn2", action = KeyAction.FnPage2),
    KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 1.5f,
        swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
    KeyDef("Alt", action = KeyAction.Alt),
    KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
),
```

- [ ] **Step 3: Update fnLayer2 Row 4**

Replace lines 152-160:

```kotlin
listOf(
    KeyDef("▲", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
    KeyDef("▼", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
    KeyDef("Fn", action = KeyAction.Fn),
    KeyDef("⇧", action = KeyAction.Shift),
    KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 2f,
        swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
    KeyDef("Alt", action = KeyAction.Alt),
    KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.5f),
),
```

- [ ] **Step 4: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-config:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd ~/Nacre && git add ime-config/src/main/kotlin/space/manus/nacre/config/KeymapConfig.kt && git commit -m "feat: update Row 4 layouts with arrow keys and Alt"
```

---

### Task 5: Update PresetProvider modifierRow and Emacs layout

**Files:**
- Modify: `ime-config/src/main/kotlin/space/manus/nacre/config/PresetProvider.kt:22-33` (modifierRow)
- Modify: `ime-config/src/main/kotlin/space/manus/nacre/config/PresetProvider.kt:181-193` (emacsLayout Row 4)

- [ ] **Step 1: Update modifierRow**

Replace lines 22-33:

```kotlin
private val modifierRow = listOf(
    KeyDef("▲", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
    KeyDef("▼", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
    KeyDef("Fn", action = KeyAction.Fn),
    KeyDef("\u21E7", action = KeyAction.Shift),
    KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 1.5f,
        swipeUp = "Tab", swipeLeft = "\u30FC"),
    KeyDef("Alt", action = KeyAction.Alt),
    KeyDef("\uFF1F", label = "\uFF1F", swipeUp = "?", swipeDown = "\uFF01"),
    KeyDef("\u30FC", label = "\u30FC", swipeUp = "\u301C", swipeDown = "-"),
    KeyDef("\u3042", label = "\u3042", action = KeyAction.ToggleJapanese),
)
```

- [ ] **Step 2: Update Emacs layout Row 4**

Replace lines 181-193 (emacsLayout Row 4):

```kotlin
// Row 4: ▲▼ Ctrl Fn Shift [⎵⎵] Alt [↵] [あ/A]
listOf(
    KeyDef("▲", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
    KeyDef("▼", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
    KeyDef("Ctrl", action = KeyAction.KeyCode(KeyEvent.KEYCODE_CTRL_LEFT)),
    KeyDef("Fn", action = KeyAction.Fn),
    KeyDef("\u21E7", action = KeyAction.Shift),
    KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 1.5f,
        swipeUp = "Tab", swipeLeft = "\u30FC"),
    KeyDef("Alt", action = KeyAction.Alt),
    KeyDef("\u21B5", action = KeyAction.Enter),
    KeyDef("\u3042", label = "\u3042", action = KeyAction.ToggleJapanese),
),
```

- [ ] **Step 3: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-config:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
cd ~/Nacre && git add ime-config/src/main/kotlin/space/manus/nacre/config/PresetProvider.kt && git commit -m "feat: update PresetProvider modifierRow and Emacs layout with arrows/Alt"
```

---

### Task 6: Update KeyView and StatusBar for Alt display

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/KeyView.kt:140-153` (semantics)
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/KeyboardScreen.kt:140-189` (StatusBar)

- [ ] **Step 1: Add Alt to KeyView semantics**

In `KeyView.kt`, inside the `semantics` block `contentDescription` `when` (around line 140-153), add before the `else ->` branch:

```kotlin
is KeyAction.Alt -> "Alt"
is KeyAction.Henkan -> "Henkan"
```

- [ ] **Step 2: Add Alt visual active state in KeyView**

In `KeyView.kt`, after the `isFnKey` variable (around line 82-84), add Alt active state detection:

```kotlin
val isAltKey = keyDef.action is KeyAction.Alt
val isAltActive = isAltKey && service.layerManager.isAltActive
```

Update `bgColor` (around line 85-89) to include Alt active state:

```kotlin
val bgColor = when {
    isPressed -> KeyBgPressed
    isAltActive -> KeyBgPressed  // Show pressed-like state when Alt is sticky
    isFnKey -> KeyBg
    else -> KeyBg
}

val textColor = when {
    isPressed -> GlowColor
    isAltActive -> GlowColor  // Accent color when Alt is sticky
    else -> KeyText
}
```

- [ ] **Step 3: Add Alt indicator to StatusBar**

In `KeyboardScreen.kt` `StatusBar` composable (line 140-189), add Alt state read and display. After `val showShift = layerManager.isShifted` (line 148), add:

```kotlin
val showAlt = layerManager.isAltActive
```

Inside the left `Row` (line 158-165), add after the Shift indicator:

```kotlin
if (showAlt) {
    Text(text = "Alt", color = accentColor, fontSize = 10.sp)
}
```

- [ ] **Step 3: Convert layout routing from `else` to exhaustive `when`**

In `KeyboardScreen.kt` (line 84-89), replace:

```kotlin
when (layoutMode) {
    space.manus.nacre.ime.foldable.LayoutMode.FullVSplit ->
        VSplitKeyboardScreen(service = service, angle = 4f)
    else ->
        StandardKeyboardScreen(service = service)
}
```

With:

```kotlin
when (layoutMode) {
    space.manus.nacre.ime.foldable.LayoutMode.FullVSplit ->
        VSplitKeyboardScreen(service = service, angle = 4f)
    space.manus.nacre.ime.foldable.LayoutMode.FlickInput12Key ->
        FlickInputPad(service = service)
    space.manus.nacre.ime.foldable.LayoutMode.StandardQwerty,
    space.manus.nacre.ime.foldable.LayoutMode.CompactQwerty,
    space.manus.nacre.ime.foldable.LayoutMode.QuickInputPad ->
        StandardKeyboardScreen(service = service)
}
```

Note: `FlickInputPad` doesn't exist yet — this will cause a compile error until Task 9. That's OK; we commit this alongside Task 9.

- [ ] **Step 4: Verify compile (will fail until FlickInputPad exists — skip for now)**

- [ ] **Step 5: Commit (combined with Task 9)**

---

### Task 7: Add FlickInput12Key to LayoutSelector

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/foldable/LayoutSelector.kt`

- [ ] **Step 1: Add FlickInput12Key enum value**

In `LayoutMode` enum (line 8-20), add after `QuickInputPad`:

```kotlin
/** 12-key flick input for Japanese on foldable sub-displays. */
FlickInput12Key,
```

- [ ] **Step 2: Update default sub-display mode**

In `loadSubDisplayMode()` (line 68-75), change both references to `CompactQwerty` to `FlickInput12Key`:

```kotlin
private fun loadSubDisplayMode(): LayoutMode {
    val name = prefs.getString(KEY_SUB_DISPLAY_MODE, LayoutMode.FlickInput12Key.name)
    return try {
        LayoutMode.valueOf(name ?: LayoutMode.FlickInput12Key.name)
    } catch (_: IllegalArgumentException) {
        LayoutMode.FlickInput12Key
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (LayoutSelector compiles independently)

- [ ] **Step 4: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/foldable/LayoutSelector.kt && git commit -m "feat: add FlickInput12Key layout mode to LayoutSelector"
```

---

### Task 8: Create FlickEngine (flick direction to kana mapping)

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/FlickEngine.kt`

- [ ] **Step 1: Create FlickEngine.kt**

Create file at `ime-core/src/main/kotlin/space/manus/nacre/ime/input/FlickEngine.kt`:

```kotlin
package space.manus.nacre.ime.input

/**
 * Maps 12-key flick directions to kana characters.
 * Also handles dakuten (゛), handakuten (゜), and small kana (小) transformations.
 */
object FlickEngine {

    enum class Direction { Tap, Left, Up, Right, Down }

    data class FlickKey(
        val id: String,
        val label: String,
        val tap: String,
        val left: String? = null,
        val up: String? = null,
        val right: String? = null,
        val down: String? = null,
    )

    val kanaKeys: List<FlickKey> = listOf(
        FlickKey("あ", "あ", "あ", "い", "う", "え", "お"),
        FlickKey("か", "か", "か", "き", "く", "け", "こ"),
        FlickKey("さ", "さ", "さ", "し", "す", "せ", "そ"),
        FlickKey("た", "た", "た", "ち", "つ", "て", "と"),
        FlickKey("な", "な", "な", "に", "ぬ", "ね", "の"),
        FlickKey("は", "は", "は", "ひ", "ふ", "へ", "ほ"),
        FlickKey("ま", "ま", "ま", "み", "む", "め", "も"),
        FlickKey("や", "や", "や", "（", "ゆ", "）", "よ"),
        FlickKey("ら", "ら", "ら", "り", "る", "れ", "ろ"),
        FlickKey("わ", "わ", "わ", "を", "ん", "ー", "〜"),
    )

    data class SymbolFlickKey(
        val id: String,
        val tap: String,
        val left: String? = null,
        val up: String? = null,
        val right: String? = null,
        val down: String? = null,
    )

    val symbolKeys: List<SymbolFlickKey> = listOf(
        SymbolFlickKey("@", "@", "#", "$"),
        SymbolFlickKey("%", "%", "&", "*"),
        SymbolFlickKey("-", "-", "+", "="),
        SymbolFlickKey("(", "(", ")", "[", "]"),
        SymbolFlickKey("{", "{", "}", "<", ">"),
        SymbolFlickKey("/", "/", "\\", "|"),
        SymbolFlickKey("!", "!", "?", "."),
        SymbolFlickKey(":", ":", ";", ","),
        SymbolFlickKey("'", "'", "\"", "`"),
        SymbolFlickKey("~", "~", "^", "_"),
        SymbolFlickKey("「", "「", "」", "『", "』"),
    )

    fun resolveFlick(key: FlickKey, direction: Direction): String? = when (direction) {
        Direction.Tap -> key.tap
        Direction.Left -> key.left
        Direction.Up -> key.up
        Direction.Right -> key.right
        Direction.Down -> key.down
    }

    fun resolveSymbolFlick(key: SymbolFlickKey, direction: Direction): String? = when (direction) {
        Direction.Tap -> key.tap
        Direction.Left -> key.left
        Direction.Up -> key.up
        Direction.Right -> key.right
        Direction.Down -> key.down
    }

    // --- Dakuten / Handakuten / Small ---

    private val dakutenMap = mapOf(
        'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
        'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
        'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
        'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
        'う' to 'ゔ',
    )

    private val handakutenMap = mapOf(
        'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ',
    )

    private val smallMap = mapOf(
        'つ' to 'っ', 'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ',
        'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
        'わ' to 'ゎ',
    )

    // Reverse maps for toggle behavior (が→か on second dakuten tap)
    private val reverseDakuten = dakutenMap.entries.associate { (k, v) -> v to k }
    private val reverseHandakuten = handakutenMap.entries.associate { (k, v) -> v to k }
    private val reverseSmall = smallMap.entries.associate { (k, v) -> v to k }

    fun applyDakuten(lastKana: Char): Char? =
        dakutenMap[lastKana] ?: reverseDakuten[lastKana]

    fun applyHandakuten(lastKana: Char): Char? =
        handakutenMap[lastKana] ?: reverseHandakuten[lastKana]

    fun applySmall(lastKana: Char): Char? =
        smallMap[lastKana] ?: reverseSmall[lastKana]
}

enum class DakutenType { Dakuten, Handakuten, Small }
```

- [ ] **Step 2: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/FlickEngine.kt && git commit -m "feat: create FlickEngine with kana/symbol flick maps and dakuten"
```

---

### Task 9: Add flick kana input methods to InputEngine

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt`

- [ ] **Step 1: Add composingFlickKana buffer**

After `private var composingText: String = ""` (line 29), add:

```kotlin
private var composingFlickKana: String = ""
```

- [ ] **Step 2: Add processFlickKana() method**

After the `moveCursor()` method (around line 866), add:

```kotlin
/**
 * Direct kana input from flick keyboard.
 * Bypasses JapaneseEngine romaji — appends kana directly.
 */
fun processFlickKana(kana: String) {
    val ic = service.currentInputConnection ?: return
    if (isConverting) commitSelectedCandidate(ic)

    composingFlickKana += kana
    composingKana = composingFlickKana
    ic.setComposingText(composingFlickKana, 1)
    updatePredictions(composingFlickKana)
}

fun processFlickDakuten(type: DakutenType) {
    if (composingFlickKana.isEmpty()) return
    val ic = service.currentInputConnection ?: return
    val lastChar = composingFlickKana.last()
    val replaced = when (type) {
        DakutenType.Dakuten -> FlickEngine.applyDakuten(lastChar)
        DakutenType.Handakuten -> FlickEngine.applyHandakuten(lastChar)
        DakutenType.Small -> FlickEngine.applySmall(lastChar)
    } ?: return

    composingFlickKana = composingFlickKana.dropLast(1) + replaced
    composingKana = composingFlickKana
    ic.setComposingText(composingFlickKana, 1)
    updatePredictions(composingFlickKana)
}

/**
 * Flick-mode conversion. Uses composingFlickKana directly (no romaji).
 */
private fun startFlickConversion(ic: InputConnection) {
    val kana = composingFlickKana
    val dict = dictionary
    if (dict != null) {
        val results = dict.convert(kana)
        if (results.isNotEmpty()) {
            fullKana = kana
            segmentBoundary = kana.length
            candidates.clear()
            candidates.addAll(results)
            selectedCandidateIndex = 0
            isConverting = true
            ic.setComposingText(results[0].surface, 1)
            return
        }
    }
    // No results: commit as kana
    ic.commitText(kana, 1)
    composingFlickKana = ""
    composingKana = ""
    clearCandidates()
}

fun processFlickBackspace() {
    val ic = service.currentInputConnection ?: return
    if (isConverting) {
        cancelConversion(ic)
    } else if (composingFlickKana.isNotEmpty()) {
        composingFlickKana = composingFlickKana.dropLast(1)
        composingKana = composingFlickKana
        if (composingFlickKana.isEmpty()) {
            ic.finishComposingText()
            clearCandidates()
        } else {
            ic.setComposingText(composingFlickKana, 1)
            updatePredictions(composingFlickKana)
        }
    } else {
        ic.deleteSurroundingText(1, 0)
    }
}
```

- [ ] **Step 3: Reset composingFlickKana in existing methods**

In `onStartInput()` (around line 82-85), add after `composingText = ""`:

```kotlin
composingFlickKana = ""
```

In `finishComposing()` (around line 733-742), add after `composingText = ""`:

```kotlin
composingFlickKana = ""
```

In `clearCandidates()` (around line 744-751), add inside the method:

```kotlin
composingFlickKana = ""
```

In `commitSelectedCandidate()` (around line 612-627), add after `composingText = ""`:

```kotlin
composingFlickKana = ""
```

- [ ] **Step 4: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt && git commit -m "feat: add flick kana input methods to InputEngine"
```

---

### Task 9b: Add buffer cleanup on layout mode switch and Space voice input

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/KeyView.kt`

- [ ] **Step 1: Add method to commit and clear flick buffer**

In `InputEngine.kt`, add after `processFlickBackspace()`:

```kotlin
/**
 * Commit any active flick composition (called when switching away from flick layout).
 */
fun commitFlickIfNeeded() {
    if (composingFlickKana.isNotEmpty()) {
        val ic = service.currentInputConnection ?: return
        if (isConverting) {
            commitSelectedCandidate(ic)
        } else {
            ic.commitText(composingFlickKana, 1)
            composingFlickKana = ""
            composingKana = ""
            clearCandidates()
        }
    }
}
```

- [ ] **Step 2: Add Space long-press voice input handling in KeyView.kt**

In `KeyView.kt`, inside the long-press handler (around line 186-199), add a branch for Space key:

```kotlin
} else if (keyDef.action is KeyAction.Space) {
    // Space long press: start voice input
    val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
    service.voiceInputManager.startListening(lang)
}
```

Add this after the `is KeyAction.SwitchIme` long-press branch (line 192) and before the `else if (keyDef.longPress != null)` branch.

- [ ] **Step 3: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/KeyView.kt && git commit -m "feat: add flick buffer cleanup and Space long-press voice input"
```

---

### Task 10: Create FlickInputPad composable

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/FlickInputPad.kt`

This is the largest task. FlickInputPad is the main composable that contains the tab bar, flick grid, candidate bar, and bottom modifier row.

- [ ] **Step 1: Create FlickInputPad.kt**

Create file at `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/FlickInputPad.kt`:

```kotlin
package space.manus.nacre.ime.keyboard

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.DakutenType
import space.manus.nacre.ime.input.FlickEngine
import androidx.compose.foundation.border
import kotlin.math.abs

private enum class FlickTab { Kana, Numbers, Symbols, Emoji }

@Composable
fun FlickInputPad(service: NacreInputMethodService) {
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())
    val accentColor = Color(theme.accent.toInt())

    var activeTab by remember { mutableStateOf(FlickTab.Kana) }

    // If emoji is requested, switch to emoji tab
    val isEmojiRequested = service.layerManager.isEmojiRequested
    LaunchedEffect(isEmojiRequested) {
        if (isEmojiRequested) {
            activeTab = FlickTab.Emoji
            service.layerManager.isEmojiRequested = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        // Tab bar
        FlickTabBar(
            activeTab = activeTab,
            onTabSelected = { activeTab = it },
            accentColor = accentColor,
            bgColor = bgColor,
        )

        // Candidate bar
        CandidateBar(service = service)

        // Tab content
        when (activeTab) {
            FlickTab.Kana -> FlickKanaGrid(service = service)
            FlickTab.Numbers -> NumberPad(service = service)
            FlickTab.Symbols -> SymbolFlickPad(service = service)
            FlickTab.Emoji -> {
                EmojiPanel(service = service, onDismiss = { activeTab = FlickTab.Kana })
                return // EmojiPanel takes over
            }
        }

        // Bottom modifier row (not shown in emoji tab)
        FlickBottomRow(service = service)
    }
}

@Composable
private fun FlickTabBar(
    activeTab: FlickTab,
    onTabSelected: (FlickTab) -> Unit,
    accentColor: Color,
    bgColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor),
    ) {
        val tabs = listOf(
            FlickTab.Kana to "あ",
            FlickTab.Numbers to "123",
            FlickTab.Symbols to "#+",
            FlickTab.Emoji to "😊",
        )
        for ((tab, label) in tabs) {
            val isActive = tab == activeTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 6.dp)
                    .then(
                        if (isActive) Modifier.background(bgColor) else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        color = if (isActive) accentColor else Color(0xFF555555),
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(2.dp)
                                .background(accentColor, RoundedCornerShape(1.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlickKanaGrid(service: NacreInputMethodService) {
    val keys = FlickEngine.kanaKeys
    // 4 rows x 3 columns
    for (rowIndex in 0 until 4) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (colIndex in 0 until 3) {
                val keyIndex = rowIndex * 3 + colIndex
                if (keyIndex < keys.size) {
                    FlickKeyView(
                        flickKey = keys[keyIndex],
                        service = service,
                        row = rowIndex,
                        column = colIndex,
                        modifier = Modifier.weight(1f),
                    )
                } else if (keyIndex == 10) {
                    // Dakuten key
                    DakutenKeyView(
                        service = service,
                        row = rowIndex,
                        column = colIndex,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Backspace key
                    FlickModKeyView(
                        label = "⌫",
                        service = service,
                        row = rowIndex,
                        column = colIndex,
                        onTap = { service.inputEngine.processFlickBackspace() },
                        onLongPress = { service.inputEngine.processFlickBackspace() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlickKeyView(
    flickKey: FlickEngine.FlickKey,
    service: NacreInputMethodService,
    row: Int,
    column: Int,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accent = Color(theme.accent.toInt())
    val edgeColor = Color(theme.surface.toInt())
    val lighting = service.keyLighting
    val lightingColor = lighting.getKeyColor(flickKey.id, row, column)
    val haptic = LocalHapticFeedback.current

    var isPressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var popupChar by remember { mutableStateOf("") }

    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(30), label = "scale")
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
    val shape = RoundedCornerShape(6.dp)

    val bgColor = if (isPressed) keyBgPressed else keyBg
    val textColor = if (isPressed) accent else keyText
    val borderColor = if (isPressed) accent.copy(alpha = 0.8f)
        else if (lightingColor != Color.Transparent) lightingColor
        else Color.Transparent

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else edgeColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier.border(0.5.dp, edgeColor.copy(alpha = 0.5f), shape)
                }
            )
            .pointerInput(flickKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var wasDragged = false

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y
                                if (abs(totalDragX) > swipeThresholdPx || abs(totalDragY) > swipeThresholdPx) {
                                    wasDragged = true
                                    // Update popup dynamically as finger moves
                                    val dir = resolveDirection(totalDragX, totalDragY)
                                    val resolved = FlickEngine.resolveFlick(flickKey, dir)
                                    if (resolved != null) {
                                        popupChar = resolved
                                        showPopup = true
                                    }
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        showPopup = false
                    }

                    val direction = if (wasDragged) resolveDirection(totalDragX, totalDragY) else FlickEngine.Direction.Tap
                    val resolved = FlickEngine.resolveFlick(flickKey, direction)
                    if (resolved != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        service.feedbackManager.onKeyPress(KeyAction.Text(resolved))
                        lighting.onKeyPress(flickKey.id, column)
                        service.inputEngine.processFlickKana(resolved)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = flickKey.label,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            style = if (isPressed) {
                androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(color = accent, blurRadius = 12f),
                )
            } else {
                androidx.compose.ui.text.TextStyle.Default
            },
        )

        // Flick popup
        if (showPopup && popupChar.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, with(LocalDensity.current) { -56.dp.roundToPx() }),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A3A6A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = popupChar, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DakutenKeyView(
    service: NacreInputMethodService,
    row: Int,
    column: Int,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accent = Color(theme.accent.toInt())
    val edgeColor = Color(theme.surface.toInt())
    val haptic = LocalHapticFeedback.current
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(30), label = "scale")
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else edgeColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .border(0.5.dp, edgeColor.copy(alpha = 0.5f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var wasDragged = false

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y
                                if (abs(totalDragX) > swipeThresholdPx || abs(totalDragY) > swipeThresholdPx) {
                                    wasDragged = true
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                    }

                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val type = if (wasDragged) {
                        val dir = resolveDirection(totalDragX, totalDragY)
                        when (dir) {
                            FlickEngine.Direction.Left -> DakutenType.Handakuten
                            FlickEngine.Direction.Up -> DakutenType.Small
                            else -> DakutenType.Dakuten
                        }
                    } else {
                        DakutenType.Dakuten
                    }
                    service.inputEngine.processFlickDakuten(type)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "゛゜",
            color = if (isPressed) accent else keyText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun FlickModKeyView(
    label: String,
    service: NacreInputMethodService,
    row: Int,
    column: Int,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accent = Color(theme.accent.toInt())
    val edgeColor = Color(theme.surface.toInt())
    val haptic = LocalHapticFeedback.current

    var isPressed by remember { mutableStateOf(false) }
    var bsRepeating by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(30), label = "scale")
    val shape = RoundedCornerShape(6.dp)

    // BS long-press repeat
    LaunchedEffect(bsRepeating) {
        if (!bsRepeating) return@LaunchedEffect
        delay(80L)
        while (bsRepeating) {
            onLongPress?.invoke()
            delay(50L)
        }
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else edgeColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .border(0.5.dp, edgeColor.copy(alpha = 0.5f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    val downTime = System.currentTimeMillis()
                    var longPressHandled = false

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(
                                if (longPressHandled) Long.MAX_VALUE
                                else maxOf(1L, 350L - (System.currentTimeMillis() - downTime)),
                            ) {
                                awaitPointerEvent()
                            }

                            if (event == null && !longPressHandled) {
                                longPressHandled = true
                                if (onLongPress != null) bsRepeating = true
                                continue
                            }

                            val change = event?.changes?.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        isPressed = false
                        bsRepeating = false
                    }

                    if (!longPressHandled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (isPressed) accent else keyText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun FlickBottomRow(service: NacreInputMethodService) {
    val bottomKeys = listOf(
        KeyDef("▲", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
        KeyDef("▼", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
        KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 1.5f,
            swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
        KeyDef("変換", action = KeyAction.Henkan),
        KeyDef("Alt", action = KeyAction.Alt, widthMultiplier = 0.8f),
        KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
    )

    KeyRow(keys = bottomKeys, service = service, rowIndex = 5, keyHeightDp = 36f)
}

private fun resolveDirection(dx: Float, dy: Float): FlickEngine.Direction {
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) FlickEngine.Direction.Right else FlickEngine.Direction.Left
    } else {
        if (dy > 0) FlickEngine.Direction.Down else FlickEngine.Direction.Up
    }
}

```

- [ ] **Step 2: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (now that FlickInputPad exists, KeyboardScreen routing from Task 6 resolves)

- [ ] **Step 3: Commit Task 6 + Task 10 together**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/FlickInputPad.kt ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/KeyboardScreen.kt ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/KeyView.kt && git commit -m "feat: create FlickInputPad composable and update KeyboardScreen routing"
```

---

### Task 11: Create NumberPad composable

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/NumberPad.kt`

- [ ] **Step 1: Create NumberPad.kt**

Create file at `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/NumberPad.kt`:

```kotlin
package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService

/**
 * Simple 4x3 number pad for the 12-key flick layout's "123" tab.
 * Reuses KeyRow and KeyView for consistent styling and lighting.
 */
@Composable
fun NumberPad(service: NacreInputMethodService) {
    val rows = listOf(
        listOf(numKey("1"), numKey("2"), numKey("3")),
        listOf(numKey("4"), numKey("5"), numKey("6")),
        listOf(numKey("7"), numKey("8"), numKey("9")),
        listOf(
            KeyDef("*", label = "* #", swipeRight = "#"),
            numKey("0"),
            KeyDef("⌫", action = KeyAction.Backspace),
        ),
    )
    for ((rowIndex, row) in rows.withIndex()) {
        KeyRow(keys = row, service = service, rowIndex = rowIndex, keyHeightDp = 52f)
    }
}

private fun numKey(digit: String) = KeyDef(digit, action = KeyAction.Text(digit))
```

- [ ] **Step 2: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/NumberPad.kt && git commit -m "feat: create NumberPad composable for 12-key 123 tab"
```

---

### Task 12: Create SymbolFlickPad composable

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/SymbolFlickPad.kt`

- [ ] **Step 1: Create SymbolFlickPad.kt**

Create file at `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/SymbolFlickPad.kt`:

```kotlin
package space.manus.nacre.ime.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.FlickEngine
import androidx.compose.foundation.border
import kotlin.math.abs

/**
 * Symbol grid with flick for alternatives, for the 12-key "#+" tab.
 */
@Composable
fun SymbolFlickPad(service: NacreInputMethodService) {
    val keys = FlickEngine.symbolKeys
    // 4 rows x 3 columns (11 symbol keys + backspace)
    for (rowIndex in 0 until 4) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (colIndex in 0 until 3) {
                val keyIndex = rowIndex * 3 + colIndex
                if (keyIndex < keys.size) {
                    SymbolFlickKeyView(
                        symbolKey = keys[keyIndex],
                        service = service,
                        row = rowIndex,
                        column = colIndex,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Backspace in last slot
                    KeyView(
                        keyDef = KeyDef("⌫", action = KeyAction.Backspace),
                        service = service,
                        modifier = Modifier.weight(1f),
                        row = rowIndex,
                        column = colIndex,
                        heightDp = 52f,
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolFlickKeyView(
    symbolKey: FlickEngine.SymbolFlickKey,
    service: NacreInputMethodService,
    row: Int,
    column: Int,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accent = Color(theme.accent.toInt())
    val edgeColor = Color(theme.surface.toInt())
    val haptic = LocalHapticFeedback.current

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(30), label = "scale")
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else edgeColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (isPressed) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.8f), shape)
                } else {
                    Modifier.border(0.5.dp, edgeColor.copy(alpha = 0.5f), shape)
                }
            )
            .pointerInput(symbolKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var wasDragged = false

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y
                                if (abs(totalDragX) > swipeThresholdPx || abs(totalDragY) > swipeThresholdPx) {
                                    wasDragged = true
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                    }

                    val direction = if (wasDragged) {
                        if (abs(totalDragX) > abs(totalDragY)) {
                            if (totalDragX > 0) FlickEngine.Direction.Right else FlickEngine.Direction.Left
                        } else {
                            if (totalDragY > 0) FlickEngine.Direction.Down else FlickEngine.Direction.Up
                        }
                    } else {
                        FlickEngine.Direction.Tap
                    }

                    val resolved = FlickEngine.resolveSymbolFlick(symbolKey, direction)
                    if (resolved != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        service.feedbackManager.onKeyPress(KeyAction.Text(resolved))
                        val ic = service.currentInputConnection
                        ic?.commitText(resolved, 1)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbolKey.tap,
            color = if (isPressed) accent else keyText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

// border extension
@Composable
private fun Modifier.border(
    width: androidx.compose.ui.unit.Dp,
    color: Color,
    shape: androidx.compose.ui.graphics.Shape,
) = this.then(
    androidx.compose.foundation.border(width, color, shape)
)
```

- [ ] **Step 2: Verify compile**

Run: `cd ~/Nacre && ./gradlew :ime-core:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/SymbolFlickPad.kt && git commit -m "feat: create SymbolFlickPad composable for 12-key #+ tab"
```

---

### Task 13: Full build verification

- [ ] **Step 1: Run full project build**

Run: `cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

If errors appear, read the error messages, fix the issues, and re-run the build.

- [ ] **Step 3: Commit any fixes**

```bash
cd ~/Nacre && git add -A && git commit -m "fix: resolve build errors from keyboard enhancements"
```

(Only if fixes were needed.)

---

### Task 14: Update CLAUDE.md with new architecture info

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update Key Layout section**

Update the "Key Layout (Base Layer Row 4)" line in CLAUDE.md to reflect the new layout:

```
## Key Layout (Base Layer Row 4)
`[▲][▼][Fn][⇧][Space(2x)][Alt] | gap/trackball | [⌫(1.2x)][Enter(1.2x)][GL][.]`
```

- [ ] **Step 2: Add FlickInputPad to Module Structure**

Add under the ime-core description:

```
ime-core/     — IME サービス本体 (InputEngine, KeyboardScreen, TrackballView, FlickInputPad)
```

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add CLAUDE.md && git commit -m "docs: update CLAUDE.md with new Row 4 layout and FlickInputPad"
```
