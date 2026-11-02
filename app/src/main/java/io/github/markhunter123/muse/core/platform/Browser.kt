package io.github.markhunter123.muse.core.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Pages that are not part of the app's own flows (GitHub releases, a Platform page's privacy
 * policy, …) open in the user's browser as its own task. DeepSeek Platform pages (sign-in, 充值,
 * 用量) do not come here: they use the in-app WebView screen (feature/account/PlatformWebScreen.kt)
 * so Back always returns to Muse, whatever the default browser does with Custom Tabs.
 */
object Browser {
    /** Opens [url] (http/https) in the default browser; false when nothing could handle it. */
    fun openExternal(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: ActivityNotFoundException) {
            Log.w("Browser", "no browser for $url", e); false
        }
    }
}
