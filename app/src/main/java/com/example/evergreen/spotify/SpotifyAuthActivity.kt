package com.example.evergreen.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one Spotify login from start to finish.
 *
 * `singleTask` with [onNewIntent] rather than a separate receiver activity: the
 * redirect has to arrive at the same instance holding this attempt's PKCE
 * verifier, and a fresh instance would have nothing to check the returned
 * `state` against.
 *
 * The sequence:
 * 1. [onCreate] builds the authorize URL and opens it in a Custom Tab.
 * 2. The user logs in on **Spotify's own page**; this app never sees the
 *    password, and does not draw the screen it is typed into.
 * 3. Spotify redirects to `com.example.evergreen://auth`, which this activity's
 *    intent-filter catches, delivering it to [onNewIntent].
 * 4. The code is exchanged for tokens off the main thread, and the activity
 *    finishes.
 *
 * If the user backs out of the browser instead, [onResume] sees the flow was
 * launched but no redirect arrived, and treats it as a cancellation rather than
 * leaving a dead activity behind.
 */
class SpotifyAuthActivity : Activity() {

    private lateinit var auth: SpotifyAuth
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** Survives configuration changes so a rotation does not restart the login. */
    private var launchedBrowser = false
    private var completing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = SpotifyAuth(this)
        launchedBrowser = savedInstanceState?.getBoolean(STATE_LAUNCHED) ?: false

        if (!auth.isConfigured) {
            // Named precisely, because the fix is a specific line in a specific
            // file that only the developer can add.
            toast("No Spotify client ID. Add spotify.clientId to local.properties.")
            finishWith(Activity.RESULT_CANCELED)
            return
        }

        // A redirect can arrive straight into onCreate when the activity was not
        // already running — a cold start after the browser was reopened.
        val redirect = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (redirect != null) {
            handleRedirect(redirect)
            return
        }

        if (!launchedBrowser) {
            startLogin()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launchedBrowser)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val redirect = intent.data
        if (intent.action == Intent.ACTION_VIEW && redirect != null) {
            handleRedirect(redirect)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back in the foreground, the browser was opened, no redirect came: the
        // user dismissed the login. Without this the activity would sit here
        // forever waiting for something that is not coming.
        if (launchedBrowser && !completing) {
            finishWith(Activity.RESULT_CANCELED)
        }
    }

    private fun startLogin() {
        val authorizeUrl = try {
            auth.beginAuthorization()
        } catch (e: Exception) {
            Log.w(TAG, "could not start login", e)
            toast("Could not start the Spotify login.")
            finishWith(Activity.RESULT_CANCELED)
            return
        }

        launchedBrowser = true
        try {
            // A Custom Tab is the system browser rendered inside this app. RFC
            // 8252 recommends it over both an external browser and an embedded
            // webview: it shares the browser's cookie jar, so an existing
            // Spotify session is reused, and unlike a webview this app has no
            // ability to read what is typed into it.
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, authorizeUrl)
        } catch (e: Exception) {
            // No browser able to handle a Custom Tab. Fall back to whatever can
            // open a URL; the flow is identical from Spotify's side.
            Log.i(TAG, "custom tab unavailable, falling back to the system browser", e)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, authorizeUrl))
            } catch (e2: Exception) {
                toast("No browser available to sign in to Spotify.")
                finishWith(Activity.RESULT_CANCELED)
            }
        }
    }

    private fun handleRedirect(redirect: Uri) {
        completing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { auth.completeAuthorization(redirect) }
            when (result) {
                is AuthResult.Success -> {
                    toast("Spotify connected.")
                    finishWith(Activity.RESULT_OK)
                }
                is AuthResult.Cancelled -> finishWith(Activity.RESULT_CANCELED)
                is AuthResult.Failed -> {
                    toast(result.reason)
                    finishWith(Activity.RESULT_CANCELED)
                }
            }
        }
    }

    private fun finishWith(resultCode: Int) {
        setResult(resultCode)
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "SpotifyAuthActivity"
        private const val STATE_LAUNCHED = "launched_browser"

        /** Starts a login. */
        fun intent(context: Context): Intent = Intent(context, SpotifyAuthActivity::class.java)
    }
}
