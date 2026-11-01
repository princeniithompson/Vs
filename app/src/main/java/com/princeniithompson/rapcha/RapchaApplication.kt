package com.princeniithompson.rapcha

import android.app.Application
import android.content.Context
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.CrashReportStore
import com.princeniithompson.rapcha.diagnostics.ProcessExitDiagnostics

class RapchaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        AppLogRepository.init(this)
        com.princeniithompson.rapcha.data.RetryClipStore.init(this)
        publishPendingCrash(this)
        ProcessExitDiagnostics.recordHistoricalExits(this)
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
