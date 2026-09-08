package com.matejdro.wearmusiccenter.watch.communication

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.Wearable
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.buttonconfig.ButtonInfo
import com.matejdro.wearmusiccenter.common.util.FloatPacker
import com.matejdro.wearmusiccenter.proto.CustomList
import com.matejdro.wearmusiccenter.proto.CustomListItemAction
import com.matejdro.wearmusiccenter.proto.MusicState
import com.matejdro.wearmusiccenter.proto.Notification
import com.matejdro.wearmusiccenter.watch.util.launchWithErrorHandling
import com.matejdro.wearutils.lifecycle.ListenableLiveData
import com.matejdro.wearutils.lifecycle.LiveDataLifecycleCombiner
import com.matejdro.wearutils.lifecycle.LiveDataLifecycleListener
import com.matejdro.wearutils.lifecycle.Resource
import com.matejdro.wearutils.lifecycle.SingleLiveEvent
import com.matejdro.wearutils.messages.getByteArrayAsset
import com.matejdro.wearutils.messages.getNearestNodeId
import com.matejdro.wearutils.messages.sendMessageToNearestClient
import com.matejdro.wearutils.miscutils.BitmapUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneConnection @Inject constructor(@ApplicationContext private val context: Context) : DataClient.OnDataChangedListener,
        CapabilityClient.OnCapabilityChangedListener,
        LiveDataLifecycleListener {

    private var scope: CoroutineScope? = null

    companion object {
        const val MESSAGE_CLOSE_CONNECTION = 0
        const val CONNECTION_CLOSE_DELAY_MS = 15_000L
    }

    val musicState = ListenableLiveData<Resource<MusicState>>()
    val albumArt = ListenableLiveData<Bitmap?>()
    val customList = ListenableLiveData<CustomListWithBitmaps>()

    val notification = SingleLiveEvent<com.matejdro.wearmusiccenter.watch.model.Notification>()

    val rawPlaybackConfig = MutableLiveData<DataItem>()
    val rawStoppedConfig = MutableLiveData<DataItem>()
    val rawActionMenuConfig = MutableLiveData<DataItem>()

    private val lifecycleObserver = LiveDataLifecycleCombiner(this)

    private val messageClient = Wearable.getMessageClient(context)
    private val dataClient = Wearable.getDataClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val closeHandler = ConnectionCloseHandler(WeakReference(this))

    private var sendingVolume = false
    private var nextVolume = -1f

    private var running = AtomicBoolean(false)

    // Bumped on every start(). Lets a stop() that suspended over a slow Bluetooth
    // round trip notice that a start() overtook it, so it does not tear down the
    // connection that start() just established.
    private val generation = AtomicInteger(0)

    init {
        lifecycleObserver.addLiveData(musicState)
        lifecycleObserver.addLiveData(albumArt)
    }

    /**
     * Bring the connection up. Returns false when it was already considered
     * running, in which case nothing was sent to the phone.
     */
    private fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            return false
        }

        generation.incrementAndGet()

        val startedScope = CoroutineScope(Job() + Dispatchers.Main)
        scope = startedScope

        startedScope.launchWithErrorHandling(context, musicState) {
            val capabilities = capabilityClient.getCapability(
                    CommPaths.PHONE_APP_CAPABILITY,
                    CapabilityClient.FILTER_REACHABLE
            ).await()

            onWatchConnectionUpdated(capabilities)

            dataClient.addListener(this@PhoneConnection)
            capabilityClient.addListener(this@PhoneConnection, CommPaths.PHONE_APP_CAPABILITY)

            loadCurrentActionConfig(CommPaths.DATA_PLAYING_ACTION_CONFIG, rawPlaybackConfig)
            loadCurrentActionConfig(CommPaths.DATA_STOPPING_ACTION_CONFIG, rawStoppedConfig)
            loadCurrentActionConfig(CommPaths.DATA_LIST_ITEMS, rawActionMenuConfig)

            musicState.postValue(Resource.loading(null))

            // The phone's push may never arrive (dropped message, or its MusicService
            // was killed). The last state it published is still sitting in the data
            // layer, so seed the UI from that instead of spinning forever.
            loadCurrentMusicState()
        }

        return true
    }

    /**
     * Ask the phone to send its current music state.
     *
     * This is the only recovery path when the phone's MusicService has been killed
     * (ACK timeout, Doze, or the OEM's background limits) while this side still
     * believes the connection is alive: the message restarts that service through
     * WatchListenerService, which then transmits fresh state.
     */
    private fun requestResync() {
        val activeScope = scope ?: return

        activeScope.launchWithErrorHandling(context, musicState) {
            val phoneNode = nodeClient.getNearestNodeId() ?: return@launchWithErrorHandling
            messageClient.sendMessage(phoneNode, CommPaths.MESSAGE_WATCH_OPENED, null).await()
        }
    }

    /**
     * Seed [musicState] from the music state DataItem already held by the data layer,
     * without clobbering a fresher value that arrived while this was in flight.
     */
    private suspend fun loadCurrentMusicState() {
        val dataItems = dataClient.getDataItems(
                Uri.parse("wear://*${CommPaths.DATA_MUSIC_STATE}"),
                DataClient.FILTER_LITERAL
        ).await()

        try {
            val dataItem = dataItems.firstOrNull() ?: return
            if (musicState.value?.status == Resource.Status.SUCCESS) {
                return
            }

            val storedState = MusicState.parseFrom(dataItem.freeze().data)
            if (!storedState.error) {
                musicState.postValue(Resource.success(storedState))
            }
        } finally {
            dataItems.release()
        }
    }

    private fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        val stoppingScope = scope ?: return
        val stoppingGeneration = generation.get()

        stoppingScope.launchWithErrorHandling(context, musicState) {
            try {
                if (running.get()) {
                    // A start() already overtook us; leave its listener registered.
                    return@launchWithErrorHandling
                }

                dataClient.removeListener(this@PhoneConnection)

                val phoneNode = nodeClient.getNearestNodeId()

                // getNearestNodeId() can suspend for seconds over Bluetooth. If a
                // start() ran in that window, sending WATCH_CLOSED now would make the
                // phone stopSelf() its MusicService while the watch app is open, and
                // the watch would never receive another update.
                if (phoneNode != null &&
                        generation.get() == stoppingGeneration &&
                        !running.get()) {
                    messageClient.sendMessage(phoneNode, CommPaths.MESSAGE_WATCH_CLOSED, null).await()
                }
            } finally {
                // Cancel the scope captured above, never the current field: a
                // concurrent start() may already have replaced it with a live one.
                stoppingScope.cancel()
            }

        }
    }

    private fun onWatchConnectionUpdated(capabilityInfo: CapabilityInfo) {
        val firstNode = capabilityInfo.nodes.firstOrNull { it.isNearby }

        if (firstNode != null) {
            scope?.launchWithErrorHandling(context, musicState) {
                messageClient.sendMessage(capabilityInfo.nodes.first().id, CommPaths.MESSAGE_WATCH_OPENED, null).await()
            }
        } else {
            musicState.postValue(Resource.error(context.getString(R.string.no_phone), null))
        }
    }

    suspend fun sendManualCloseMessage() {
        if (!running.get()) {
            return
        }

        // Activity closes when manual close happens, so we must ignore cancel signal here
        withContext(NonCancellable) {
            val phoneNode = nodeClient.getNearestNodeId()
            if (phoneNode != null) {
                messageClient.sendMessage(phoneNode, CommPaths.MESSAGE_WATCH_CLOSED_MANUALLY, null).await()
            }
        }
    }

    fun sendVolume(newVolume: Float) {
        scope?.launchWithErrorHandling(context, musicState) {
            nextVolume = -1f

            if (sendingVolume) {
                nextVolume = newVolume
                return@launchWithErrorHandling
            }

            try {
                sendingVolume = true

                messageClient.sendMessageToNearestClient(
                        nodeClient,
                        CommPaths.MESSAGE_CHANGE_VOLUME,
                        FloatPacker.packFloat(newVolume)
                )
            } finally {
                sendingVolume = false
                if (nextVolume >= 0) {
                    sendVolume(nextVolume)
                }
            }
        }
    }

    suspend fun executeButtonAction(buttonInfo: ButtonInfo) {
        messageClient.sendMessageToNearestClient(
                nodeClient,
                CommPaths.MESSAGE_EXECUTE_ACTION,
                buttonInfo.buildProtoVersion().build().toByteArray()
        )
    }

    suspend fun executeMenuAction(index: Int) {
        messageClient.sendMessageToNearestClient(
                nodeClient,
                CommPaths.MESSAGE_EXECUTE_MENU_ACTION,
                ByteBuffer.allocate(4).putInt(index).array()
        )
    }

    suspend fun executeCustomMenuAction(listId: String, entryId: String) {
        messageClient.sendMessageToNearestClient(
                nodeClient,
                CommPaths.MESSAGE_CUSTOM_LIST_ITEM_SELECTED,
                CustomListItemAction.newBuilder()
                        .setListId(listId)
                        .setEntryId(entryId)
                        .build()
                        .toByteArray()
        )

    }


    private suspend fun sendAck() {
        messageClient.sendMessageToNearestClient(nodeClient, CommPaths.MESSAGE_ACK)
    }

    override fun onDataChanged(data: DataEventBuffer) {
        val frozenData = data.use { _ ->
            data.map { it.freeze() }
        }

        scope?.launchWithErrorHandling(context, musicState) {
            frozenData.filter { it.type == DataEvent.TYPE_CHANGED }
                    .map { it.dataItem }
                    .forEach {
                        when (it.uri.path) {
                            CommPaths.DATA_MUSIC_STATE -> {
                                val dataItem = it.freeze()

                                val receivedMusicState = MusicState.parseFrom(dataItem.data)

                                if (receivedMusicState.error) {
                                    musicState.postValue(Resource.error(receivedMusicState.title, null))
                                } else {
                                    musicState.postValue(Resource.success(receivedMusicState))

                                    sendAck()

                                    val albumArtData = dataItem.assets[CommPaths.ASSET_ALBUM_ART]
                                            ?.let { asset -> dataClient.getByteArrayAsset(asset) }
                                    albumArt.postValue(BitmapUtils.deserialize(albumArtData))
                                }
                            }
                            CommPaths.DATA_NOTIFICATION -> {
                                val dataItem = it.freeze()
                                val receivedNotification = Notification.parseFrom(dataItem.data)

                                sendAck()

                                val pictureData = dataItem.assets[CommPaths.ASSET_NOTIFICATION_BACKGROUND]
                                        ?.let { asset -> dataClient.getByteArrayAsset(asset) }
                                val picture = BitmapUtils.deserialize(pictureData)

                                val mergedNotification = com.matejdro.wearmusiccenter.watch.model.Notification(
                                        receivedNotification.title,
                                        receivedNotification.description,
                                        picture,
                                        System.currentTimeMillis()
                                )

                                notification.postValue(mergedNotification)
                            }
                            CommPaths.DATA_PLAYING_ACTION_CONFIG -> rawPlaybackConfig.postValue(it.freeze())
                            CommPaths.DATA_STOPPING_ACTION_CONFIG -> rawStoppedConfig.postValue(it.freeze())
                            CommPaths.DATA_LIST_ITEMS -> rawActionMenuConfig.postValue(it.freeze())
                            CommPaths.DATA_CUSTOM_LIST -> {
                                val dataItem = it.freeze()
                                val receivedCustomList = CustomList.parseFrom(dataItem.data)

                                val listItems = receivedCustomList.actionsList
                                        .mapIndexed { index, rawListEntry ->
                                            val pictureData = dataItem.assets[index.toString()]
                                                    ?.let { asset -> dataClient.getByteArrayAsset(asset) }

                                            val picture = BitmapUtils.deserialize(pictureData)

                                            CustomListItemWithIcon(
                                                    rawListEntry,
                                                    picture
                                            )
                                        }


                                customList.postValue(
                                        CustomListWithBitmaps(
                                                receivedCustomList.listTimestamp,
                                                receivedCustomList.listId,
                                                listItems
                                        )
                                )
                            }
                        }
                    }
        }
    }

    override fun onCapabilityChanged(capability: CapabilityInfo) {
        onWatchConnectionUpdated(capability)
    }

    private suspend fun loadCurrentActionConfig(configPath: String, targetLiveData: MutableLiveData<DataItem>) {
        val dataItems = dataClient.getDataItems(
                Uri.parse("wear://*$configPath"),
                DataClient.FILTER_LITERAL)
                .await()

        val dataItem = dataItems.firstOrNull() ?: return

        targetLiveData.postValue(dataItem.freeze())
        dataItems.release()
    }

    override fun onInactive() {
        // Delay connection closing for a bit to make sure it is not just brief configuration change

        closeHandler.removeMessages(MESSAGE_CLOSE_CONNECTION)
        closeHandler.sendEmptyMessageDelayed(MESSAGE_CLOSE_CONNECTION, CONNECTION_CLOSE_DELAY_MS)
    }

    override fun onActive() {
        closeHandler.removeMessages(MESSAGE_CLOSE_CONNECTION)

        if (!start()) {
            // Already running, so start() sent nothing. That is the state the watch
            // gets stuck in: the activity is never destroyed (screen off keeps it
            // STARTED), onInactive never fires, and so the phone is never asked for
            // state again even though its service may have died long ago. Ask now.
            requestResync()
        }
    }

    suspend fun openPlaybackQueue() {
        messageClient.sendMessageToNearestClient(nodeClient, CommPaths.MESSAGE_OPEN_PLAYBACK_QUEUE)
    }

    private class ConnectionCloseHandler(val phoneConnection: WeakReference<PhoneConnection>) : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: android.os.Message) {
            if (msg.what == MESSAGE_CLOSE_CONNECTION) {
                phoneConnection.get()?.stop()
            }
        }
    }
}
