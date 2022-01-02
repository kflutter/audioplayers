package xyz.luan.audioplayers

import android.content.Context
import android.os.Handler
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import xyz.luan.audioplayers.player.Player
import xyz.luan.audioplayers.player.WrappedMediaPlayer
import xyz.luan.audioplayers.player.WrappedSoundPool
import java.lang.ref.WeakReference

class AudioplayersPlugin : MethodCallHandler, FlutterPlugin {
    private lateinit var channel: MethodChannel
    private lateinit var loggerChannel: MethodChannel
    private lateinit var context: Context

    private val mediaPlayers = mutableMapOf<String, Player>()
    private val handler = Handler()
    private var positionUpdates: Runnable? = null

    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "xyz.luan/audioplayers")
        loggerChannel = MethodChannel(binding.binaryMessenger, "xyz.luan/audioplayers.logger")
        context = binding.applicationContext
        channel.setMethodCallHandler(this)
        loggerChannel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {}
    override fun onMethodCall(call: MethodCall, response: MethodChannel.Result) {
        try {
            handleMethodCall(call, response)
        } catch (e: Exception) {
            Logger.error("Unexpected error!", e)
            response.error("Unexpected error!", e.message, e)
        }
    }

    private fun handleMethodCall(call: MethodCall, response: MethodChannel.Result) {
        when (call.method) {
            "changeLogLevel" -> {
                val value = call.enumArgument<LogLevel>("value")
                    ?: throw error("value is required")
                Logger.logLevel = value
                response.success(1)
                return
            }
        }
        val playerId = call.argument<String>("playerId") ?: return
        val mode = call.argument<String>("mode")
        val player = getPlayer(playerId, mode)
        when (call.method) {
            "setSourceUrl" -> {
                val url = call.argument<String>("url") ?: throw error("url is required")
                val isLocal = call.argument<Boolean>("isLocal") ?: false
                player.setUrl(url, isLocal)
            }
            "setSourceBytes" -> {
                val bytes = call.argument<ByteArray>("bytes") ?: throw error("bytes are required")
                player.setDataSource(ByteDataSource(bytes))
            }
            "resume" -> player.play()
            "pause" -> player.pause()
            "stop" -> player.stop()
            "release" -> player.release()
            "seek" -> {
                val position = call.argument<Int>("position") ?: throw error("position is required")
                player.seek(position)
            }
            "setVolume" -> {
                val volume = call.argument<Double>("volume") ?: throw error("volume is required")
                player.setVolume(volume)
            }
            "setPlaybackRate" -> {
                val rate = call.argument<Double>("playbackRate") ?: throw error("playbackRate is required")
                player.setRate(rate)
            }
            "getDuration" -> {
                response.success(player.getDuration() ?: 0)
                return
            }
            "getCurrentPosition" -> {
                response.success(player.getCurrentPosition() ?: 0)
                return
            }
            "setReleaseMode" -> {
                val releaseMode = call.enumArgument<ReleaseMode>("releaseMode")
                    ?: throw error("releaseMode is required")
                player.setReleaseMode(releaseMode)
            }
            else -> {
                response.notImplemented()
                return
            }
        }
        response.success(1)
    }

    private fun configureAttributesAndVolume(
        call: MethodCall,
        player: Player
    ) {
        val respectSilence = call.argument<Boolean>("respectSilence") ?: false
        val stayAwake = call.argument<Boolean>("stayAwake") ?: false
        val duckAudio = call.argument<Boolean>("duckAudio") ?: false
        player.configAttributes(respectSilence, stayAwake, duckAudio)

        val volume = call.argument<Double>("volume") ?: 1.0
        player.setVolume(volume)
    }

    private fun getPlayer(playerId: String, mode: String?): Player {
        return mediaPlayers.getOrPut(playerId) {
            when (mode) {
                null, "PlayerMode.MEDIA_PLAYER" -> WrappedMediaPlayer(this, playerId)
                "PlayerMode.LOW_LATENCY" -> WrappedSoundPool(playerId)
                else -> throw error("Unknown PlayerMode $mode")
            }
        }
    }

    fun getApplicationContext(): Context {
        return context.applicationContext
    }

    fun handleIsPlaying() {
        startPositionUpdates()
    }

    fun handleDuration(player: Player) {
        channel.invokeMethod("audio.onDuration", buildArguments(player.playerId, player.getDuration() ?: 0))
    }

    fun handleComplete(player: Player) {
        channel.invokeMethod("audio.onComplete", buildArguments(player.playerId))
    }

    fun handleError(player: Player, message: String) {
        channel.invokeMethod("audio.onError", buildArguments(player.playerId, message))
    }

    fun handleSeekComplete() {
        channel.invokeMethod("audio.onSeekComplete", buildArguments(player.playerId))
    }

    private fun startPositionUpdates() {
        if (positionUpdates != null) {
            return
        }
        positionUpdates = UpdateCallback(mediaPlayers, channel, handler, this).also {
            handler.post(it)
        }
    }

    private fun stopPositionUpdates() {
        positionUpdates = null
        handler.removeCallbacksAndMessages(null)
    }

    private class UpdateCallback(
        mediaPlayers: Map<String, Player>,
        channel: MethodChannel,
        handler: Handler,
        audioplayersPlugin: AudioplayersPlugin
    ) : Runnable {
        private val mediaPlayers = WeakReference(mediaPlayers)
        private val channel = WeakReference(channel)
        private val handler = WeakReference(handler)
        private val audioplayersPlugin = WeakReference(audioplayersPlugin)

        override fun run() {
            val mediaPlayers = mediaPlayers.get()
            val channel = channel.get()
            val handler = handler.get()
            val audioplayersPlugin = audioplayersPlugin.get()
            if (mediaPlayers == null || channel == null || handler == null || audioplayersPlugin == null) {
                audioplayersPlugin?.stopPositionUpdates()
                return
            }
            var nonePlaying = true
            for (player in mediaPlayers.values) {
                if (!player.isActuallyPlaying()) {
                    continue
                }
                try {
                    nonePlaying = false
                    val key = player.playerId
                    val duration = player.getDuration()
                    val time = player.getCurrentPosition()
                    channel.invokeMethod("audio.onDuration", buildArguments(key, duration ?: 0))
                    channel.invokeMethod("audio.onCurrentPosition", buildArguments(key, time ?: 0))
                } catch (e: UnsupportedOperationException) {
                }
            }
            if (nonePlaying) {
                audioplayersPlugin.stopPositionUpdates()
            } else {
                handler.postDelayed(this, 200)
            }
        }

    }

    companion object {
        private fun buildArguments(playerId: String, value: Any? = null): Map<String, Any> {
            return listOfNotNull(
                    "playerId" to playerId,
                    value?.let { "value" to it },
            ).toMap()
        }

        private fun error(message: String): Exception {
            return IllegalArgumentException(message)
        }
    }
}

private inline fun <reified T: Enum<T>> MethodCall.enumArgument(name: String): T? {
    val enumName = argument<String>(name) ?: return null
    return enumValueOf<T>(enumName.split('.').last())
}
