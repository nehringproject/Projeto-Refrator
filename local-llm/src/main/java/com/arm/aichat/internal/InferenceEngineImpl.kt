package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Serializes llama.cpp access on a dedicated dispatcher. */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: run {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    require(nativeLibDir.isNotBlank()) { "Native library path is unavailable." }
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                }
            }
    }

    @FastNative
    private external fun init(nativeLibDir: String)

    @FastNative
    private external fun load(modelPath: String): Int

    @FastNative
    private external fun prepare(contextSize: Int): Int

    @FastNative
    private external fun systemInfo(): String

    @FastNative
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    @FastNative
    private external fun processSystemPrompt(systemPrompt: String): Int

    @FastNative
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    @FastNative
    private external fun generateNextToken(): String?

    @FastNative
    private external fun unload()

    @FastNative
    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    private var _nativeRuntimeInitialized = false
    private var _nativeModelLoaded = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _nativeRuntimeInitialized = true
                _state.value = InferenceEngine.State.Initialized
            } catch (failure: Throwable) {
                val exception = failure as? Exception
                    ?: IllegalStateException("Native runtime initialization failed.", failure)
                Log.e(TAG, "Native runtime initialization failed: ${failure.javaClass.simpleName}")
                _state.value = InferenceEngine.State.Error(exception)
            }
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String, contextSize: Int) =
        withContext(llamaDispatcher) {
            require(contextSize in 1_024..65_536) { "Invalid context size: $contextSize" }
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel).let {
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                _nativeModelLoaded = true
                prepare(contextSize).let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed: ${e.javaClass.simpleName}")
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    override suspend fun setSystemPrompt(systemPrompt: String) =
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            try {
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.ProcessingSystemPrompt
                processSystemPrompt(systemPrompt).let { result ->
                    if (result != 0) {
                        throw IOException("Native system prompt processing failed with code $result.")
                    }
                }
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, "System prompt processing failed: ${e.javaClass.simpleName}")
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            _cancelGeneration = false
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    throw IOException("Native user prompt processing failed with code $result.")
                }
            }

            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.javaClass.simpleName}")
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    override fun cancelGeneration() {
        _cancelGeneration = true
    }

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            runCatching { benchModel(pp, tg, pl, nr) }.onSuccess {
                _state.value = InferenceEngine.State.ModelReady
            }.getOrElse { failure ->
                val exception = failure as? Exception ?: IllegalStateException("Benchmark failed.", failure)
                _state.value = InferenceEngine.State.Error(exception)
                throw exception
            }
        }

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()
                    _nativeModelLoaded = false

                    _state.value = InferenceEngine.State.Initialized
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    if (!_nativeRuntimeInitialized) throw state.exception
                    if (_nativeModelLoaded) {
                        unload()
                        _nativeModelLoaded = false
                    }
                    _state.value = InferenceEngine.State.Initialized
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            if (_nativeModelLoaded) {
                unload()
                _nativeModelLoaded = false
            }
            if (_nativeRuntimeInitialized) {
                shutdown()
                _nativeRuntimeInitialized = false
            }
        }
        llamaScope.cancel()
        synchronized(Companion) {
            if (instance === this) instance = null
        }
    }
}
