package com.matejdro.wearmusiccenter.config

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-profile backup of the phone app's configuration.
 *
 * Everything this app persists lives in two directories: `files/` (button layouts,
 * the action list and the custom icon store) and `shared_prefs/` (misc settings,
 * including the auto-start blacklist, plus the icon metadata). Copying both
 * wholesale keeps backups correct as settings are added later, which a
 * field-by-field serializer would not.
 *
 * Restoring replaces the live configuration, so the process must be restarted
 * afterwards: SharedPreferences are cached in memory and would otherwise be
 * written straight back over the restored files.
 */
object SettingsBackup {
    const val MIME_TYPE = "application/zip"
    const val DEFAULT_FILE_NAME = "music-center-settings.zip"

    private const val DIR_FILES = "files"
    private const val DIR_PREFS = "shared_prefs"

    fun exportTo(context: Context, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            addDirectory(zip, context.filesDir, DIR_FILES)
            addDirectory(zip, sharedPrefsDir(context), DIR_PREFS)
        }
    }

    fun restoreFrom(context: Context, input: InputStream) {
        val staging = File(context.cacheDir, "settings_restore")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging directory" }

        try {
            // Unpack completely before touching the live configuration, so a
            // truncated or corrupt archive cannot leave a half-restored profile.
            var entriesRead = 0
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    extractEntry(zip, entry, staging)
                    entriesRead++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            require(entriesRead > 0) { "Backup file is empty" }

            val stagedFiles = File(staging, DIR_FILES)
            val stagedPrefs = File(staging, DIR_PREFS)
            require(stagedFiles.isDirectory || stagedPrefs.isDirectory) {
                "Not a Music Center backup"
            }

            replaceDirectory(stagedFiles, context.filesDir)
            replaceDirectory(stagedPrefs, sharedPrefsDir(context))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun sharedPrefsDir(context: Context): File =
        File(context.applicationInfo.dataDir, DIR_PREFS)

    private fun replaceDirectory(source: File, destination: File) {
        if (!source.isDirectory) {
            return
        }

        destination.deleteRecursively()
        destination.mkdirs()
        source.copyRecursively(destination, overwrite = true)
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.isDirectory) {
            return
        }

        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(directory).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry("$prefix/$relativePath"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun extractEntry(zip: ZipInputStream, entry: ZipEntry, target: File) {
        if (entry.isDirectory) {
            return
        }

        val targetRoot = target.canonicalFile
        val destination = File(targetRoot, entry.name).canonicalFile

        // Reject archives whose entry names climb out of the staging directory.
        require(destination.path.startsWith(targetRoot.path + File.separator)) {
            "Refusing unsafe backup entry: ${entry.name}"
        }

        destination.parentFile?.mkdirs()
        destination.outputStream().use { zip.copyTo(it) }
    }
}
