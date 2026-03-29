# Keyboard Enhancements: Alt Key, Arrow Keys, 12-Key Flick Input

**Date:** 2026-03-30
**Status:** Approved

## Overview

Three enhancements to the Nacre keyboard:

1. **Row 4 redesign** — Add Alt (sticky) and ▲▼ arrow keys, remove Esc and quote
2. **12-key flick input** — Japanese flick input for Z Fold6 folded mode (sub-display)
3. **Tab-based input switching** — Gboard-style tabs for かな/123/#+/emoji on flick layout

## 1. Row 4 (Bottom Modifier Row) Redesign

### Current Layout
```
[Esc][Fn][Shift][Space(3x)]['][Enter(1.5x)]
```

### New Layout
```
[▲|▼][Fn][Shift][Space(2x)][Alt][Enter(1.5x)]
```

### Changes

| Position | Before | After | Notes |
|----------|--------|-------|-------|
| 1 | Esc (1x) | ▲▼ left-right split (1x total) | DPAD_UP/DOWN, each 0.5x width |
| 2 | Fn (1x) | Fn (1x) | Unchanged. Long-press=Esc still works |
| 3 | Shift (1x) | Shift (1x) | Unchanged |
| 4 | Space (3x) | Space (2x) | Reduced. swipeUp=Tab, swipeL/R=ToggleJa unchanged |
| 5 | ' (1x) | Alt (1x) | **New key**. Sticky modifier |
| 6 | Enter (1.5x) | Enter (1.5x) | Unchanged |

### Alt Key Behavior — Sticky Modifier

- **Tap**: Alt state becomes ON (sticky)
- **Status bar**: Shows "Alt" indicator (same style as Shift/Fn indicators)
- **Next key press**: Sends key event with `META_ALT_ON` flag, then auto-resets Alt to OFF
- **Double-tap**: Lock Alt ON (persistent until tapped again) — same as Shift double-tap pattern
- **Use case**: Alt + Enter = newline in terminal apps

### Implementation: KeyAction & LayerManager

```kotlin
// New KeyAction in sealed class KeyAction
data object Alt : KeyAction()

// New KeyAction for flick conversion trigger
data object Henkan : KeyAction()

// LayerManager additions
var isAltActive by mutableStateOf(false)
    private set

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

Note: Double-tap Alt locking is deferred. The current `toggleShift()` is a simple boolean toggle without double-tap detection. Alt follows the same simple sticky pattern: tap ON → next key consumes → OFF.

### Implementation: InputEngine

**Handling `KeyAction.Alt` in `processAction()`** (Critical — exhaustive `when` match):
```kotlin
is KeyAction.Alt -> service.layerManager.toggleAlt()
```

**Handling `KeyAction.Henkan` in `processAction()`:**
```kotlin
is KeyAction.Henkan -> {
    if (composingText.isNotEmpty() || composingKana.isNotEmpty()) {
        if (isConverting) nextCandidate(ic) else startConversion(ic)
    }
}
```

**Applying Alt meta state** to key events (Enter, KeyCode, etc.):
```kotlin
// In processAction for KeyAction.Enter (after existing logic for empty composing):
// In processAction for KeyAction.KeyCode:
// In sendKeyEvent helper:
private fun sendKeyEvent(keyCode: Int) {
    val ic = service.currentInputConnection ?: return
    val altMeta = if (service.layerManager.isAltActive) KeyEvent.META_ALT_ON else 0
    val metaState = altMeta  // combine with ctrl if needed
    ic.sendKeyEvent(KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
        KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
    ic.sendKeyEvent(KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
        KeyEvent.ACTION_UP, keyCode, 0, metaState))
    service.layerManager.consumeAlt()
}
```

**Flick input method** — `processFlickKana()` added to `InputEngine`:
```kotlin
/**
 * Direct kana input from flick keyboard.
 * Bypasses JapaneseEngine (no romaji). Appends kana directly to composingKana.
 * Uses a separate composingFlickKana buffer since composingText is romaji-based.
 */
private var composingFlickKana: String = ""

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

