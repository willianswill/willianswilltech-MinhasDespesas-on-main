package com.willianswill.novatv

import androidx.multidex.MultiDexApplication
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.willianswill.novatv.core.network.HttpClientProvider
import java.io.InputStream

class NovaTvApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Faz o Glide usar o mesmo OkHttpClient resiliente (SSL IPTV + Redirects + IPv4/DoH)
        Glide.get(this).registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(HttpClientProvider.sharedClient)
        )
    }
}
