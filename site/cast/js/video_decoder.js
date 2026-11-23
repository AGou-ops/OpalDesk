/**
 * WebCodecs hardware-accelerated low-latency H.264 / HEVC video decoder & <canvas> renderer.
 */

import {
  annexBToLengthPrefixed,
  buildAvcDecoderConfig,
  buildHevcDecoderConfig,
  extractParameterSets,
  splitNalUnits,
} from './annexb.js';
import { FLAG_VIDEO_KEYFRAME, VIDEO_CODEC_H264, VIDEO_CODEC_HEVC } from './protocol.js';

const MAX_DECODE_QUEUE = 4;

export async function probeVideoCodecs() {
  if (typeof VideoDecoder === 'undefined') {
    return { supported: false, h264: false, hevc: false };
  }
  const check = async (codec) => {
    try {
      const res = await VideoDecoder.isConfigSupported({
        codec,
        codedWidth: 1920,
        codedHeight: 1080,
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware',
      });
      return Boolean(res && res.supported);
    } catch {
      return false;
    }
  };
  const h264 = (await check('avc1.64002a')) || (await check('avc1.42e01f'));
  const hevc = await check('hvc1.1.6.L120.90');
  return { supported: h264 || hevc, h264, hevc };
}

export class OpalVideoDecoder {
  constructor(canvas, { onResize, onRequestIdr, onStats, onError } = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    this.onResize = onResize || (() => {});
    this.onRequestIdr = onRequestIdr || (() => {});
    this.onStats = onStats || (() => {});
    this.onError = onError || (() => {});

    this.decoder = null;
    this.codecId = VIDEO_CODEC_H264;
    this.width = 0;
    this.height = 0;
    this.awaitingKeyFrame = true;
    this.pendingFrame = null;
    this.rafId = 0;

    this.framesInWindow = 0;
    this.fpsWindowStart = performance.now();
    this.currentFps = 0;
    this.droppedFrames = 0;
    this.lastIdrReqMs = 0;
  }

  configure(videoConfig) {
    if (typeof VideoDecoder === 'undefined') {
      this.onError('当前浏览器不支持 WebCodecs VideoDecoder，请使用最新版 Chrome / Edge / Firefox');
      return false;
    }
    this.codecId = videoConfig.codec;
    this.width = videoConfig.width;
    this.height = videoConfig.height;

    const units = splitNalUnits(videoConfig.csdAnnexB, this.codecId);
    const ps = extractParameterSets(units, this.codecId);
    const cfgInfo =
      this.codecId === VIDEO_CODEC_HEVC
        ? buildHevcDecoderConfig(ps.vps, ps.sps, ps.pps)
        : buildAvcDecoderConfig(ps.sps, ps.pps);

    if (!cfgInfo) {
      this.onError('收到的视频参数集 (CSD) 不完整，正在请求关键帧重试…');
      this.requestIdrThrottled();
      return false;
    }

    if (this.canvas.width !== this.width || this.canvas.height !== this.height) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    this.onResize(this.width, this.height, videoConfig.fpsX100 / 100);

    try {
      if (!this.decoder || this.decoder.state === 'closed') {
        this.decoder = new VideoDecoder({
          output: (frame) => this.handleDecodedFrame(frame),
          error: (err) => {
            this.awaitingKeyFrame = true;
            this.onError(`视频解码异常: ${err?.message || err}`);
            this.requestIdrThrottled();
          },
        });
      }
      this.decoder.configure({
        codec: cfgInfo.codecString,
        codedWidth: this.width,
        codedHeight: this.height,
        description: cfgInfo.description,
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware',
      });
      this.awaitingKeyFrame = true;
      return true;
    } catch (err) {
      this.onError(`配置视频解码器失败 (${cfgInfo.codecString}): ${err?.message || err}`);
      return false;
    }
  }

  decodeFrame(annexB, flags, timestampUs) {
    if (!this.decoder || this.decoder.state !== 'configured') return;
    const { sample, hasKeySlice } = annexBToLengthPrefixed(annexB, this.codecId);
    if (sample.byteLength === 0) return;
    const isKey = (flags & FLAG_VIDEO_KEYFRAME) !== 0 || hasKeySlice;

    if (this.awaitingKeyFrame && !isKey) {
      this.requestIdrThrottled();
      return;
    }

    if (!isKey && this.decoder.decodeQueueSize >= MAX_DECODE_QUEUE) {
      this.droppedFrames++;
      this.awaitingKeyFrame = true;
      this.requestIdrThrottled();
      return;
    }

    try {
      const chunk = new EncodedVideoChunk({
        type: isKey ? 'key' : 'delta',
        timestamp: Number(timestampUs),
        data: sample,
      });
      this.decoder.decode(chunk);
      if (isKey) this.awaitingKeyFrame = false;
    } catch {
      this.awaitingKeyFrame = true;
      this.requestIdrThrottled();
    }
  }

  handleDecodedFrame(frame) {
    if (this.pendingFrame) {
      this.pendingFrame.close();
    }
    this.pendingFrame = frame;
    if (!this.rafId) {
      this.rafId = requestAnimationFrame(() => this.renderPending());
    }
  }

  renderPending() {
    this.rafId = 0;
    const frame = this.pendingFrame;
    this.pendingFrame = null;
    if (!frame) return;

    try {
      const fw = frame.displayWidth || frame.codedWidth;
      const fh = frame.displayHeight || frame.codedHeight;
      if (fw > 0 && fh > 0 && (this.canvas.width !== fw || this.canvas.height !== fh)) {
        this.canvas.width = fw;
        this.canvas.height = fh;
        this.width = fw;
        this.height = fh;
        this.onResize(fw, fh, this.currentFps);
      }
      this.ctx.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
    } finally {
      frame.close();
    }

    this.framesInWindow++;
    const now = performance.now();
    const elapsed = now - this.fpsWindowStart;
    if (elapsed >= 1000) {
      this.currentFps = Math.round((this.framesInWindow * 1000) / elapsed);
      this.framesInWindow = 0;
      this.fpsWindowStart = now;
      this.onStats({
        fps: this.currentFps,
        width: this.width,
        height: this.height,
        queueSize: this.decoder ? this.decoder.decodeQueueSize : 0,
        dropped: this.droppedFrames,
      });
    }
  }

  requestIdrThrottled() {
    const now = performance.now();
    if (now - this.lastIdrReqMs >= 1000) {
      this.lastIdrReqMs = now;
      this.onRequestIdr();
    }
  }

  reset() {
    if (this.rafId) {
      cancelAnimationFrame(this.rafId);
      this.rafId = 0;
    }
    if (this.pendingFrame) {
      this.pendingFrame.close();
      this.pendingFrame = null;
    }
    if (this.decoder) {
      try {
        if (this.decoder.state !== 'closed') this.decoder.close();
      } catch {
        // Ignore teardown errors
      }
      this.decoder = null;
    }
    this.awaitingKeyFrame = true;
    this.framesInWindow = 0;
    this.currentFps = 0;
  }
}
