package com.tak.weartak_tracker.market

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class MarketInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            if (confirmation == null) {
                abandonSession(context, intent)
                saveStatus(context, packageName, STATUS_FAILED, "Installer confirmation is unavailable.")
                return
            }
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(confirmation)
            } catch (error: Exception) {
                Log.e(TAG, "Could not open Android package confirmation", error)
                abandonSession(context, intent)
                saveStatus(context, packageName, STATUS_FAILED, "Could not open Android package confirmation.")
            }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            saveStatus(context, packageName, STATUS_SUCCESS, "Installation complete.")
        } else {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?.take(240)
                ?.takeIf { it.isNotBlank() }
                ?: "Android installer did not complete installation."
            saveStatus(context, packageName, STATUS_FAILED, message)
        }
    }

    private fun saveStatus(context: Context, packageName: String, status: String, message: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("$packageName.status", status)
            .putString("$packageName.message", message)
            .apply()
    }

    private fun abandonSession(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        if (sessionId >= 0) {
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                .onFailure { Log.w(TAG, "Could not abandon unconfirmed install session", it) }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "market_package_name"
        const val EXTRA_SESSION_ID = "market_session_id"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        private const val PREFERENCES = "takwerx_market_install_status"
        private const val TAG = "TakwerxMarket"

        fun lastStatus(context: Context, packageName: String): Pair<String, String>? {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val status = prefs.getString("$packageName.status", null) ?: return null
            val message = prefs.getString("$packageName.message", null) ?: return null
            return status to message
        }

        fun clearStatus(context: Context, packageName: String) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove("$packageName.status")
                .remove("$packageName.message")
                .apply()
        }
    }
}