// Reset flick buffer alongside romaji buffer
// In onStartInput(), finishComposing(), clearCandidates():
composingFlickKana = ""
```

### Arrow Keys Implementation

The ▲▼ keys occupy the Esc position, split horizontally into two equal halves:

```kotlin
// In KeymapConfig.kt — these are NOT regular KeyDefs
// They need special rendering (two keys in one slot)
// Option: Use a new ArrowPair compound key type
// Or: Represent as two separate KeyDefs with 0.5x width each

KeyDef("▲", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.5f),
KeyDef("▼", action = KeyAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.5f),
```

### Affected Layouts — Exact New Row 4 Definitions

**`DefaultLayouts.qwertyBase` Row 4:**
```
Before: [Esc][Fn][Shift][Space(3x)]['][Enter(1.5x)]
After:  [▲(0.5x)][▼(0.5x)][Fn][Shift][Space(2x)][Alt][Enter(1.5x)]
```

**`DefaultLayouts.fnLayer1` Row 4:**
```
Before: [Esc][😀][#+][Fn2][Space(2x)][Enter(1.2x)]
After:  [▲(0.5x)][▼(0.5x)][😀][#+][Fn2][Space(1.5x)][Alt][Enter(1.2x)]
```

**`DefaultLayouts.fnLayer2` Row 4:**
```
Before: [Esc][Fn][Shift][Space(3x)]['][Enter(1.5x)]
After:  [▲(0.5x)][▼(0.5x)][Fn][Shift][Space(2x)][Alt][Enter(1.5x)]
```

**`PresetProvider.modifierRow`** (shared by Terminal/Vim/Dvorak/Colemak):
```
Before: [Esc][Fn][Shift][Space(2x)][？][ー]['][あ/A]  (8 keys)
After:  [▲(0.5x)][▼(0.5x)][Fn][Shift][Space(1.5x)][Alt][？][ー][あ/A]  (9 keys)
```
Note: ？ and ー retained for Japanese input. ' removed (available via swipe).

**`PresetProvider.emacsLayout` Row 4:**
```
Before: [Ctrl][Fn][Shift][Space(2x)][Esc][？][Enter][あ/A]  (8 keys)
After:  [▲(0.5x)][▼(0.5x)][Ctrl][Fn][Shift][Space(1.5x)][Alt][Enter][あ/A]  (9 keys)
```
Note: Esc removed (Fn long-press is the Emacs-standard alternative). ？ removed (available on Fn layer).

### Quote Key Relocation

The `'` key (removed from Row 4) is still accessible via:
- Existing swipe: `x` key swipeRight = `'`
- Existing swipe: `c` key swipeUp = `'`
- Fn layer 1: can add to an available slot

## 2. 12-Key Flick Input (Sub-Display)

### Activation Conditions

- Device: Foldable sub-display (`FoldableDetector.isSubDisplay() == true`)
- Mode: Japanese input (`LayerManager.isJapanese == true`)
- When English mode: Falls back to CompactQwerty (existing behavior)

### LayoutSelector Changes

```kotlin
enum class LayoutMode {
    FullVSplit,
    StandardQwerty,
    CompactQwerty,
    QuickInputPad,
    FlickInput12Key,  // NEW
}

fun selectLayout(): LayoutMode {
    val widthDp = detector.getScreenWidthDp()
    return when {
        widthDp >= 500f -> LayoutMode.FullVSplit
        detector.isSubDisplay() -> userSubDisplayMode  // default: FlickInput12Key
        widthDp >= 380f -> LayoutMode.StandardQwerty
        widthDp >= 200f -> LayoutMode.QuickInputPad
        else -> LayoutMode.QuickInputPad
    }
}
```

Default `userSubDisplayMode` changes from `CompactQwerty` to `FlickInput12Key`.

Note: Existing users who previously stored `CompactQwerty` in SharedPreferences will keep their setting. The new default only applies to fresh installs or users who never explicitly set a sub-display preference. The fallback in `loadSubDisplayMode()` should also be updated to `FlickInput12Key`.

### Screen Structure

```
┌─────────────────────────────┐
│  [あ] [123] [#+] [😊]      │  ← Tab bar (Gboard-style)
├─────────────────────────────┤
│  candidates...              │  ← CandidateBar (reuse existing)
├─────────────────────────────┤
│  [あ]  [か]  [さ]          │
│  [た]  [な]  [は]          │  ← 4x3 flick grid
│  [ま]  [や]  [ら]          │
│  [わ]  [゛゜] [⌫]          │
├─────────────────────────────┤
│  [▲▼] [Space] [変換] [Alt] [Enter]  │  ← Bottom row
└─────────────────────────────┘
```

### Tab System

| Tab | Content | Implementation |
|-----|---------|----------------|
| あ | 12-key flick grid | New: FlickInputPad composable |
| 123 | Number pad (1-9, *, 0, #) | New: NumberPad composable (simple) |
| #+ | Symbol grid (flick for alternatives) | New: SymbolFlickPad composable |
| 😊 | Emoji panel | Reuse existing EmojiPanel |

Tab state managed by a simple `mutableStateOf<FlickTab>()` in FlickInputPad.

### Flick Map

| Key | Tap | ← Left | ↑ Up | → Right | ↓ Down |
|-----|-----|--------|------|---------|--------|
| あ | あ | い | う | え | お |
| か | か | き | く | け | こ |
| さ | さ | し | す | せ | そ |
| た | た | ち | つ | て | と |
| な | な | に | ぬ | ね | の |
| は | は | ひ | ふ | へ | ほ |
| ま | ま | み | む | め | も |
| や | や | （ | ゆ | ） | よ |
| ら | ら | り | る | れ | ろ |
| わ | わ | を | ん | ー | 〜 |
| ゛゜ | ゛ | ゜ | 小 | ― | ― |

### Symbol Flick Map (#+  tab)

Each key shows the primary symbol. Flick directions provide alternatives:

| Key | Tap | ← | ↑ | → | ↓ |
|-----|-----|---|---|---|---|
| @ | @ | # | $ | | |
| % | % | & | * | | |
| - | - | + | = | | |
| ( | ( | ) | [ | ] | |
| { | { | } | < | > | |
| / | / | \ | \| | | |
| ! | ! | ? | . | | |
| : | : | ; | , | | |
| ' | ' | " | ` | | |
| ~ | ~ | ^ | _ | | |
| 「 | 「 | 」 | 『 | 』 | |

### Dakuten / Handakuten (゛゜) Key

Modifies the last entered kana:

```kotlin
// FlickEngine.kt
fun applyDakuten(lastKana: Char): Char? = dakutenMap[lastKana]
fun applyHandakuten(lastKana: Char): Char? = handakutenMap[lastKana]
fun applySmall(lastKana: Char): Char? = smallMap[lastKana]

// Maps
val dakutenMap = mapOf(
    'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
    'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
    'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
    'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
    // Katakana counterparts if needed
)
val handakutenMap = mapOf(
    'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ',
)
val smallMap = mapOf(
    'つ' to 'っ', 'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ',
    'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
)
```

### Conversion Pipeline

Flick input produces hiragana directly (no romaji intermediate). The kana feeds into the existing pipeline:

```
Flick → hiragana char → composingKana buffer → CandidateBar predictions
                                              → Space = startConversion (Viterbi)
                                              → 変換 key = same as Space
```

The `変換` key on the bottom row triggers the same `startConversion()` as Space does when composing text exists. Space in flick mode acts as literal space when no composing text.

### Bottom Row (Flick-specific)

```
[▲|▼] [Space(1.5x)] [変換(1x)] [Alt(0.8x)] [Enter(1.2x)]
```

- **▲▼**: Same as QWERTY Row 4 (left-right split, DPAD_UP/DOWN)
- **Space**: Long-press (350ms) = voice input (existing VoiceInputManager)
- **変換**: Triggers conversion. Same as Space-during-composing in QWERTY
- **Alt**: Sticky modifier (same behavior as QWERTY)
- **Enter**: Standard enter

### Voice Input

Space long-press (350ms) activates `VoiceInputManager`:
- Language: `ja-JP` when Japanese, `en-US` when English
- KenLM re-ranking applied to results
- Tap to stop listening
- Reuses all existing voice infrastructure

## 3. Visual Design

### Theme Colors (from ThemeConfig.kt)

All flick keys use the same theme system as QWERTY:

| Property | Dark/AMOLED | Usage |
|----------|-------------|-------|
| background | `#000000` | Keyboard background, key background |
| surface | `#111111` | Keycap bottom edge (mechanical depth) |
| keyBackground | `#000000` | Key face |
| keyBackgroundPressed | `#1A1A1A` | Key face when pressed |
| keyText | `#E0E0E0` | Primary text |
| keyTextSwipe | `#555555` | N/A for flick (no swipe hints shown) |
| accent | `#00D4AA` | Press glow, active tab indicator, status bar |

### Mechanical Keycap Effect

Same as existing KeyView:
- Bottom 1.5dp edge: `surface` color (#111111) for depth illusion
- Press: scale(0.95) with 30ms tween
- Press: accent border glow + text color change to accent + text shadow
- Key shape: RoundedCornerShape(6.dp)

### Key Lighting

FlickInputPad passes `(keyId, row, column)` to `KeyLighting.getKeyColor()`. All existing modes work unchanged:
- **Reactive**: Tap a flick key → accent glow fades over 400ms
- **Wave**: HSV hue sweep across columns (3s period)
- **Breathing**: Global pulse (4s period)
- **Heatmap**: Per-key heat tracking
- **Matrix**: Column drip effect (green)
- **Static**: Uniform accent glow

WASD highlight is N/A for flick mode (only applies to QWERTY letter keys).

### Flick Popup

When user flicks, show a Gboard-style popup bubble displaying the selected kana:
- Same implementation as existing long-press popup in KeyView
- 48x48dp bubble above the key
- Background: `#3A3A6A`, text: white, fontSize: 22sp
- Duration: shown while finger is held, disappears on release

## 4. New Files

| File | Module | Description |
|------|--------|-------------|
| `FlickEngine.kt` | ime-core/input | Flick direction → kana mapping, dakuten/handakuten/small |
| `FlickInputPad.kt` | ime-core/keyboard | Main composable: tab bar + flick grid + bottom row |
| `NumberPad.kt` | ime-core/keyboard | Simple 4x3 number grid composable |
| `SymbolFlickPad.kt` | ime-core/keyboard | Symbol grid with flick alternatives |

## 5. Modified Files

| File | Changes |
|------|---------|
| `KeymapConfig.kt` | Add `KeyAction.Alt`. Update `qwertyBase` Row 4, `fnLayer1` Row 4, `fnLayer2` Row 4 |
| `PresetProvider.kt` | Update `modifierRow`. Update Emacs custom Row 4 |
| `LayerManager.kt` | Add `isAltActive`, `isAltLocked`, `toggleAlt()`, `consumeAlt()` |
| `InputEngine.kt` | Add `KeyAction.Alt` and `KeyAction.Henkan` to `processAction()` `when` block. Modify `sendKeyEvent()` to apply Alt meta state. Add `processFlickKana()` and `processFlickDakuten()` methods with separate `composingFlickKana` buffer (bypasses romaji `composingText`). Reset `composingFlickKana` in `onStartInput()`, `finishComposing()`, `clearCandidates()`. |
| `KeyboardScreen.kt` | Route `FlickInput12Key` layout mode to `FlickInputPad`. **Convert `else` catch-all to exhaustive `when`** to prevent silent fallthrough for new enum values. Add `is KeyAction.Alt -> "Alt"` to StatusBar semantics. Show "Alt" indicator in StatusBar when `layerManager.isAltActive`. |
| `LayoutSelector.kt` | Add `FlickInput12Key` enum. Change default sub-display mode |
| `KeyView.kt` | Handle `KeyAction.Alt` display (show "Alt" label, accent color when active). Add `is KeyAction.Alt -> "Alt"` to semantics `contentDescription` block. |
| `StatusBar` (in KeyboardScreen.kt) | Show "Alt" indicator when active |

## 6. Not Changed

- CandidateBar, EmojiPanel, SymbolsPanel — reused as-is
- VoiceInputManager — reused, triggered by Space long-press
- KeyLighting — reused, receives (keyId, row, col) from flick keys
- JapaneseEngine — not invoked by flick path (flick produces kana directly), but remains available if user switches to QWERTY mid-session
- DictionaryProvider / NacreDictionary — reused for predictions and conversion
- VSplitLayout — unaffected (large screen only)
- ThemeConfig — no changes, flick reads same theme
- FoldableDetector — no changes
