package com.example.nourio.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The steps of a meal analysis, in order. */
enum class AiStage(val label: String) {
    PREPARING("Preparing"),
    ASKING("Asking the AI"),
    NUTRITION("Looking up nutrition"),
    DONE("Done"),
}

/**
 * What the AI request on screen is doing right now: which service, model and key, which step, and what
 * happened on the way (a busy model, a switched key). Keys appear only as "#2 (…a1B9)".
 */
data class AiStatus(
    val stage: AiStage = AiStage.PREPARING,
    val provider: String = "",
    val model: String? = null,
    val key: String? = null,
    val attempt: Int = 0,
    val foodsDone: Int = 0,
    val foodsTotal: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val stageStartedAt: Long = System.currentTimeMillis(),
    val events: List<String> = emptyList(),
)

object AiProgress {
    private val _status = MutableStateFlow<AiStatus?>(null)
    val status: StateFlow<AiStatus?> = _status.asStateFlow()

    /** Starts a new run; earlier events are cleared. */
    fun begin(stage: AiStage = AiStage.PREPARING) {
        _status.value = AiStatus(stage = stage, provider = AiSettings.provider.label)
    }

    fun stage(s: AiStage) = _status.update { it?.copy(stage = s, stageStartedAt = System.currentTimeMillis()) }

    /** A request is about to go to [model] with key [key] (masked). */
    fun trying(model: String?, key: String?) = _status.update {
        (it ?: AiStatus(stage = AiStage.ASKING, provider = AiSettings.provider.label))
            .copy(model = model, key = key, attempt = (it?.attempt ?: 0) + 1)
    }

    /** Something worth showing: a busy model, a key switched, a wait before retrying. Keeps the last 6. */
    fun event(text: String) = _status.update { it?.copy(events = (it.events + text).takeLast(6)) }

    fun foods(done: Int, total: Int) = _status.update { it?.copy(foodsDone = done, foodsTotal = total) }

    fun end() { _status.value = null }

    /** "#2 (…a1B9)": enough to tell keys apart, never the key itself. */
    fun maskKey(index: Int, key: String): String = "#${index + 1} (…${key.takeLast(4)})"
}
