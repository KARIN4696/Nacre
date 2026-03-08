package space.manus.nacre.ime.foldable

/**
 * Available keyboard layout modes for different screen sizes and form factors.
 */
enum class LayoutMode {
    /** V-split keyboard for tablets and large main displays (>= 500dp). */
    FullVSplit,

    /** Standard QWERTY layout for normal phone screens (>= 380dp). */
    StandardQwerty,

    /** Compact QWERTY for smaller screens or foldable sub-displays. */
    CompactQwerty,

    /** Minimal macro pad for very small sub-displays (e.g. Z Flip cover). */
    QuickInputPad,
}

/**
 * Selects the appropriate keyboard layout based on screen dimensions
 * and foldable device state.
 */
class LayoutSelector(private val detector: FoldableDetector) {

    /**
     * User-selected preferred layout for the sub-display.
     * When set, overrides the automatic selection for foldable sub-displays.
     */
    var userSubDisplayMode: LayoutMode = LayoutMode.CompactQwerty

    /**
     * Determines the best layout mode for the current screen configuration.
     *
     * Decision tree:
     *  - widthDp >= 500  -> FullVSplit (tablet / unfolded main display)
     *  - foldable sub    -> user preference (default CompactQwerty)
     *  - widthDp >= 380  -> StandardQwerty (normal phone)
     *  - widthDp >= 200  -> QuickInputPad (very small sub-display)
     *  - else            -> QuickInputPad (fallback)
     */
    fun selectLayout(): LayoutMode {
        val widthDp = detector.getScreenWidthDp()

        return when {
            widthDp >= 500f -> LayoutMode.FullVSplit
            detector.isSubDisplay() -> userSubDisplayMode
            widthDp >= 380f -> LayoutMode.StandardQwerty
            widthDp >= 200f -> LayoutMode.QuickInputPad
            else -> LayoutMode.QuickInputPad
        }
    }
}
