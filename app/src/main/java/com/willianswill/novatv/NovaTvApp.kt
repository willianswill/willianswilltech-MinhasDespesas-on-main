package com.willianswill.novatv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.multidex.MultiDexApplication
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.willianswill.novatv.core.network.HttpClientProvider
import com.willianswill.novatv.core.network.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.lang.ref.WeakReference

class NovaTvApp : MultiDexApplication() {

    private var currentActivityRef: WeakReference<Activity>? = null
    private var isDialogShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val commandPollRunnable: Runnable = object : Runnable {
        override fun run() {
            val self = this
            val act = currentActivityRef?.get()
            if (act != null && !act.isFinishing && !act.isDestroyed && !isDialogShowing) {
                CoroutineScope(Dispatchers.Main).launch {
                    val cmd = TelemetryManager.checkCommandNow(this@NovaTvApp)
                    if (cmd != null && cmd.action != "NONE") {
                        val activeNow = currentActivityRef?.get()
                        if (activeNow != null && !activeNow.isFinishing && !activeNow.isDestroyed && !isDialogShowing) {
                            if (cmd.action == "BLOCK") isDialogShowing = true
                            TelemetryManager.handleCommandOnActivity(activeNow, cmd) {
                                isDialogShowing = false
                                mainHandler.post(self)
                            }
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.willianswill.novatv.core.security.AppIntegrityGuard.verifyOrTerminate(this)
        // Faz o Glide usar o imageClient resiliente (User-Agent navegador + SSL IPTV + Redirects + IPv4/DoH)
        Glide.get(this).registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(HttpClientProvider.imageClient)
        )

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                mainHandler.removeCallbacks(commandPollRunnable)
                mainHandler.postDelayed(commandPollRunnable, 1_500L)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    currentActivityRef = null
                    mainHandler.removeCallbacks(commandPollRunnable)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
