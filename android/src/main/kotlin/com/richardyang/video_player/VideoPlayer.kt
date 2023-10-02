// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package com.richardyang.video_player

import android.opengl.*
import android.content.Context
import android.net.Uri
import android.view.Surface
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.Listener
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.video.VideoSize
import io.flutter.plugin.common.EventChannel
import io.flutter.view.TextureRegistry
import kotlin.math.max
import kotlin.math.min

internal class VideoPlayer {
    private var exoPlayer: ExoPlayer? = null
    private var surface: Surface? = null
    private val textureEntry: TextureRegistry.SurfaceTextureEntry
    private var eventSink: QueuingEventSink? = null
    private val eventChannel: EventChannel
    private val context: Context

    @VisibleForTesting
    var isInitialized = false
    private val options: VideoPlayerOptions

    private var renderer: VideoRender? = null

    constructor(
        context: Context,
        eventChannel: EventChannel,
        textureEntry: TextureRegistry.SurfaceTextureEntry,
        options: VideoPlayerOptions
    ) {
        this.eventChannel = eventChannel
        this.textureEntry = textureEntry
        this.context = context
        this.options = options
        val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
        setUpVideoPlayer(exoPlayer, QueuingEventSink())
    }

    // Constructor used to directly test members of this class.
    // @VisibleForTesting
    // constructor(
    //     exoPlayer: ExoPlayer,
    //     eventChannel: EventChannel,
    //     textureEntry: TextureRegistry.SurfaceTextureEntry,
    //     options: VideoPlayerOptions,
    //     eventSink: QueuingEventSink?
    // ) {
    //     this.eventChannel = eventChannel
    //     this.textureEntry = textureEntry
    //     this.options = options
    //     setUpVideoPlayer(exoPlayer, eventSink, EGL14.EGL_NO_CONTEXT)
    // }

    fun getOffScreenTextureId() : Int {
        return renderer?.offScreenTextureId()!!
    }

