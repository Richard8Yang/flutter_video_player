// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'video_player_platform_interface.dart';

const MethodChannel _channel = MethodChannel('video_player');

/// An implementation of [VideoPlayerPlatform] that uses method channels.
class MethodChannelVideoPlayer extends VideoPlayerPlatform {
  @override
  Future<void> init() {
    return _channel.invokeMethod<void>('video.init');
  }

  @override
  Future<List<int?>> create(
      {mixWithOthers = false, allowBackgroundPlayback = false}) async {
    var args = Map<String, dynamic>();
    args['mixWithOthers'] = mixWithOthers;
    args['allowBackgroundPlayback'] = allowBackgroundPlayback;
    print("Creating player with args: $args");

    final responseLinkedHashMap =
        await _channel.invokeMethod<Map?>('video.create', args);

    final Map<String, dynamic>? response = responseLinkedHashMap != null
        ? Map<String, dynamic>.from(responseLinkedHashMap)
        : null;
    // Return the shared offscreen texture if shared EGL context is specified
    final texId = response?['textureId'] as int?;
    final sharedTexId = response?['sharedTextureId'] as int?;
    List<int?> ret = [texId, sharedTexId];
    return ret;
  }

  @override
  Future<void> dispose(int? textureId) {
    return _channel.invokeMethod<void>(
      'video.player.dispose',
      <String, dynamic>{'textureId': textureId},
    );
  }

  @override
  Stream<VideoEvent> videoEventsFor(int textureId) {
    return _eventChannelFor(textureId)
        .receiveBroadcastStream()
        .map((dynamic event) {
      final Map<dynamic, dynamic> map = event as Map<dynamic, dynamic>;
      switch (map['event']) {
        case 'initialized':
          return VideoEvent(
            eventType: VideoEventType.initialized,
            duration: Duration(milliseconds: map['duration'] as int),
            size: Size((map['width'] as num?)?.toDouble() ?? 0.0,
                (map['height'] as num?)?.toDouble() ?? 0.0),
            rotationCorrection: map['rotationCorrection'] as int? ?? 0,
          );
        case 'completed':
          return VideoEvent(
            eventType: VideoEventType.completed,
          );
        case 'bufferingUpdate':
          final List<dynamic> values = map['values'] as List<dynamic>;
          return VideoEvent(
            buffered: values.map<DurationRange>(_toDurationRange).toList(),
            eventType: VideoEventType.bufferingUpdate,
          );
        case 'bufferingStart':
          return VideoEvent(eventType: VideoEventType.bufferingStart);
        case 'bufferingEnd':
          return VideoEvent(eventType: VideoEventType.bufferingEnd);
        default:
          return VideoEvent(eventType: VideoEventType.unknown);
      }
    });
  }

  @override
  Future<void> setDataSource(int? textureId, DataSource dataSource) async {
    Map<String, dynamic>? dataSourceDescription;
    switch (dataSource.sourceType) {
      case DataSourceType.asset:
        dataSourceDescription = <String, dynamic>{
          'key': dataSource.key,
          'asset': dataSource.asset,
          'package': dataSource.package,
          'useCache': false,
          'maxCacheSize': 0,
          'maxCacheFileSize': 0,
        };
        break;
      case DataSourceType.network:
        dataSourceDescription = <String, dynamic>{
          'key': dataSource.key,
          'uri': dataSource.uri,
          'formatHint': dataSource.rawFormalHint,
          // 'headers': dataSource.headers,
          // 'useCache': dataSource.useCache,
          // 'maxCacheSize': dataSource.maxCacheSize,
          // 'maxCacheFileSize': dataSource.maxCacheFileSize,
          // 'cacheKey': dataSource.cacheKey,
        };
        break;
      case DataSourceType.file:
        dataSourceDescription = <String, dynamic>{
          'key': dataSource.key,
          'uri': dataSource.uri,
          'useCache': false,
          'maxCacheSize': 0,
          'maxCacheFileSize': 0,
        };
        break;
      case DataSourceType.contentUri:
        // TODO: Handle this case.
        break;
    }
    await _channel.invokeMethod<void>(
      'video.player.setDataSource',
      <String, dynamic>{
        'textureId': textureId,
        'dataSource': dataSourceDescription,
      },
    );
    return;
  }

