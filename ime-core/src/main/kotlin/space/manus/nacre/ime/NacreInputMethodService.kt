package space.manus.nacre.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import space.manus.nacre.ime.input.InputEngine
import space.manus.nacre.ime.input.LayerManager
import space.manus.nacre.ime.input.NacreDictionary
import space.manus.nacre.ime.keyboard.KeyboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NacreInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var composeView: ComposeView? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var inputEngine: InputEngine
        private set
    val layerManager = LayerManager()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        inputEngine = InputEngine(this)

        // Load dictionary in background
        serviceScope.launch(Dispatchers.IO) {
            val dict = NacreDictionary(this@NacreInputMethodService)
            dict.load()
            inputEngine.dictionary = dict
        }
    }

    override fun onCreateInputView(): View {
        // Cache ComposeView — don't recreate every time
        composeView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@NacreInputMethodService)
            setViewTreeViewModelStoreOwner(this@NacreInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@NacreInputMethodService)
            setContent {
                KeyboardScreen(service = this@NacreInputMethodService)
            }
        }
        composeView = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputEngine.onStartInput(info)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        composeView = null
        super.onDestroy()
    }
}
