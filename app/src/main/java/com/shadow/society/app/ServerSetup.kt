package com.shadow.society.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** The one-button server setup (Deploy to Cloudflare). The public GitHub repository holds the server files. */
object ServerSetup {
    const val DEPLOY_URL = "https://deploy.workers.cloudflare.com/?url=https://github.com/Dev-ira92/shadow-server"

    /** Opens the Cloudflare deploy page in the browser (or copies the link if there is no browser). */
    fun open(c: Context) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEPLOY_URL)))
        } catch (e: Exception) {
            Neo.copy(c, DEPLOY_URL)
            Toast.makeText(c, tr("No browser found. The link was copied: paste it in a browser.",
                "مرورگر پیدا نشد. لینک کپی شد: در یک مرورگر paste کن."), Toast.LENGTH_LONG).show()
        }
    }
}
