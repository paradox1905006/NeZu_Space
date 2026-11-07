package com.dparadox.tabspace

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * What is unlocked right now. Lives in memory only, so a new process always starts locked.
 * Several screens (browser, Settings, Guide) share it: leaving the app = no screen of the app is visible any more.
 */
object LockState {
    @Volatile var unlocked = false
    val unlockedProfiles = HashSet<String>()

    private var started = 0
    private var leftAt = 0L

    fun onStart() { started++ }

    fun onStop() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) leftAt = SystemClock.elapsedRealtime()
    }

    /** True when the app lock must be asked now. Also forgets old unlocks once the chosen time away has passed. */
    fun appLockNeeded(c: Context): Boolean {
        if (!PrivacyPrefs.appLock(c)) { unlocked = true; return false }
        if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > PrivacyPrefs.lockDelayMs(c)) {
            unlocked = false
            unlockedProfiles.clear()
        }
        leftAt = 0L
        return !unlocked
    }

    /** True when the profile has its own lock and was not unlocked yet. */
    fun profileLockNeeded(c: Context, profileId: String) =
        PrivacyPrefs.isLocked(c, profileId) && profileId !in unlockedProfiles
}

/**
 * Full-screen cover with a fingerprint / face / PIN prompt. Added on top of the whole window, so the page
 * underneath can neither be seen nor touched.
 */
class LockScreen(private val activity: FragmentActivity, private val onUnlocked: () -> Unit) {

    companion object {
        /** Fingerprint, face or the phone's PIN / pattern / password. */
        const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /** True when this phone has a screen lock or biometrics that can be used. */
        fun available(c: Context) =
            BiometricManager.from(c).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private var overlay: FrameLayout? = null
    private var titleView: TextView? = null
    private var messageView: TextView? = null
    private var switchButton: MaterialButton? = null
    private var prompting = false

    val isShowing: Boolean get() = overlay?.parent != null

    fun show(title: String, message: String, onSwitch: (() -> Unit)?) {
        val o = overlay ?: build().also { overlay = it }
        titleView?.text = title
        messageView?.text = message
        switchButton?.apply {
            visibility = if (onSwitch != null) View.VISIBLE else View.GONE
            setOnClickListener { onSwitch?.invoke() }
        }
        if (o.parent == null) {
            (activity.window.decorView as ViewGroup).addView(o,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        o.bringToFront()
        prompt()
    }

    fun hide() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun prompt() {
        if (prompting) return
        if (!available(activity)) { failOpen(); return }
        prompting = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock NeZu Space")
            .setAllowedAuthenticators(AUTH)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                onUnlocked()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ||
                    errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                    errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT) failOpen()
                // Cancelled or too many tries: the cover stays, the Unlock button asks again.
            }
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    /** The phone has no screen lock any more, so there is nothing to ask. The app lock is switched off. */
    private fun failOpen() {
        PrivacyPrefs.setAppLock(activity, false)
        Toast.makeText(activity, "No screen lock on this phone. App lock was turned off.", Toast.LENGTH_LONG).show()
        onUnlocked()
    }

    private fun build(): FrameLayout {
        val surface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val onSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
        val variant = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface)
        val dp = activity.resources.displayMetrics.density

        val title = TextView(activity).apply {
            textSize = 24f; setTextColor(onSurface); gravity = Gravity.CENTER
        }
        val message = TextView(activity).apply {
            textSize = 15f; setTextColor(variant); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
        }
        val unlock = MaterialButton(activity).apply {
            text = "Unlock"
            setOnClickListener { prompt() }
        }
        val switch = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Switch profile"
            visibility = View.GONE
        }
        titleView = title; messageView = message; switchButton = switch

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
            addView(title); addView(message); addView(unlock); addView(switch)
        }
        return FrameLayout(activity).apply {
            setBackgroundColor(surface)
            isClickable = true
            isFocusable = true
            elevation = 100f
            addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }
}
