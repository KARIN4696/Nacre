package space.manus.nacre.ime

import android.content.res.Configuration
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
import space.manus.nacre.config.ConfigRepository
import space.manus.nacre.ime.feedback.FeedbackManager
import space.manus.nacre.ime.foldable.FoldableDetector
import space.manus.nacre.ime.foldable.LayoutSelector
import space.manus.nacre.ime.input.AutoConvertEngine
import space.manus.nacre.ime.input.ClipboardManager
import space.manus.nacre.ime.input.InputEngine
import space.manus.nacre.ime.input.LayerManager
import space.manus.nacre.ime.input.MacroEngine
import space.manus.nacre.ime.input.NacreDictionary
import space.manus.nacre.ime.input.PhysicalKeyboardDetector
import space.manus.nacre.ime.input.SnippetEngine
import space.manus.nacre.ime.keyboard.KeyboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // --- Core components ---
    lateinit var inputEngine: InputEngine
        private set
    val layerManager = LayerManager()

    // --- Phase 2+ components ---
    lateinit var feedbackManager: FeedbackManager
        private set
    lateinit var clipboardManager: ClipboardManager
        private set
    lateinit var macroEngine: MacroEngine
        private set
    lateinit var snippetEngine: SnippetEngine
        private set
    lateinit var autoConvertEngine: AutoConvertEngine
        private set
    lateinit var configRepository: ConfigRepository
        private set
    lateinit var foldableDetector: FoldableDetector
        private set
    lateinit var layoutSelector: LayoutSelector
        private set
    lateinit var physicalKeyboardDetector: PhysicalKeyboardDetector
        private set

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Initialize all components
        configRepository = ConfigRepository(this)
        inputEngine = InputEngine(this)
        feedbackManager = FeedbackManager(this)
        clipboardManager = ClipboardManager(this)
        macroEngine = MacroEngine(this)
        snippetEngine = SnippetEngine(this)
        autoConvertEngine = AutoConvertEngine(this)
        foldableDetector = FoldableDetector(this)
        layoutSelector = LayoutSelector(foldableDetector)
        physicalKeyboardDetector = PhysicalKeyboardDetector(this)

        clipboardManager.startListening()
        foldableDetector.startHingeAngleListening()

        // Load dictionary in background, publish on Main
        serviceScope.launch(Dispatchers.IO) {
            val dict = NacreDictionary(this@NacreInputMethodService)
            dict.load()
            withContext(Dispatchers.Main) {
                inputEngine.dictionary = dict
            }
        }
    }

    override fun onCreateInputView(): View {
        // Cache ComposeView — don't recreate every time
        composeView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Recreate ComposeView on configuration change (theme, density, screen size)
        composeView = null
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        clipboardManager.stopListening()
        foldableDetector.stopHingeAngleListening()
        (inputEngine.dictionary as? NacreDictionary)?.flushPendingSave()
        macroEngine.saveMacros(this)
        snippetEngine.saveSnippets(this)
        autoConvertEngine.saveRules(this)
        feedbackManager.release()
        serviceScope.cancel()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        composeView = null
        super.onDestroy()
    }
}
