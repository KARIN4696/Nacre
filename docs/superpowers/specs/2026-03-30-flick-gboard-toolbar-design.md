# Flick Gboard Layout + Toolbar + Clipboard Enhancement

**Date:** 2026-03-30
**Status:** Approved

## Overview

Six changes to the Nacre keyboard:

1. **12-key Gboard-style 5-column layout** — Replace 3-column grid with Gboard's 5-column arrangement
2. **わ行にー追加** — Right flick direction
3. **Unified toolbar/candidate bar** — Both QWERTY and 12-key share toolbar↔candidate switching
4. **White line SVG toolbar icons** — Clipboard, Voice, Emoji, Settings
5. **Clipboard history (5 items)** — SharedPreferences-backed clipboard panel
6. **QWERTY Row 4 trackball split** — Left: Shift/↑/↓/Fn | Right: Space(big)/Alt/Enter

## 1. 12-Key Gboard 5-Column Layout

### New Grid (replaces current 3-column)

```
Row 1: [↩(0.8x)]  [あ]  [か]  [さ]  [⌫(0.8x)]
Row 2: [◀(0.8x)]  [た]  [な]  [は]  [▶(0.8x)]
Row 3: [☺記(0.8x)] [ま]  [や]  [ら]  [␣(0.8x)]
Row 4: [あa1(0.8x)] [゛゜大⇔小] [わ] [？。！] [↵(1.2x)]
```

### Side Key Functions

| Key | Action |
|-----|--------|
| ↩ | Cancel conversion / undo last kana |
| ⌫ | Backspace (long-press repeat) |
| ◀ ▶ | Cursor left / right (DPAD_LEFT / DPAD_RIGHT) |
| ☺記 | Open emoji panel |
| ␣ | Space (long-press = voice input) |
| あa1 | Toggle: Japanese flick → CompactQwerty → Numbers (cycle) |
| ？。！ | Tap=？ / Flick: 。！… |
| ↵ | Enter (Gboard-compatible performEditorAction) |

### Modified File

`FlickInputPad.kt` — Replace `FlickKanaGrid` 3-column with 5-column. Side keys are standard `KeyView` using existing `KeyAction` types. Remove separate `FlickBottomRow` — Enter is now in the grid.

### Bottom Row (simplified)

With Enter now in the grid, the bottom row becomes:
```
[↑(0.5x)] [↓(0.5x)] [変換] [Alt]
```

## 2. わ行フリック更新

### Before
```
わ: Tap=わ Left=を Up=ん Right=ー Down=〜
```

Already has ー on Right. No change needed — already in FlickEngine.

## 3. Unified Toolbar / Candidate Bar

### Behavior

The same position at the top of the keyboard shows either:
- **Toolbar** — when `composingKana.isEmpty() && candidates.isEmpty()`
- **CandidateBar** — when composing or candidates exist

### Implementation

Replace the current `CandidateBar(service)` call in both `StandardKeyboardScreen` and `FlickInputPad` with a new `ToolbarOrCandidateBar(service)` composable.

```kotlin
@Composable
fun ToolbarOrCandidateBar(service: NacreInputMethodService) {
    val hasContent = service.inputEngine.composingKana.isNotEmpty() ||
        service.inputEngine.candidates.isNotEmpty()

    if (hasContent) {
        CandidateBar(service = service)
    } else {
        Toolbar(service = service)
    }
}
```

### Toolbar Composable

```kotlin
@Composable
private fun Toolbar(service: NacreInputMethodService) {
    Row(modifier = Modifier.fillMaxWidth().height(36.dp).background(bgColor),
        horizontalArrangement = Arrangement.Center) {
        ToolbarIcon(icon = ClipboardIcon) { /* open clipboard panel */ }
        ToolbarIcon(icon = VoiceIcon) { /* start voice input */ }
        ToolbarIcon(icon = EmojiIcon) { /* open emoji panel */ }
        ToolbarIcon(icon = SettingsIcon) { /* open settings */ }
    }
}
```

