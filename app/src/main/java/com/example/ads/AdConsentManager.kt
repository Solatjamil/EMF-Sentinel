package com.example.ads

import android.app.Activity
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Google User Messaging Platform (UMP) consent gate.
 *
 * Rules implemented here (per AdMob/UMP documentation):
 *  - requestConsentInfoUpdate() is called at every app launch / resume.
 *  - After a successful update, loadAndShowConsentFormIfRequired() shows the form if needed.
 *  - Ads may initialize/request ONLY when consentInformation.canRequestAds() is true —
 *    never on form dismissal, custom flags, or assumed consent.
 *  - Previous-session consent is honored: canRequestAds() is re-read after every
 *    success/error/form callback (it can be true even if the update call fails).
 *  - If consent fails and canRequestAds() is false, the app keeps working without ads.
 *
 * AUDIENCE: this build is configured as a general-audience utility app — it is NOT
 * child-directed and tagForUnderAgeOfConsent is false. If the Play Console target
 * audience for this app ever includes children, set setTagForUnderAgeOfConsent(true)
 * and review Google Play Families + AdMob child-directed requirements BEFORE release.
 */
object AdConsentManager {

    private const val TAG = "AdConsentManager"

    @Volatile
    var canRequestAds: Boolean = false
        private set

    @Volatile
    var isPrivacyOptionsRequired: Boolean = false
        private set

    @Volatile
    private var gathering = false

    /**
     * Gathers/refresh consent. [onStateChanged] is invoked on the main thread with
     * (canRequestAds, privacyOptionsRequired) — possibly more than once (update success,
     * then form dismissal). Callers must tolerate repeat invocations.
     */
    fun gatherConsent(
        activity: Activity,
        onStateChanged: (canRequestAds: Boolean, privacyOptionsRequired: Boolean) -> Unit
    ) {
        if (gathering) {
            onStateChanged(canRequestAds, isPrivacyOptionsRequired)
            return
        }
        gathering = true

        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false) // see AUDIENCE note above
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Update succeeded — present the form if the regulation requires it.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { loadAndShowError ->
                    if (loadAndShowError != null) {
                        Log.w(TAG, "Consent form issue: ${loadAndShowError.errorCode} ${loadAndShowError.message}")
                    }
                    publish(consentInformation, onStateChanged)
                    gathering = false
                }
            },
            { requestError ->
                // Update failed. Keep app functional; only serve ads if a previous
                // session already granted canRequestAds().
                Log.w(TAG, "Consent info update failed: ${requestError.errorCode} ${requestError.message}")
                publish(consentInformation, onStateChanged)
                gathering = false
            }
        )
    }

    /** Presents the Privacy Options form (call only when REQUIRED; safe to call anytime). */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onStateChanged: (canRequestAds: Boolean, privacyOptionsRequired: Boolean) -> Unit
    ) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "Privacy options form issue: ${formError.errorCode} ${formError.message}")
            }
            publish(consentInformation, onStateChanged)
        }
    }

    private fun publish(
        consentInformation: ConsentInformation,
        onStateChanged: (Boolean, Boolean) -> Unit
    ) {
        canRequestAds = consentInformation.canRequestAds()
        isPrivacyOptionsRequired =
            consentInformation.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        Log.d(TAG, "canRequestAds=$canRequestAds privacyOptionsRequired=$isPrivacyOptionsRequired")
        onStateChanged(canRequestAds, isPrivacyOptionsRequired)
    }

    /** DEBUG/QA ONLY helper — never call this in production code paths. */
    fun debugReset(context: android.content.Context) {
        UserMessagingPlatform.getConsentInformation(context).reset()
    }
}
