package com.eeinspired.mantel

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.eeinspired.mantel.data.AppScope
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.telemetry.Telemetry
import com.eeinspired.mantel.ui.gallery.NextcloudImageLoader
import com.eeinspired.mantel.upload.UploadEnqueuer
import kotlinx.coroutines.launch

/**
 * Process-level setup. Runs in every process start — including ones WorkManager spins up
 * for a retry with no Activity — so telemetry and the resolved server host are always ready.
 */
class MantelApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
        Config.initialize(this) // resolve the server host before any network use
        AppScope.io.launch { UploadEnqueuer.sweep(this@MantelApp) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = NextcloudImageLoader.create(context)
}
