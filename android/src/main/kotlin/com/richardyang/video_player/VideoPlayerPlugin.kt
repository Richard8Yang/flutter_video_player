// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package com.richardyang.video_player

import android.os.Build
import android.util.Log
import android.util.LongSparseArray
import android.opengl.*
import android.content.Context
import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.EventChannel
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.view.TextureRegistry

/** Android platform implementation of the VideoPlayerPlugin.  */
class VideoPlayerPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var registry: TextureRegistry
    private lateinit var context: Context
    private lateinit var messenger: BinaryMessenger;
    private var activity: Activity? = null

    private val videoPlayers = LongSparseArray<VideoPlayer>()
    private val options = VideoPlayerOptions()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "video_player")
        channel.setMethodCallHandler(this)
        registry = binding.textureRegistry
        context = binding.applicationContext
        messenger = binding.binaryMessenger;
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        disposeAllPlayers()
        //releaseCache()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // TODO: the Activity your plugin was attached to was
        // destroyed to change configuration.
        // This call will be followed by onReattachedToActivityForConfigChanges().
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        // TODO: your plugin is now attached to a new Activity
        // after a configuration change.
    }

    override fun onDetachedFromActivity() {
        // TODO: your plugin is no longer associated with an Activity.
        // Clean up references.
        // TODO: Stop all players and get them disposed
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
         when (call.method) {
            "video.init" -> initialize()
            "video.create" -> {
                val videoTex = registry.createSurfaceTexture()
                val eventChannel = EventChannel(messenger, "videoPlayer/videoEvents" + videoTex.id())
                val mixWithOthers = call.argument<Boolean?>("mixWithOthers")
                val allowBgPlayback = call.argument<Boolean?>("allowBackgroundPlayback")
                if (mixWithOthers != null) {
                    options.mixWithOthers = mixWithOthers
                }
                if (allowBgPlayback != null) {
                    options.allowBackgroundPlayback = allowBgPlayback
                }
                // create player with share EGL context if specified
                val player = VideoPlayer(context, eventChannel, videoTex, options)
                videoPlayers.put(videoTex.id(), player)

                val reply: MutableMap<String, Any> = HashMap()
                reply["textureId"] = videoTex.id()
                reply["sharedTextureId"] = player.getOffScreenTextureId()   // returns shared offscreen texture
                Log.d("PLAYER", "Onscreen texture %d, Offscreen texture %d".format(videoTex.id(), player.getOffScreenTextureId()))
                result.success(reply)
            }
            //"video.preCache" -> preCache(call, result)
            //"video.stopPreCache" -> stopPreCache(call, result)
            //"video.clearCache" -> clearCache(result)
            else -> {
                val textureId = (call.argument<Any>("textureId") as Number?)!!.toLong()
                handlePlayerControlCmd(call, result, textureId)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getParameter(parameters: Map<String, Any?>, key: String, defaultValue: T): T {
        if (parameters.containsKey(key)) {
            val value = parameters[key]
            if (value != null) {
                return value as T
            }
        }
        return defaultValue
    }

    private fun handlePlayerControlCmd(
        call: MethodCall,
        result: MethodChannel.Result,
        textureId: Long
    ) {
        val player = videoPlayers[textureId]
        if (player == null) {
            result.error(
                "Unknown textureId",
                "No video player associated with texture id $textureId",
                null
            )
            return
        }
        when (call.method) {
            "video.player.setDataSource" -> {
                val dataSrcDesc = call.argument<Map<String, Any?>>("dataSource")!!
                val key = getParameter(dataSrcDesc, "key", "")
                val uri = getParameter(dataSrcDesc, "uri", "")
                val formatHint = getParameter<String?>(dataSrcDesc, "formatHint", null)
                //val asset = getParameter(dataSrcDesc, "asset", "")
                if (uri != null) {
                    player.setDataSource(uri, formatHint)
                    result.success(null)
                } else {
                    result.error(
                        "Uri is not specified",
                        "Please specify a non-null uri",
                        null
                    )
                }
            }
            "video.player.play" -> {
                //setupNotification(player)
                player.play()
                result.success(null)
            }
            "video.player.pause" -> {
                player.pause()
                result.success(null)
            }
            "video.player.setLooping" -> {
                player.setLooping(call.argument("looping")!!)
                result.success(null)
            }
            "video.player.setVolume" -> {
                player.setVolume(call.argument("volume")!!)
                result.success(null)
            }
            "video.player.setPlaybackSpeed" -> {
                player.setPlaybackSpeed(call.argument("speed")!!)
                result.success(null)
            }
            "video.player.seekToPos" -> {
                val pos = (call.argument<Any>("position") as Number?)!!.toInt()
                player.seekTo(pos)
                result.success(null)
            }
            "video.player.getPos" -> {
                result.success(player.position)
                player.sendBufferingUpdate()
            }
            "video.player.getAbsolutePos" -> result.success(player.absolutePosition)
            // "video.player.setTrackParams" -> {
            //     player.setTrackParameters(
            //         call.argument(WIDTH_PARAMETER)!!,
            //         call.argument(HEIGHT_PARAMETER)!!,
            //         call.argument(BITRATE_PARAMETER)!!
            //     )
            //     result.success(null)
            // }
            // "video.player.setAudioTrack" -> {
            //     val name = call.argument<String?>(NAME_PARAMETER)
            //     val index = call.argument<Int?>(INDEX_PARAMETER)
            //     if (name != null && index != null) {
            //         player.setAudioTrack(name, index)
            //     }
            //     result.success(null)
            // }
            "video.player.setMixWithOthers" -> {
                val mixWitOthers = call.argument<Boolean?>("mixWithOthers")
                if (mixWitOthers != null) {
                    options.mixWithOthers = mixWitOthers
                    player.setMixWithOthers(mixWitOthers)
                }
            }
            "video.player.dispose" -> {
                player.dispose()
                videoPlayers.remove(textureId)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun disposeAllPlayers() {
        for (i in 0 until videoPlayers.size()) {
            videoPlayers.valueAt(i).dispose()
        }
        videoPlayers.clear()
    }

    private fun onDestroy() {
        // The whole FlutterView is being destroyed. Here we release resources acquired for all
        // instances
        // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
        // be replaced with just asserting that videoPlayers.isEmpty().
        // https://github.com/flutter/flutter/issues/20989 tracks this.
        disposeAllPlayers()
    }

    private fun initialize() {
        disposeAllPlayers()
    }
}