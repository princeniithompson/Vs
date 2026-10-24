package com.princeniithompson.rapcha.service

/**
 * Runs app detection and Safe Mode off the main thread.
 * Keyboard handling must not wait on this. Repeat events for the same
 * package and window reuse the cached result.
 */
class ForegroundContextScheduler(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val execute: (Runnable) -> Unit,
    private val publishOnMain: (Runnable) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val evaluate: (packageName: String, className: String?, windowTitle: String?) -> Evaluation
) {
    data class Evaluation(val sensitive: Boolean, val appName: String, val category: String)

    private var pending: Runnable? = null
    private var lastKey: String? = null
    private val cache = HashMap<String, Evaluation>()

    fun submit(packageName: String?, className: String?, windowTitle: String?) {
        if (packageName.isNullOrBlank()) return
        val key = "$packageName|${className.orEmpty()}|${windowTitle.orEmpty()}"
        if (key == lastKey) return
        val cached = cache[key]
        if (cached != null) {
            lastKey = key
            publishOnMain(Runnable { onResult?.invoke(packageName, cached) })
            return
        }
        pending?.let(cancel)
        val task = Runnable {
            execute(Runnable {
                val evaluation = try {
                    evaluate(packageName, className, windowTitle)
                } catch (_: Throwable) {
                    Evaluation(sensitive = false, appName = packageName, category = "Other")
                }
                cache[key] = evaluation
                publishOnMain(Runnable {
                    lastKey = key
                    onResult?.invoke(packageName, evaluation)
                })
            })
        }
        pending = task
        postDelayed(task, debounceMs)
    }

    var onResult: ((packageName: String, evaluation: Evaluation) -> Unit)? = null

    fun cached(packageName: String, className: String?, windowTitle: String?): Evaluation? {
        return cache["$packageName|${className.orEmpty()}|${windowTitle.orEmpty()}"]
    }

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}