### Icon Style

White line SVGs rendered with Compose Canvas or `painterResource`. All icons:
- Stroke color: `#AAAAAA`
- Stroke width: 1.8dp
- Fill: none (except filled dots for emoji eyes)
- Size: 18x18dp inside 40x30dp touch target

### New File

`Toolbar.kt` — Contains `ToolbarOrCandidateBar`, `Toolbar`, `ToolbarIcon`, icon drawing functions.

## 4. Clipboard History (5 items)

### Storage

SharedPreferences key: `"nacre_clipboard_history"` — JSON array of strings, max 5 items.

### Capture

In `NacreInputMethodService`, register `ClipboardManager.OnPrimaryClipChangedListener`:
- On clip change: prepend to history, cap at 5, persist
- Skip duplicates (don't re-add if already most recent)

### UI

`ClipboardPanel.kt` already exists. Modify to show history list instead of just current clip. Tap an item → paste via `ic.commitText()`.

### New File

`ClipboardHistory.kt` — Manages the 5-item clipboard ring buffer with SharedPreferences.

## 5. QWERTY Row 4 Trackball Split

### Current Row 4 (single row, no trackball alignment)
```
[Shift][↑(0.5x)][↓(0.5x)][Fn][Space(2x)][Alt][Enter(1.5x)]
```

### New Row 4 (trackball split — matches Row 2-3)

Row 4 uses `KeyRowWithTrackball` with `showTrackball = false` (empty gap where trackball sits on Row 2-3):

```
Left half:  [Shift(1x)] [↑(0.5x)] [↓(0.5x)] [Fn(0.8x)]
            |  gap  |
Right half: [Space(2.5x)] [Alt(0.8x)] [Enter(1.2x)]
```

### Implementation

In `KeymapConfig.kt`, Row 4 must have an even number of keys for `KeyRowWithTrackball`'s `mid = keys.size / 2` split. Current proposed: 4 left + 3 right = 7 keys (odd). Need to balance.

Solution: Use 4 left + 4 right = 8 keys. Add a thin spacer key or adjust widths:
```
Left:  [Shift(1x)] [↑(0.5x)] [↓(0.5x)] [Fn(0.8x)]     = 4 keys
Right: [Space(2x)]  [Alt(0.8x)] [Enter(1.2x)] [GL(0.5x)] = 4 keys
```

Or keep 4+3 and handle odd count in `KeyRowWithTrackball` by using `mid = 4` explicitly. Simpler: make Row 4 use `rowIndex = 3` which triggers `KeyRowWithTrackball` with `showTrackball = false`.

### Modified Files

- `KeymapConfig.kt` — Update qwertyBase Row 4 key arrangement
- `KeyboardScreen.kt` — Ensure Row 4 (index 3) goes through `KeyRowWithTrackball`
- `PresetProvider.kt` — Update modifierRow and Emacs Row 4

## 6. Modified Files Summary

| File | Changes |
|------|---------|
| `FlickInputPad.kt` | Rewrite to 5-column Gboard grid, remove FlickBottomRow, simplify bottom to ↑↓/変換/Alt |
| `FlickEngine.kt` | No change (わ already has ー) |
| `KeyboardScreen.kt` | Replace `CandidateBar` with `ToolbarOrCandidateBar`. Row 4 trackball split. |
| `KeymapConfig.kt` | Restructure qwertyBase Row 4 for trackball split (4+4 even split) |
| `PresetProvider.kt` | Update modifierRow and Emacs Row 4 for trackball split |

## 7. New Files

| File | Description |
|------|-------------|
| `Toolbar.kt` | ToolbarOrCandidateBar, Toolbar, white-line icon composables |
| `ClipboardHistory.kt` | 5-item clipboard ring buffer with SharedPreferences persistence |