  @override
  Future<void> play(int? textureId) {
    return _channel.invokeMethod<void>(
      'video.player.play',
      <String, dynamic>{'textureId': textureId},
    );
  }

  @override
  Future<void> pause(int? textureId) {
    return _channel.invokeMethod<void>(
      'video.player.pause',
      <String, dynamic>{'textureId': textureId},
    );
  }

  @override
  Future<void> setLooping(int? textureId, bool looping) {
    return _channel.invokeMethod<void>(
      'video.player.setLooping',
      <String, dynamic>{
        'textureId': textureId,
        'looping': looping,
      },
    );
  }

  @override
  Future<void> setVolume(int? textureId, double volume) {
    return _channel.invokeMethod<void>(
      'video.player.setVolume',
      <String, dynamic>{
        'textureId': textureId,
        'volume': volume,
      },
    );
  }

  @override
  Future<void> setPlaybackSpeed(int? textureId, double speed) {
    return _channel.invokeMethod<void>(
      'video.player.setPlaybackSpeed',
      <String, dynamic>{
        'textureId': textureId,
        'speed': speed,
      },
    );
  }

  @override
  Future<void> seekTo(int? textureId, Duration? position) {
    return _channel.invokeMethod<void>(
      'video.player.seekToPos',
      <String, dynamic>{
        'textureId': textureId,
        'location': position!.inMilliseconds,
      },
    );
  }

  @override
  Future<Duration> getPosition(int? textureId) async {
    return Duration(
        milliseconds: await _channel.invokeMethod<int>(
              'video.player.getPos',
              <String, dynamic>{'textureId': textureId},
            ) ??
            0);
  }

  @override
  Future<DateTime?> getAbsolutePosition(int? textureId) async {
    final int milliseconds = await _channel.invokeMethod<int>(
          'video.player.getAbsolutePos',
          <String, dynamic>{'textureId': textureId},
        ) ??
        0;

    if (milliseconds <= 0) return null;

    return DateTime.fromMillisecondsSinceEpoch(milliseconds);
  }

  @override
  Future<void> setMixWithOthers(int? textureId, bool mixWithOthers) {
    return _channel.invokeMethod<void>(
      'video.player.setMixWithOthers',
      <String, dynamic>{
        'textureId': textureId,
        'mixWithOthers': mixWithOthers,
      },
    );
  }

  // @override
  // Future<void> clearCache() {
  //   return _channel.invokeMethod<void>(
  //     'video.clearCache',
  //     <String, dynamic>{},
  //   );
  // }

  // @override
  // Future<void> preCache(DataSource dataSource, int preCacheSize) {
  //   final Map<String, dynamic> dataSourceDescription = <String, dynamic>{
  //     'key': dataSource.key,
  //     'uri': dataSource.uri,
  //     'certificateUrl': dataSource.certificateUrl,
  //     'headers': dataSource.headers,
  //     'maxCacheSize': dataSource.maxCacheSize,
  //     'maxCacheFileSize': dataSource.maxCacheFileSize,
  //     'preCacheSize': preCacheSize,
  //     'cacheKey': dataSource.cacheKey,
  //     'videoExtension': dataSource.videoExtension,
  //   };
  //   return _channel.invokeMethod<void>(
  //     'video.preCache',
  //     <String, dynamic>{
  //       'dataSource': dataSourceDescription,
  //     },
  //   );
  // }

  // @override
  // Future<void> stopPreCache(String url, String? cacheKey) {
  //   return _channel.invokeMethod<void>(
  //     'video.stopPreCache',
  //     <String, dynamic>{'url': url, 'cacheKey': cacheKey},
  //   );
  // }

  @override
  Widget buildView(int? textureId) {
    return Texture(textureId: textureId!);
  }

  EventChannel _eventChannelFor(int? textureId) {
    return EventChannel('videoPlayer/videoEvents$textureId');
  }

  DurationRange _toDurationRange(dynamic value) {
    final List<dynamic> pair = value as List;
    return DurationRange(
      Duration(milliseconds: pair[0] as int),
      Duration(milliseconds: pair[1] as int),
    );
  }
}
