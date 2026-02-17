package uz.flutterwithakmaljon.video_player_pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class VideoPlayerPipPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private val TAG = "VideoPlayerPipPlugin"

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private var isInPipMode = false
    private var activityBinding: ActivityPluginBinding? = null
    private var componentCallback: android.content.ComponentCallbacks? = null

    // Cache of player ID to view mappings
    private val playerViewCache = mutableMapOf<Int, View?>()

    // --- Constants for Media Controls ---
    private val ACTION_PIP_CONTROL = "uz.flutterwithakmaljon.video_player_pip.MEDIA_CONTROL"
    private val EXTRA_CONTROL_TYPE = "control_type"
    private val CONTROL_TYPE_PLAY = 1
    private val CONTROL_TYPE_PAUSE = 2

    // Track state
    private var isPlayingState = true
    private var mediaSession: MediaSession? = null

    // --- BroadcastReceiver for Button Clicks ---
    private val pipBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != ACTION_PIP_CONTROL) return

            val type = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
            when (type) {
                CONTROL_TYPE_PLAY -> {
                    channel.invokeMethod("pipAction", "play")
                    updatePipParams(true, -1, -1) 
                }
                CONTROL_TYPE_PAUSE -> {
                    channel.invokeMethod("pipAction", "pause")
                    updatePipParams(false, -1, -1)
                }
            }
        }
    }

    // --- MediaSession Callback for Seek Bar ---
    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onSeekTo(pos: Long) {
            super.onSeekTo(pos)
            // Forward seek event to Flutter
            channel.invokeMethod("seekTo", pos)
            // Update local state immediately so UI feels responsive
            updatePipParams(isPlayingState, pos, -1)
        }

        override fun onPlay() {
            super.onPlay()
            channel.invokeMethod("pipAction", "play")
            updatePipParams(true, -1, -1)
        }

        override fun onPause() {
            super.onPause()
            channel.invokeMethod("pipAction", "pause")
            updatePipParams(false, -1, -1)
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "video_player_pip")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        playerViewCache.clear()
        releaseMediaSession()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "isPipSupported" -> result.success(isPipSupported())
            "enterPipMode" -> {
                val playerId = call.argument<Int>("playerId")
                val width = call.argument<Int>("width")
                val height = call.argument<Int>("height")
                val isPlaying = call.argument<Boolean>("isPlaying") ?: true
                val currentPosition = (call.argument<Number>("currentPosition")?.toLong()) ?: 0L
                val duration = (call.argument<Number>("duration")?.toLong()) ?: 0L

                if (playerId != null) {
                    val success = enterPipMode(playerId, width, height, isPlaying, currentPosition, duration)
                    result.success(success)
                } else {
                    result.error("INVALID_ARGUMENT", "Player ID is required", null)
                }
            }
            "exitPipMode" -> {
                result.success(exitPipMode())
            }
            "updatePipUi" -> {
                val isPlaying = call.argument<Boolean>("isPlaying") ?: false
                val position = (call.argument<Number>("position")?.toLong()) ?: 0L
                val duration = (call.argument<Number>("duration")?.toLong()) ?: 0L
                updatePipParams(isPlaying, position, duration)
                result.success(null)
            }
            "isInPipMode" -> {
                result.success(isInPipMode)
            }
            else -> result.notImplemented()
        }
    }

    private fun isPipSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun enterPipMode(
        playerId: Int,
        customWidth: Int?,
        customHeight: Int?,
        isPlaying: Boolean,
        currentPosition: Long,
        duration: Long
    ): Boolean {
        if (!isPipSupported() || activity == null) return false

        this.isPlayingState = isPlaying

        try {
            // Setup Media Session (Crucial for Seek Bar)
            setupMediaSession(duration, currentPosition, isPlaying)

            if (!playerViewCache.containsKey(playerId)) playerViewCache.clear()

            val videoView = playerViewCache.getOrPut(playerId) { findVideoPlayerView(playerId) }
            if (videoView == null) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val width = customWidth ?: videoView.width
                val height = customHeight ?: videoView.height

                // Aspect Ratio
                val aspectRatio = if (width > 0 && height > 0) {
                    val currentRatio = width.toFloat() / height.toFloat()
                    if (currentRatio >= 0.418410f && currentRatio <= 2.390000f) Rational(width, height)
                    else Rational(16, 9)
                } else Rational(16, 9)

                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .setActions(buildRemoteActions(isPlaying)) 

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(true)
                    paramsBuilder.setSeamlessResizeEnabled(true)
                }

                return activity?.enterPictureInPictureMode(paramsBuilder.build()) ?: false
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error entering PiP", e)
            return false
        }
    }

    private fun exitPipMode(): Boolean {
        if (!isPipSupported() || activity == null) return false
        try {
            if (isInPipMode) {
                val intent = Intent(context, activity!!.javaClass)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity?.startActivity(intent)
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error exiting PiP", e)
            return false
        }
    }

    // --- MediaSession Helpers ---

    private fun setupMediaSession(duration: Long, position: Long, isPlaying: Boolean) {
        if (mediaSession == null) {
            mediaSession = MediaSession(context, "FlutterVideoPipSession").apply {
                setCallback(mediaSessionCallback)
                setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
                isActive = true
            }
            
            activity?.let {
                val mediaController = MediaController(context, mediaSession!!.sessionToken)
                it.mediaController = mediaController
            }
        }

        updateMediaMetadata(duration)
        updatePlaybackState(isPlaying, position)
    }

    private fun updateMediaMetadata(duration: Long) {
        if (mediaSession == null) return
        val metadataBuilder = MediaMetadata.Builder()
        metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        
        // Dummy art is sometimes required by Samsung/Pixel
        val dummyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, dummyBitmap)
        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, dummyBitmap)

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        if (mediaSession == null) return
        val stateBuilder = PlaybackState.Builder()
        
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SEEK_TO 

        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (isPlaying) 1.0f else 0.0f

        stateBuilder.setActions(actions)
        stateBuilder.setState(state, position, speed)
        
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    private fun updatePipParams(isPlaying: Boolean, position: Long, duration: Long) {
        this.isPlayingState = isPlaying
        
        if (mediaSession != null) {
            if (duration >= 0) updateMediaMetadata(duration)
            val posToUse = if (position >= 0) position else 0L 
            updatePlaybackState(isPlaying, posToUse)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setActions(buildRemoteActions(isPlaying))
                    .build()
                activity?.setPictureInPictureParams(params)
            } catch (e: Exception) {
                // Activity might be gone
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildRemoteActions(isPlaying: Boolean): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        
        val iconId = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (isPlaying) "Pause" else "Play"
        val controlType = if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY

        val intent = Intent(ACTION_PIP_CONTROL).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CONTROL_TYPE, controlType)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, controlType, intent, flags)
        val icon = Icon.createWithResource(context, iconId)
        
        actions.add(RemoteAction(icon, title, title, pendingIntent))
        return actions
    }
    
    // --- Lifecycle and View Finding Logic ---

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        setupPipModeChangeListener(binding)
        registerReceiver()
    }

    override fun onDetachedFromActivity() {
        unregisterReceiver()
        releaseMediaSession()
        cleanupPipModeChangeListener()
        activity = null
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        setupPipModeChangeListener(binding)
        registerReceiver()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        unregisterReceiver()
        activity = null
        activityBinding = null
    }

    private fun registerReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val filter = IntentFilter(ACTION_PIP_CONTROL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(pipBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(pipBroadcastReceiver, filter)
            }
        }
    }

    private fun unregisterReceiver() {
        try {
            context.unregisterReceiver(pipBroadcastReceiver)
        } catch (e: Exception) {}
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }
    
    private fun setupPipModeChangeListener(binding: ActivityPluginBinding) {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            componentCallback = object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    val newPipState = activity?.isInPictureInPictureMode ?: false
                    if (newPipState != isInPipMode) {
                        isInPipMode = newPipState
                        if (!isInPipMode) releaseMediaSession()
                        channel.invokeMethod("pipModeChanged", mapOf("isInPipMode" to isInPipMode))
                    }
                }
                override fun onLowMemory() {}
            }
            activity?.registerComponentCallbacks(componentCallback)
        }
    }

    private fun cleanupPipModeChangeListener() {
        componentCallback?.let { activity?.unregisterComponentCallbacks(it) }
        componentCallback = null
    }
    
    private fun findVideoPlayerView(playerId: Int): View? {
        if (activity == null) return null
        val rootView = activity?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        return findVideoPlayerViewRecursively(rootView, playerId)
    }

    private fun findVideoPlayerViewRecursively(view: View?, playerId: Int, depth: Int = 0): View? {
        if (view == null) return null
        if (view.tag != null && view.tag is String && (view.tag as String).contains("$playerId")) return view
        if (view is SurfaceView) {
            val parent = view.parent as? View
            if (isFlutterPlatformView(parent)) return view
        }
        if (isFlutterPlatformView(view) && view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is SurfaceView) return child
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findVideoPlayerViewRecursively(view.getChildAt(i), playerId, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isFlutterPlatformView(view: View?): Boolean {
        if (view == null) return false
        val className = view.javaClass.name
        return className.contains("PlatformView") && !className.contains("Factory")
    }
}