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
import android.graphics.drawable.Icon
import android.media.MediaMetadata
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

/** VideoPlayerPipPlugin */
class VideoPlayerPipPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {
  private val TAG = "VideoPlayerPipPlugin"
  
  private lateinit var channel: MethodChannel
  private lateinit var context: Context
  private var activity: Activity? = null
  private var isInPipMode = false
  private var activityBinding: ActivityPluginBinding? = null
  private var componentCallback: android.content.ComponentCallbacks? = null
  
  // Cache of player ID to view mappings
  private val playerViewCache = mutableMapOf<Int, View?>()
  
  private var activePlayerId: Int? = null
  private var pipRequestedByUser = false

  // --- Constants for Media Controls ---
  private val ACTION_PIP_CONTROL = "uz.flutterwithakmaljon.video_player_pip.MEDIA_CONTROL"
  private val EXTRA_CONTROL_TYPE = "control_type"
  private val CONTROL_TYPE_PLAY = 1
  private val CONTROL_TYPE_PAUSE = 2
  
  // Track current state to update icons efficiently
  private var isPlayingState = true 
  
  // --- NEW: MediaSession for Seek Bar Support ---
  private var mediaSession: MediaSession? = null

  // --- BroadcastReceiver to handle button clicks on the PiP window ---
  private val pipBroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent == null || intent.action != ACTION_PIP_CONTROL) return

      val type = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
      when (type) {
        CONTROL_TYPE_PLAY -> {
          channel.invokeMethod("pipAction", "play")
          // Optimistically update UI
          updatePipParams(true, 0, 0) 
        }
        CONTROL_TYPE_PAUSE -> {
          channel.invokeMethod("pipAction", "pause")
          updatePipParams(false, 0, 0)
        }
      }
    }
  }

  // --- NEW: MediaSession Callback for Seeking ---
  private val mediaSessionCallback = object : MediaSession.Callback() {
    override fun onSeekTo(pos: Long) {
        super.onSeekTo(pos)
        // Send the seek request back to Flutter
        // "seekTo" needs to be handled in your Dart MethodChannel listener
        channel.invokeMethod("seekTo", pos)
        
        // Optimistically update local state so the bar jumps immediately
        updateMediaSessionState(isPlayingState, pos, -1)
    }
  }

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "video_player_pip")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
    Log.d(TAG, "Plugin attached to engine")
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "isPipSupported" -> {
        result.success(isPipSupported())
      }
      "enterPipMode" -> {
        val playerId = call.argument<Int>("playerId")
        val width = call.argument<Int>("width")
        val height = call.argument<Int>("height")
        val isPlaying = call.argument<Boolean>("isPlaying") ?: true
        
        // Get initial position/duration if provided, else 0
        val currentPosition = (call.argument<Number>("currentPosition")?.toLong()) ?: 0L
        val duration = (call.argument<Number>("duration")?.toLong()) ?: 0L

        if (playerId != null) {
          activePlayerId = playerId
          pipRequestedByUser = true
          val success = enterPipMode(playerId, width, height, isPlaying, currentPosition, duration)
          result.success(success)
        } else {
          result.error("INVALID_ARGUMENT", "Player ID is required", null)
        }
      }
      "exitPipMode" -> {
        pipRequestedByUser = false
        val success = exitPipMode()
        if (success) activePlayerId = null
        result.success(success)
      }
      "isInPipMode" -> {
        result.success(isInPipMode)
      }
      "updatePipUi" -> {
         // Flutter calls this constantly or on state change
         val isPlaying = call.argument<Boolean>("isPlaying") ?: false
         val position = (call.argument<Number>("position")?.toLong()) ?: 0L
         val duration = (call.argument<Number>("duration")?.toLong()) ?: 0L
         
         updatePipParams(isPlaying, position, duration)
         result.success(null)
      }
      else -> {
        result.notImplemented()
      }
    }
  }
  
  private fun isPipSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
  }
  
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
      // 1. Initialize the MediaSession (Required for Seek Bar)
      initMediaSession()
      updateMediaSessionMetadata(duration)
      updateMediaSessionState(isPlaying, currentPosition, duration)

      if (!playerViewCache.containsKey(playerId)) {
        playerViewCache.clear()
      }
      
      val videoView = playerViewCache.getOrPut(playerId) { findVideoPlayerView(playerId) }
      if (videoView == null) return false
      
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val width = customWidth ?: videoView.width
        val height = customHeight ?: videoView.height
        
        // Aspect Ratio Logic
        val aspectRatio = if (width > 0 && height > 0) {
          val currentRatio = width.toFloat() / height.toFloat()
          if (currentRatio >= 0.418410f && currentRatio <= 2.390000f) {
            Rational(width, height)
          } else {
            Rational(16, 9)
          }
        } else {
          Rational(16, 9)
        }
        
        val paramsBuilder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(buildRemoteActions(isPlaying))
        
        // Android 12+ Improvements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val location = IntArray(2)
            videoView.getLocationInWindow(location)
            val sourceRectHint = android.graphics.Rect(
                location[0], location[1],
                location[0] + videoView.width,
                location[1] + videoView.height
            )
            paramsBuilder.setSourceRectHint(sourceRectHint)
            paramsBuilder.setSeamlessResizeEnabled(true)
            paramsBuilder.setAutoEnterEnabled(true)
        }
        
        val params = paramsBuilder.build()
        val result = activity?.enterPictureInPictureMode(params) ?: false
        return result
      }
      return false
    } catch (e: Exception) {
      Log.e(TAG, "Error entering PiP mode", e)
      return false
    }
  }

  /**
   * Updates the PiP controls (Buttons AND Seek Bar)
   */
  private fun updatePipParams(isPlaying: Boolean, position: Long, duration: Long) {
    // Update the MediaSession (The Seek Bar)
    updateMediaSessionState(isPlaying, position, duration)
    if (duration > 0) {
        updateMediaSessionMetadata(duration)
    }

    // Update the RemoteActions