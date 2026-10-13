package se.lublin.mumla.app

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings

object DialogUtils {
    private val NEWS_ITEMS: Map<String, Int> = linkedMapOf(
        // These *must* be added in ascending semver order (1.2.3, 1.10.1, ...)
        "3.7.0" to R.string.app_news_items_v3_7_0,
        "3.7.1" to R.string.app_news_items_v3_7_1,
        "3.7.2" to R.string.app_news_items_v3_7_2,
        "3.7.3" to R.string.app_news_items_v3_7_3
    )
    private val RELEVANT_VERSIONS: List<String> = buildList {
        for (candidate in NEWS_ITEMS.keys) {
            // Relevant as long as candidate-version <= build-version; otherwise break
            if (Version(candidate).compareTo(Version(BuildConfig.VERSIONTAG)) > 0) {
                break
            }
            add(candidate)
        }
    }

    @JvmStatic
    fun maybeShowNewsDialog(context: Context): Boolean {
        val toShow = ArrayList<String>()
        val shown = Settings.getInstance(context).newsShownVersions
        for (version in RELEVANT_VERSIONS) {
            if (!shown.contains(version)) {
                toShow.add(version)
            }
        }

        if (toShow.isEmpty()) {
            return false
        }

        showNewsDialogForVersions(context, toShow, true)
        return true
    }

    @JvmStatic
    fun showAllNewsDialog(context: Context) {
        showNewsDialogForVersions(context, RELEVANT_VERSIONS, false)
    }

    private fun showNewsDialogForVersions(context: Context, versions: List<String>, markShown: Boolean) {
        val sb = StringBuilder()
        for (i in versions.size - 1 downTo 0) {
            val version = versions[i]
            val resId = NEWS_ITEMS[version]
            if (resId == null) {
                continue
            }
            sb.append(String.format("<b>%s %s</b><br/>", context.getString(R.string.version), version))
            sb.append(context.getString(resId).replace("\n", "<br/>"))
            sb.append("<br/>")
        }

        if (markShown) {
            sb.append(String.format("<em>%s</em>", context.getString(R.string.app_news_footer_on_startup)))
        }

        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_news, null)
        val newsTextView = dialogView.findViewById(R.id.news_text_view) as TextView
        newsTextView.text = HtmlCompat.fromHtml(sb.toString(), 0)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.app_news)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                if (markShown) {
                    Settings.getInstance(context).addNewsShownVersions(versions)
                }
                dialog.dismiss()
            }
            .show()
    }

    private class Version(versionString: String) : Comparable<Version> {
        val major: Int
        val minor: Int
        val patch: Int

        init {
            // Removing any "-suffix"
            val parts = versionString.split("-")[0].split(".")
            var maj = 0
            var min = 0
            var pat = 0
            try {
                if (parts.size > 0) maj = parts[0].toInt()
                if (parts.size > 1) min = parts[1].toInt()
                if (parts.size > 2) pat = parts[2].toInt()
            } catch (e: NumberFormatException) {
                Log.d("DialogUtils", "Failed to parse version string: $versionString", e)
            }
            major = maj
            minor = min
            patch = pat
        }

        override fun compareTo(other: Version): Int {
            if (major != other.major) {
                return Integer.compare(major, other.major)
            }
            if (minor != other.minor) {
                return Integer.compare(minor, other.minor)
            }
            return Integer.compare(patch, other.patch)
        }
    }
}