    private fun buildMediaSource(
        uri: Uri, mediaDataSourceFactory: DataSource.Factory, formatHint: String?, context: Context
    ): MediaSource {
        val type: Int = if (formatHint == null) {
            Util.inferContentType(uri)
        } else {
            when (formatHint) {
                FORMAT_SS -> C.CONTENT_TYPE_SS
                FORMAT_DASH -> C.CONTENT_TYPE_DASH
                FORMAT_HLS -> C.CONTENT_TYPE_HLS
                FORMAT_OTHER -> C.CONTENT_TYPE_OTHER
                else -> -1
            }
        }
        return when (type) {
            C.CONTENT_TYPE_SS -> SsMediaSource.Factory(
                DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            )
                .createMediaSource(MediaItem.fromUri(uri))
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            )
                .createMediaSource(MediaItem.fromUri(uri))
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(mediaDataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
            C.CONTENT_TYPE_OTHER -> ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun setUpVideoPlayer(exoPlayer: ExoPlayer, eventSink: QueuingEventSink?) {
        this.exoPlayer = exoPlayer
        this.eventSink = eventSink
        eventChannel.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(o: Any?, sink: EventChannel.EventSink) {
                    eventSink?.setDelegate(sink)
                }
                override fun onCancel(o: Any?) {
                    eventSink?.setDelegate(null)
                }
            })
        // Create opengl renderer with textureEntry
        if (renderer != null) {
            renderer?.dispose()
        }
        renderer = VideoRender(this.textureEntry)
        surface = Surface(renderer?.srcSurfaceTex)
        //surface = Surface(textureEntry.surfaceTexture())
        exoPlayer.setVideoSurface(surface)
        setAudioAttributes(exoPlayer, options.mixWithOthers)
        //setAudioAttributes(exoPlayer, options.allowBackgroundPlayback)    // TODO: how to set this option?
        exoPlayer.addListener(
            object : Player.Listener {
                private var isBuffering = false
                fun setBuffering(buffering: Boolean) {
                    if (isBuffering != buffering) {
                        isBuffering = buffering
                        val event: MutableMap<String, Any> = HashMap()
                        event["event"] = if (isBuffering) "bufferingStart" else "bufferingEnd"
                        eventSink?.success(event)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        setBuffering(true)
                        sendBufferingUpdate()
                    } else if (playbackState == Player.STATE_READY) {
                        if (!isInitialized) {
                            isInitialized = true
                            sendInitialized()
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        val event: MutableMap<String, Any> = HashMap()
                        event["event"] = "completed"
                        eventSink?.success(event)
                    }
                    if (playbackState != Player.STATE_BUFFERING) {
                        setBuffering(false)
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // Video frame size changed
                    Log.d("INFO ", "Video size changed to: %d x %d".format(videoSize.width, videoSize.height))
                    textureEntry.surfaceTexture().setDefaultBufferSize(videoSize.width, videoSize.height)
                    renderer?.updateTextureSize(videoSize.width, videoSize.height)
                }

                override fun onPlayerError(error: PlaybackException) {
                    setBuffering(false)
                    eventSink?.error("VideoError", "Video player had error $error", null)
                }
            })
    }

    fun sendBufferingUpdate() {
        val event: MutableMap<String, Any> = HashMap()
        event["event"] = "bufferingUpdate"
        val range: List<Number?> = listOf(0, exoPlayer!!.getBufferedPosition())
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event["values"] = listOf(range)
        eventSink?.success(event)
    }

    fun setDataSource(dataSource: String?, formatHint: String?) {
        val uri: Uri = Uri.parse(dataSource)
        val dataSourceFactory: DataSource.Factory = if (isHTTP(uri)) {
            val httpDataSourceFactory: DefaultHttpDataSource.Factory =
                DefaultHttpDataSource.Factory()
                    .setUserAgent("ExoPlayer")
                    .setAllowCrossProtocolRedirects(true)
            // if (httpHeaders != null && httpHeaders.isNotEmpty()) {
            //     httpDataSourceFactory.setDefaultRequestProperties(httpHeaders)
            // }
            httpDataSourceFactory
        } else {
            DefaultDataSource.Factory(context)
        }
        val mediaSource: MediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, context)
        exoPlayer!!.setMediaSource(mediaSource)
        exoPlayer!!.prepare()
    }

    fun play() {
        exoPlayer!!.setPlayWhenReady(true)
    }

    fun pause() {
        exoPlayer!!.setPlayWhenReady(false)
    }

    fun setLooping(value: Boolean) {
        exoPlayer!!.setRepeatMode(if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF)
    }

    fun setVolume(value: Double) {
        val bracketedValue = max(0.0, min(1.0, value)).toFloat()
        exoPlayer!!.setVolume(bracketedValue)
    }

    fun setPlaybackSpeed(value: Double) {
        // We do not need to consider pitch and skipSilence for now as we do not handle them and
        // therefore never diverge from the default values.
        val playbackParameters = PlaybackParameters(value.toFloat())
        exoPlayer!!.setPlaybackParameters(playbackParameters)
    }

    fun seekTo(location: Int) {
        exoPlayer!!.seekTo(location.toLong())
    }

    val position: Long
        get() = exoPlayer!!.getCurrentPosition()

    val absolutePosition: Long
        get() {
            val timeline = exoPlayer!!.currentTimeline
            if (!timeline.isEmpty) {
                val windowStartTimeMs = timeline.getWindow(0, Timeline.Window()).windowStartTimeMs
                val pos = exoPlayer!!.getCurrentPosition()
                return windowStartTimeMs + pos
            }
            return exoPlayer!!.currentPosition
        }

    @VisibleForTesting
    fun sendInitialized() {
        if (isInitialized) {
            val event: MutableMap<String, Any> = HashMap()
            event["event"] = "initialized"
            event["duration"] = exoPlayer!!.getDuration()
            if (exoPlayer!!.getVideoFormat() != null) {
                val videoFormat: Format = exoPlayer!!.getVideoFormat()!!
                var width = videoFormat.width
                var height = videoFormat.height
                val rotationDegrees = videoFormat.rotationDegrees
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = videoFormat.height
                    height = videoFormat.width
                }
                event["width"] = width
                event["height"] = height

                // Rotating the video with ExoPlayer does not seem to be possible with a Surface,
                // so inform the Flutter code that the widget needs to be rotated to prevent
                // upside-down playback for videos with rotationDegrees of 180 (other orientations work
                // correctly without correction).
                if (rotationDegrees == 180) {
                    event["rotationCorrection"] = rotationDegrees
                }
            }
            eventSink?.success(event)
        }
    }

    fun setMixWithOthers(mixWithOthers: Boolean) {
        val audioComponent = exoPlayer!!.audioComponent ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            audioComponent.setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build(),
                !mixWithOthers
            )
        } else {
            audioComponent.setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MUSIC).build(),
                !mixWithOthers
            )
        }
    }

    fun dispose() {
        if (isInitialized) {
            exoPlayer!!.stop()
        }
        textureEntry.release()
        eventChannel.setStreamHandler(null)
        if (surface != null) {
            surface!!.release()
        }
        if (exoPlayer != null) {
            exoPlayer!!.release()
        }
    }

    companion object {
        private const val FORMAT_SS = "ss"
        private const val FORMAT_DASH = "dash"
        private const val FORMAT_HLS = "hls"
        private const val FORMAT_OTHER = "other"
        private fun isHTTP(uri: Uri?): Boolean {
            if (uri == null || uri.getScheme() == null) {
                return false
            }
            val scheme: String = uri!!.getScheme()!!
            return scheme == "http" || scheme == "https"
        }

        private fun setAudioAttributes(exoPlayer: ExoPlayer, isMixMode: Boolean) {
            exoPlayer.setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !isMixMode
            )
        }
    }
}