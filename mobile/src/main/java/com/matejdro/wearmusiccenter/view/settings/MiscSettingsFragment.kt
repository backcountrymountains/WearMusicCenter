package com.matejdro.wearmusiccenter.view.settings

import android.Manifest
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.matejdro.wearmusiccenter.NotificationService
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.MiscPreferences
import com.matejdro.wearmusiccenter.common.model.AutoStartMode
import com.matejdro.wearmusiccenter.config.SettingsBackup
import com.matejdro.wearmusiccenter.util.launchWithPlayServicesErrorHandling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.system.exitProcess
import com.matejdro.wearutils.logging.LogRetrievalTask
import com.matejdro.wearutils.preferences.compat.PreferenceFragmentCompatEx
import com.matejdro.wearutils.preferences.definition.Preferences
import com.matejdro.wearutils.preferencesync.PreferencePusher
import de.psdev.licensesdialog.LicensesDialog


class MiscSettingsFragment : PreferenceFragmentCompatEx(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private const val VIBRATION_CENTER_PACKAGE = "com.matejdro.wearvibrationcenter"
    }

    private val backupFileLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument(SettingsBackup.MIME_TYPE)) { uri ->
                uri?.let { performBackup(it) }
            }

    private val restoreFileLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { confirmRestore(it) }
            }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        initAutomationSection()
        initNotificationsSection()
        initBackupSection()
        initAboutSection()
    }

    private fun initBackupSection() {
        findPreference<Preference>("backup_settings")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    backupFileLauncher.launch(SettingsBackup.DEFAULT_FILE_NAME)
                    true
                }

        findPreference<Preference>("restore_settings")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    // Some file pickers will not offer a zip under the strict mime
                    // type, so accept anything and let the reader reject it.
                    restoreFileLauncher.launch(arrayOf("*/*"))
                    true
                }
    }

    private fun performBackup(uri: Uri) {
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val stream = appContext.contentResolver.openOutputStream(uri, "wt")
                            ?: return@withContext "Could not open the destination file"
                    stream.use { SettingsBackup.exportTo(appContext, it) }
                    null
                } catch (e: Exception) {
                    Timber.w(e, "Settings backup failed (%s)", e.javaClass.simpleName)
                    e.message ?: e.javaClass.simpleName
                }
            }

            val message = if (error == null) {
                getString(R.string.backup_success)
            } else {
                getString(R.string.backup_failed, error)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> performRestore(uri) }
                .show()
    }

    private fun performRestore(uri: Uri) {
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val stream = appContext.contentResolver.openInputStream(uri)
                            ?: return@withContext "Could not open the backup file"
                    stream.use { SettingsBackup.restoreFrom(appContext, it) }
                    null
                } catch (e: Exception) {
                    Timber.w(e, "Settings restore failed (%s)", e.javaClass.simpleName)
                    e.message ?: e.javaClass.simpleName
                }
            }

            if (error != null) {
                Toast.makeText(appContext, getString(R.string.restore_failed, error), Toast.LENGTH_LONG).show()
                return@launch
            }

            AlertDialog.Builder(requireContext())
                    .setTitle(R.string.restore_success_title)
                    .setMessage(R.string.restore_success_message)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        requireActivity().finishAffinity()
                        // SharedPreferences are cached in memory and would be written
                        // straight back over the restored files, so the process has to
                        // go away before they are read again.
                        exitProcess(0)
                    }
                    .show()
        }
    }

    private fun initAutomationSection() {
        migrateOldAutoStartSetting()

        findPreference<Preference>("auto_start_apps_allowed")?.isEnabled =
                Preferences.getEnum(preferenceManager.sharedPreferences, MiscPreferences.AUTO_START_MODE) != AutoStartMode.OFF

        findPreference<Preference>("auto_start_mode")!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    newValue as String

                    val mode = enumValueOf<AutoStartMode>(newValue)

                    findPreference<Preference>("auto_start_apps_allowed")?.isEnabled = mode != AutoStartMode.OFF


                    // On Android N and above we unbind notification service when autostart is disabled and
                    // rebind when enabled

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (newValue != AutoStartMode.OFF) {
                            NotificationListenerService.requestRebind(
                                    ComponentName(requireContext(), NotificationService::class.java)
                            )
                        } else {
                            val serviceStopIntent = Intent(requireContext(), NotificationService::class.java)
                            serviceStopIntent.action = NotificationService.ACTION_UNBIND_SERVICE
                            requireContext().startService(serviceStopIntent)
                        }
                    }

                    true
                }
    }

    private fun migrateOldAutoStartSetting() {
        val preferences = preferenceManager.sharedPreferences!!
        if (preferences.contains("auto_start")) {
            val legacyAutoStart = Preferences.getBoolean(preferences, MiscPreferences.AUTO_START)

            val autoStartMode = if (legacyAutoStart) AutoStartMode.OPEN_APP else AutoStartMode.OFF
            findPreference<ListPreference>("auto_start_mode")?.value = autoStartMode.name

            preferences.edit().remove("auto_start").apply()
        }
    }

    private fun initNotificationsSection() {
        findPreference<Preference>("enable_notification_popup")!!.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference, value: Any ->
            if (value == false) {
                return@OnPreferenceChangeListener true
            }

            if (!isVibrationCenterInstalledAndEnabled()) {
                AlertDialog.Builder(requireContext())
                        .setTitle(R.string.app_required)
                        .setMessage(R.string.vibration_center_required_description)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.open_play_store) { _: DialogInterface, _: Int -> openVibrationCenterPlayStore() }
                        .show()

                return@OnPreferenceChangeListener false
            }

            true
        }
    }

    private fun initAboutSection() {
        findPreference<Preference>("supportButton")!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            sendLogs()
            true
        }

        try {
            findPreference<Preference>("version")!!.summary =
                    requireActivity().packageManager.getPackageInfo(requireActivity().packageName, 0).versionName
        } catch (ignored: PackageManager.NameNotFoundException) {
        }

        findPreference<Preference>("licenses")!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            LicensesDialog.Builder(activity)
                    .setNotices(R.raw.notices)
                    .setIncludeOwnLicense(true)
                    .build()
                    .show()
            true
        }
    }

    private fun isVibrationCenterInstalledAndEnabled(): Boolean {
        return try {
            val appInfo = requireContext().packageManager.getApplicationInfo(VIBRATION_CENTER_PACKAGE, 0)
            appInfo.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openVibrationCenterPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VIBRATION_CENTER_PACKAGE")))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$VIBRATION_CENTER_PACKAGE")))
        }
    }

    private fun sendLogs() {
        LogRetrievalTask(activity,
                CommPaths.MESSAGE_SEND_LOGS,
                "apps@matejdro.com",
                "com.matejdro.wearmusiccenter.logs").execute(null as Void?)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (permissions.isNotEmpty() &&
                permissions[0] == Manifest.permission.WRITE_EXTERNAL_STORAGE &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendLogs()
        }
    }

    override fun onStart() {
        super.onStart()

        preferenceManager.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()

        preferenceManager.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        pushPreferencesToWatch()
    }

    private fun pushPreferencesToWatch() {
        lifecycleScope.launchWithPlayServicesErrorHandling(requireContext().applicationContext) {
            PreferencePusher.pushPreferences(
                    requireContext().applicationContext,
                    preferenceManager.sharedPreferences!!,
                    CommPaths.PREFERENCES_PREFIX,
                    false
            )
        }
    }
}
