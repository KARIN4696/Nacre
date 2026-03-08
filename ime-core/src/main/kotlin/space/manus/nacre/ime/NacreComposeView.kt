package space.manus.nacre.ime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A ComposeView that installs ViewTree owners on its entire parent chain
 * when attached to the window. This is necessary for InputMethodService
 * because the system-provided parent (android:id/parentPanel LinearLayout)
 * does not have ViewTreeLifecycleOwner set, causing ComposeView to crash
 * with IllegalStateException.
 */
class NacreComposeView(
    context: Context,
    private val service: NacreInputMethodService,
) : ComposeView(context) {

    override fun onAttachedToWindow() {
        // Walk up the parent chain and set ViewTree owners on every ViewGroup.
        // This ensures ComposeView's resolveParentCompositionContext() can
        // find a LifecycleOwner regardless of which ancestor it checks.
        var parent = parent
        while (parent is View) {
            (parent as View).setViewTreeLifecycleOwner(service)
            (parent as View).setViewTreeViewModelStoreOwner(service)
            (parent as View).setViewTreeSavedStateRegistryOwner(service)
            parent = (parent as View).parent
        }

        super.onAttachedToWindow()
    }
}
