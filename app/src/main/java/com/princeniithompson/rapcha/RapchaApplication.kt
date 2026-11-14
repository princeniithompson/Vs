package com.princeniithompson.rapcha

import android.app.Application
import android.content.Context
import android.util.Log
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.CrashReportStore
import com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics
import com.princeniithompson.rapcha.diagnostics.ProcessExitDiagnostics
import java.util.concurrent.atomic.AtomicBoolean

class RapchaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // v24: decide Lite mode before anything heavy starts; everything below is gated on it.
        com.princeniithompson.rapcha.core.LiteMode.decide(this)
        com.princeniithompson.rapcha.core.ApiKeyVault.init(this)
        // v23: everything else (log file load, device/network diagnostics, retry-clip repair,
        // crash publish, exit-reason scan) runs once, on one background thread.
        ensureBackgroundInit(this)
        if (com.princeniithompson.rapcha.service.FloatingBubbleManager.isBubbleEnabledSync(this) &&
            com.princeniithompson.rapcha.service.FloatingBubbleService.instance == null &&
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.lastStopReason(this) ==
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_UNEXPECTED &&
            com.princeniithompson.rapcha.service.FloatingBubbleManager.canDrawOverlays(this)
        ) {
            // v23: Application.onCreate is almost always a background start (the system is
            // re-creating the process for the accessibility service or a sticky restart), so a
            // startForegroundService here is refused on Android 12+. Ask the recovery job instead;
            // the accessibility service binds the bubble as soon as it reconnects.
            com.princeniithompson.rapcha.service.BubbleRecoveryJobScheduler.schedule(
                this,
                com.princeniithompson.rapcha.service.RecoveryJobPolicy.MIN_LATENCY_MS
            )
        }
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashReportStore.write(filesDir, thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "RapchaApplication"
        private val backgroundInitStarted = AtomicBoolean(false)
        private val renderBudgetInitialized = AtomicBoolean(false)

        /**
         * Idempotent, process-wide startup. Safe to call from Application, services and the
         * activity. Only tiny work happens on the caller thread.
         */
        fun ensureBackgroundInit(context: Context) {
            val app = context.applicationContext ?: context
            val lite = com.princeniithompson.rapcha.core.LiteMode.decide(app)
            // Cheap and needed before the first overlay frame so low-end phones never start in
            // full-glow mode. Also binds AppLogRepository to the context (no disk I/O).
            AppLogRepository.init(app)
            if (renderBudgetInitialized.compareAndSet(false, true)) {
                try {
                    com.princeniithompson.rapcha.service.floating.RenderBudget.init(app)
                } catch (t: Throwable) {
                    renderBudgetInitialized.set(false)
                    Log.w(TAG, "RenderBudget init failed", t)
                }
            }
            if (!backgroundInitStarted.compareAndSet(false, true)) return
            val worker = Thread({
                // v24 Lite: no network/device monitoring and no exit-reason scan (diagnostics only).
                if (!lite) runStep("network_diagnostics") { NetworkAndDeviceDiagnostics.startMonitoring(app) }
                runStep("retry_clips") { com.princeniithompson.rapcha.data.RetryClipStore.init(app) }
                runStep("crash_publish") { publishPendingCrash(app) }
                if (!lite) runStep("exit_reasons") { ProcessExitDiagnostics.recordHistoricalExits(app) }
            }, "rapcha-startup-init")
            worker.priority = Thread.NORM_PRIORITY - 1
            worker.isDaemon = true
            worker.start()
        }

        private inline fun runStep(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "Startup step $name failed", t)
                AppLogRepository.logEvent(
                    DiagnosticSource.APP,
                    DiagnosticType.WARNING,
                    "startup_step_failed step=$name kind=${t.javaClass.simpleName} detail=${t.message?.take(120)}"
                )
            }
        }

        fun publishPendingCrash(context: Context) {
            val report = CrashReportStore.consume(context.filesDir) ?: return
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.CRASH,
                report.take(MAX_CRASH_CHARS)
            )
        }

        private const val MAX_CRASH_CHARS = 4000
    }
}
