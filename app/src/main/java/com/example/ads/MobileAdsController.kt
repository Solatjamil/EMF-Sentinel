package com.example.ads

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concurrency-safe, once-per-process initializer for the Google Mobile Ads (Legacy) SDK.
 *
 * Callers MUST gate on [AdConsentManager.canRequestAds] before calling [initialize]:
 * the SDK starts pre-loading ads during initialization, so initializing before consent
 * would violate UMP policy. Initialization runs on a background thread to avoid
 * blocking app startup (recommended by the integration guide).
 */
object MobileAdsController {

    private const val TAG = "MobileAdsController"

    private val initialized = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)

    val isInitialized: Boolean
        get() = initialized.get()

    /**
     * Initializes the GMA SDK exactly once. Safe to call repeatedly — extra calls are no-ops.
     * Never holds an Activity reference; pass an application context.
     */
    fun initialize(applicationContext: Context) {
        if (initialized.get()) return
        if (!initializing.compareAndSet(false, true)) return

        Thread({
            try {
                // General-audience request configuration. If the app's audience includes
                // children, set maxAdContentRating and child-directed treatment here BEFORE
                // initialization (and mirror it in AdConsentManager audience flags).
                MobileAds.setRequestConfiguration(RequestConfiguration.Builder().build())

                MobileAds.initialize(applicationContext) { status ->
                    initialized.set(true)
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "GMA SDK initialized. Adapters: " + status.adapterStatusMap.keys.joinToString())
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "GMA SDK initialization failed", t)
                initializing.set(false) // allow a later retry after transient failure
            }
        }, "gma-init").start()
    }
}
