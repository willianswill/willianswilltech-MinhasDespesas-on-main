package com.willianswill.novatv.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.Process

/**
 * Verificações rápidas de integridade em tempo de execução contra clonagem de pacote
 * e depuração indevida em builds de produção.
 */
object AppIntegrityGuard {

    private const val EXPECTED_PACKAGE = "com.willianswill.novatv"

    fun verifyOrTerminate(context: Context) {
        if (context.packageName != EXPECTED_PACKAGE) {
            Process.killProcess(Process.myPid())
            return
        }
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable && (Debug.isDebuggerConnected() || Debug.waitingForDebugger())) {
            Process.killProcess(Process.myPid())
        }
    }
}
