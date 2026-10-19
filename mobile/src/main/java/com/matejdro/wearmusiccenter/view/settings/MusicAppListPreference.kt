package com.matejdro.wearmusiccenter.view.settings

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.config.AutoStartApps
import com.matejdro.wearutils.preferences.compat.PreferenceWithDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicAppListPreference
@JvmOverloads
constructor(context: Context, attributeSet: AttributeSet? = null) :
    DialogPreference(context, attributeSet), PreferenceWithDialog {
    var appList: List<App>? = null

    /** Null until the user (or the legacy-blacklist migration) has saved a list. */
    var allowed: MutableSet<String>?
        get() = getPersistedStringSet(null)
        set(value) {
            persistStringSet(value)
        }

    override fun createDialog(key: String): PreferenceDialogFragmentCompat {
        return MusicAppListPreferenceDialog.create(key)
    }

    override fun onClick() {
        GlobalScope.launch(Dispatchers.Main) {

            @Suppress("DEPRECATION")
            val progressDialog = android.app.ProgressDialog(this@MusicAppListPreference.context!!)
            progressDialog.setMessage(this@MusicAppListPreference.context!!.getString(R.string.please_wait))
            progressDialog.show()

            appList = getAllApps()
            withContext(Dispatchers.Default) {
                AutoStartApps.migrateBlacklist(context, sharedPreferences!!)
            }

            progressDialog.hide()
            preferenceManager.showDialog(this@MusicAppListPreference)
        }
    }

    private suspend fun getAllApps(): List<App> = withContext(Dispatchers.Default) {
        val packageManager = this@MusicAppListPreference.context!!.packageManager

        packageManager.getInstalledPackages(0)
            .map {
                val appLabel = try {
                    val appInfo = packageManager.getApplicationInfo(it.packageName, 0)
                    packageManager.getApplicationLabel(appInfo)
                } catch (e: PackageManager.NameNotFoundException) {
                    it.packageName
                }

                App(it.packageName, appLabel.toString())
            }
            .sortedBy { it.label }
    }

    class MusicAppListPreferenceDialog : PreferenceDialogFragmentCompat() {
        companion object {
            fun create(key: String): MusicAppListPreferenceDialog {
                val fragment = MusicAppListPreferenceDialog()

                val arguments = Bundle(1)
                arguments.putString(ARG_KEY, key)

                fragment.arguments = arguments
                return fragment
            }
        }

        private lateinit var installedApps: List<App>

        /** Packages allowed to auto-start, exactly as persisted. */
        private val allowedPackages = HashSet<String>()

        private var filteredApps: List<App> = emptyList()

        private lateinit var listView: ListView
        private lateinit var listAdapter: ArrayAdapter<String>
        private lateinit var selectionSummary: TextView

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            installedApps = (preference as MusicAppListPreference).appList ?: emptyList()

            // Never configured means every app may auto-start, so show them all ticked.
            val saved = (preference as MusicAppListPreference).allowed
            allowedPackages.clear()
            if (saved != null) {
                allowedPackages.addAll(saved)
            } else {
                installedApps.mapTo(allowedPackages) { it.pkg }
            }

            return super.onCreateDialog(savedInstanceState)
        }

        override fun onDialogClosed(positiveResult: Boolean) {
            if (!positiveResult) {
                return
            }

            (preference as MusicAppListPreference).allowed = HashSet(allowedPackages)
        }

        override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
            builder.setPositiveButton(android.R.string.ok, this)
                .setNegativeButton(android.R.string.cancel, this)
        }

        override fun onCreateDialogView(context: Context): View {
            return LayoutInflater.from(context).inflate(R.layout.dialog_app_list, null)
        }

        override fun onBindDialogView(view: View) {
            super.onBindDialogView(view)

            listView = view.findViewById(R.id.app_list)
            selectionSummary = view.findViewById(R.id.selection_summary)

            listAdapter = ArrayAdapter(
                view.context,
                android.R.layout.simple_list_item_multiple_choice,
                ArrayList<String>()
            )
            listView.adapter = listAdapter
            listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

            listView.setOnItemClickListener { _, _, position, _ ->
                val pkg = filteredApps[position].pkg
                if (listView.isItemChecked(position)) {
                    allowedPackages.add(pkg)
                } else {
                    allowedPackages.remove(pkg)
                }
                updateSummary()
            }

            // Bulk actions apply to whatever the filter currently shows, so "type a
            // name, deselect all" works as well as clearing the whole list at once.
            view.findViewById<Button>(R.id.select_all).setOnClickListener {
                filteredApps.mapTo(allowedPackages) { app -> app.pkg }
                applyCheckedState()
            }
            view.findViewById<Button>(R.id.deselect_all).setOnClickListener {
                filteredApps.forEach { app -> allowedPackages.remove(app.pkg) }
                applyCheckedState()
            }

            view.findViewById<EditText>(R.id.app_filter).addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                }
            )

            applyFilter("")
        }

        private fun applyFilter(query: String) {
            val trimmed = query.trim()
            filteredApps = if (trimmed.isEmpty()) {
                installedApps
            } else {
                installedApps.filter {
                    it.label.contains(trimmed, ignoreCase = true) ||
                            it.pkg.contains(trimmed, ignoreCase = true)
                }
            }

            listAdapter.setNotifyOnChange(false)
            listAdapter.clear()
            listAdapter.addAll(filteredApps.map { it.label })
            listAdapter.notifyDataSetChanged()

            applyCheckedState()
        }

        private fun applyCheckedState() {
            // ListView tracks checks by position, so they must be reapplied whenever
            // the backing list changes.
            for (index in filteredApps.indices) {
                listView.setItemChecked(index, allowedPackages.contains(filteredApps[index].pkg))
            }
            updateSummary()
        }

        private fun updateSummary() {
            selectionSummary.text = selectionSummary.context.getString(
                R.string.app_list_selection_summary,
                allowedPackages.size,
                installedApps.size
            )
        }
    }

    data class App(val pkg: String, val label: String)
}
