package space.manus.nacre.ime

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
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
import space.manus.nacre.ime.input.VoiceInputManager
import space.manus.nacre.ime.keyboard.KeyLighting
import space.manus.nacre.ime.keyboard.KeyboardScreen
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    private var inputViewContainer: FrameLayout? = null
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
    lateinit var voiceInputManager: VoiceInputManager
        private set
    lateinit var keyLighting: KeyLighting
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
        voiceInputManager = VoiceInputManager(this)
        keyLighting = KeyLighting(this)

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
        // Cache — don't recreate every time
        inputViewContainer?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        // Advance lifecycle so ComposeView can start composition.
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        return try {
            val service = this
            // FrameLayout wrapper: when attached to the IME window, walk up
            // the parent chain and stamp ViewTree owners on every ancestor
            // BEFORE adding the ComposeView. ComposeView resolves owners in
            // its own onAttachedToWindow, so the owners must already be on
            // the parent chain at that point.
            val container = object : FrameLayout(service) {
                override fun onAttachedToWindow() {
                    // First, set owners on ourselves
                    setViewTreeLifecycleOwner(service)
                    setViewTreeViewModelStoreOwner(service)
                    setViewTreeSavedStateRegistryOwner(service)
                    // Then propagate up the entire parent chain
                    var p = parent
                    while (p is View) {
                        p.setViewTreeLifecycleOwner(service)
                        p.setViewTreeViewModelStoreOwner(service)
                        p.setViewTreeSavedStateRegistryOwner(service)
                        p = p.parent
                    }
                    super.onAttachedToWindow()
                    // NOW it is safe to add ComposeView — all ancestors have owners
                    post {
                        if (childCount == 0) {
                            val composeView = ComposeView(context).apply {
                                setContent {
                                    KeyboardScreen(service = service)
                                }
                            }
                            addView(
                                composeView,
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                            )
                        }
                    }
                }
            }
            container.setViewTreeLifecycleOwner(this)
            container.setViewTreeViewModelStoreOwner(this)
            container.setViewTreeSavedStateRegistryOwner(this)

            inputViewContainer = container
            container
        } catch (e: Exception) {
            Log.e("NacreIME", "Failed to create input view", e)
            android.widget.TextView(this).apply {
                text = "Nacre: keyboard load error"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputEngine.onStartInput(info)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // SPEC: 300-500ms delay for foldable screen size stabilization
        serviceScope.launch {
            delay(350L)
            inputViewContainer?.let { container ->
                (container.parent as? ViewGroup)?.removeView(container)
                container.removeAllViews()
            }
            inputViewContainer = null
            setInputView(onCreateInputView())
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        inputEngine.destroy()
        clipboardManager.stopListening()
        foldableDetector.stopHingeAngleListening()
        (inputEngine.dictionary as? NacreDictionary)?.flushPendingSave()
        macroEngine.saveMacros(this)
        snippetEngine.saveSnippets(this)
        autoConvertEngine.saveRules(this)
        feedbackManager.release()
        voiceInputManager.release()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        inputViewContainer = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
