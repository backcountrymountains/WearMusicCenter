package com.matejdro.wearmusiccenter.config

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.media.MediaBrowserService
import com.matejdro.wearmusiccenter.common.MiscPreferences
import timber.log.Timber

/**
 * Which media apps may auto-start the watch app.
 *
 * Stored as an allow-list. Older versions stored the *unticked* apps as a
 * blacklist, which silently allowed every app the picker could not see (package
 * visibility hides most apps on Android 11+) and every app installed later.
 */
object AutoStartApps {
    fun isAllowed(context: Context, preferences: SharedPreferences, packageName: String?): Boolean {
        migrateBlacklist(context, preferences)

        // Never configured at all: keep the historical "every app" behaviour.
        val allowed = preferences.getStringSet(MiscPreferences.AUTO_START_APPS_ALLOWED.key, null)
            ?: return true
        return allowed.contains(packageName)
    }

    /**
     * One-time conversion of the legacy blacklist: allowed = media apps (media
     * button receiver or browse service) minus the blacklist. Which apps the old
     * picker could see cannot be reconstructed, so non-media apps are left out;
     * the user can tick them again.
     */
    fun migrateBlacklist(context: Context, preferences: SharedPreferences) {
        if (preferences.contains(MiscPreferences.AUTO_START_APPS_ALLOWED.key)) return
        val blacklist = preferences.getStringSet(MiscPreferences.AUTO_START_APP_BLACKLIST.key, null)
            ?: return

        val packageManager = context.packageManager
        val mediaApps = packageManager
            .queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0)
            .map { it.activityInfo.packageName } +
                packageManager
                    .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                    .map { it.serviceInfo.packageName }

        val allowed = mediaApps.filterNot { blacklist.contains(it) }.toSet()

        preferences.edit()
            .putStringSet(MiscPreferences.AUTO_START_APPS_ALLOWED.key, allowed)
            .apply()
        Timber.i("Auto-start: migrated blacklist of %d to allow-list %s", blacklist.size, allowed)
    }
}
