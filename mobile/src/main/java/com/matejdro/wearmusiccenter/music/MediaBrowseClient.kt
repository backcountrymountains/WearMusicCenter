package com.matejdro.wearmusiccenter.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaBrowserCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Reads another media app's browse tree.
 *
 * A player's real queue is usually NOT in its MediaSession: AntennaPod reports
 * a session queue of 0-1 items no matter how long the actual queue is. What
 * Android Auto shows comes from the app's MediaBrowserService instead, so that
 * is what this reads.
 *
 * Everything here is best-effort. The service belongs to another app, it may
 * refuse the connection outright, and its media IDs are private to it — so all
 * failures degrade to an empty list rather than throwing.
 */
class MediaBrowseClient(private val context: Context) {

    /**
     * Finds the browse service [packageName] exposes, or null if it has none or
     * package visibility hides it.
     */
    fun findBrowseService(packageName: String): ComponentName? {
        val intent = Intent(SERVICE_INTERFACE).setPackage(packageName)
        val resolved = context.packageManager.queryIntentServices(intent, 0)

        val service = resolved.firstOrNull()?.serviceInfo
        if (service == null) {
            Timber.d("browse: %s exposes no MediaBrowserService", packageName)
            return null
        }

        return ComponentName(service.packageName, service.name)
    }

    /**
     * Connects, walks the root, and returns the children of the first node that
     * looks like a queue. Logs the whole tree it sees — media IDs are
     * app-specific and undocumented, so the log is the only way to learn them.
     */
    suspend fun loadQueue(component: ComponentName): List<MediaBrowserCompat.MediaItem> =
            withContext(Dispatchers.Main) {
                var browser: MediaBrowserCompat? = null
                try {
                    val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        suspendCancellableCoroutine { continuation ->
                            val callback = object : MediaBrowserCompat.ConnectionCallback() {
                                override fun onConnected() {
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onConnectionFailed() {
                                    Timber.w("browse: %s refused the connection", component)
                                    if (continuation.isActive) continuation.resume(false)
                                }

                                override fun onConnectionSuspended() {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            }

                            browser = MediaBrowserCompat(context, component, callback, null)
                            browser?.connect()
                        }
                    }

                    if (connected != true) {
                        Timber.w("browse: could not connect to %s (timeout or refusal)", component)
                        return@withContext emptyList()
                    }

                    val root = browser?.root
                    if (root.isNullOrEmpty()) {
                        Timber.w("browse: %s gave no root", component)
                        return@withContext emptyList()
                    }

                    val rootChildren = loadChildren(browser, root)
                    Timber.d("browse: root '%s' has %d children", root, rootChildren.size)
                    rootChildren.forEach {
                        Timber.d("browse:   root child id='%s' title='%s' browsable=%b",
                                it.mediaId, it.description.title, it.isBrowsable)
                    }

                    val queueNode = rootChildren.firstOrNull { item ->
                        val id = item.mediaId.orEmpty().lowercase()
                        val title = item.description.title?.toString().orEmpty().lowercase()
                        item.isBrowsable && (QUEUE_HINTS.any { id.contains(it) } ||
                                QUEUE_HINTS.any { title.contains(it) })
                    }

                    if (queueNode?.mediaId == null) {
                        Timber.w("browse: no queue-looking node among %d root children",
                                rootChildren.size)
                        return@withContext emptyList()
                    }

                    val queueItems = loadChildren(browser, queueNode.mediaId!!)
                    Timber.d("browse: queue node '%s' yielded %d items",
                            queueNode.mediaId, queueItems.size)

                    queueItems
                } finally {
                    // Always unbind; a leaked browser keeps the other app's service alive.
                    browser?.takeIf { it.isConnected }?.disconnect()
                }
            }

    private suspend fun loadChildren(
            browser: MediaBrowserCompat?,
            parentId: String
    ): List<MediaBrowserCompat.MediaItem> {
        if (browser == null) return emptyList()

        return withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : MediaBrowserCompat.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                            parentId: String,
                            children: MutableList<MediaBrowserCompat.MediaItem>
                    ) {
                        browser.unsubscribe(parentId)
                        if (continuation.isActive) continuation.resume(children.toList())
                    }

                    override fun onError(parentId: String) {
                        Timber.w("browse: error loading children of '%s'", parentId)
                        browser.unsubscribe(parentId)
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }

                browser.subscribe(parentId, callback)
            }
        } ?: emptyList<MediaBrowserCompat.MediaItem>().also {
            Timber.w("browse: timed out loading children of '%s'", parentId)
        }
    }
}

private const val SERVICE_INTERFACE = "android.media.browse.MediaBrowserService"
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

// Matched against both media id and title, lowercased.
private val QUEUE_HINTS = listOf("queue", "playlist", "up next", "upnext")
