package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Pure Kotlin OpalCast v1.1 TLS Peer Receiver Client (100% wire-compatible with
 * `OpalCastProtocol.java` & `RemoteStreamServer.java`):
 * - TLS 1.2/1.3 + 6-digit PIN / saved authToken authentication
 * - Hardware-accelerated HEVC / H.264 video decoding directly onto a GPU [Surface]
 * - Hardware AAC-LC audio decoding to low-latency [AudioTrack]
 * - Full multi-touch, mouse (hover/buttons/scroll), key, and system-action back-channel
 */
class OpalCastPeerClient(
    private val host: String,
    private val port: Int,
    private val pinCode: String,
    private val savedAuthToken: String,
    private val clientId: String,
    private val reqWidth: Int,
    private val reqHeight: Int,
    private val reqDpi: Int,
    private val reqFps: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onAuthenticated(peerName: String, authToken: String, certFingerprint: String)
        fun onVideoSizeChanged(width: Int, height: Int, fps: Int)
        fun onError(message: String)
        fun onDisconnected(reason: String)
    }

    companion object {
        private const val TAG = "OpalPeerClient"
        private const val HEADER_SIZE = 18
        private const val MAX_PAYLOAD_LEN = 16 * 1024 * 1024

        private const val TYPE_VIDEO_CONFIG = 0x01
        private const val TYPE_VIDEO_FRAME = 0x02
        private const val TYPE_AUDIO_CONFIG = 0x03
        private const val TYPE_AUDIO_FRAME = 0x04
        private const val TYPE_AUTH_RESULT = 0x12

        private const val TYPE_HELLO = 0x20
        private const val TYPE_REQUEST_IDR = 0x22
        private const val TYPE_INPUT_MOUSE = 0x23
        private const val TYPE_INPUT_TOUCH = 0x24
        private const val TYPE_INPUT_KEY = 0x25
        private const val TYPE_SYSTEM_ACTION = 0x27
        private const val TYPE_PING = 0x2F

        private const val CODEC_H264 = 1
        private const val CODEC_HEVC = 2
        private const val FLAG_VIDEO_KEYFRAME = 0x01

        const val SYS_HOME = 1
        const val SYS_BACK = 2
        const val SYS_RECENTS = 3
        const val SYS_TOGGLE_DISPLAY_MODE = 4
        const val SYS_DISCONNECT = 8
    }

    private data class OutPacket(val type: Int, val flags: Int, val tsUs: Long, val payload: ByteArray)

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val outQueue = LinkedBlockingQueue<OutPacket>(256)

    @Volatile
    private var outputSurface: Surface? = null

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    var remoteWidth: Int = reqWidth
        private set

    @Volatile
    var remoteHeight: Int = reqHeight
        private set

    private val videoLock = Any()
    private var videoCodec: MediaCodec? = null
    private var videoMime: String = MediaFormat.MIMETYPE_VIDEO_HEVC
    private var pendingCsd: ByteArray? = null
    private var videoConfigured = false

    private val audioLock = Any()
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    fun attachSurface(surface: Surface?) {
        outputSurface = surface
        if (surface != null && surface.isValid) {
            synchronized(videoLock) {
                if (pendingCsd != null && !videoConfigured) {
                    configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, pendingCsd!!)
                }
            }
            requestKeyFrame()
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::ioLoop, "OpalPeerClientRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try {
            enqueue(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_DISCONNECT, 0))
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        releaseDecoders()
    }

    fun requestKeyFrame() {
        enqueue(TYPE_REQUEST_IDR, 0, ByteArray(0))
    }

    fun sendSystemAction(action: Int, arg: Int = 0) {
        enqueue(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(action, arg))
    }

    fun sendKeyEvent(action: Int, keyCode: Int, metaState: Int, repeat: Int = 0) {
        val bb = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        bb.put((action and 0xFF).toByte())
        bb.putInt(keyCode)
        bb.putInt(metaState)
        bb.putInt(repeat)
        enqueue(TYPE_INPUT_KEY, 0, bb.array())
    }

    fun sendTouchEvent(ev: MotionEvent, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val count = ev.pointerCount.coerceIn(1, 10)
        val bb = ByteBuffer.allocate(3 + count * 16).order(ByteOrder.BIG_ENDIAN)
        bb.put((ev.actionMasked and 0xFF).toByte())
        bb.put((ev.actionIndex and 0xFF).toByte())
        bb.put((count and 0xFF).toByte())
        val rw = remoteWidth.coerceAtLeast(2).toFloat()
        val rh = remoteHeight.coerceAtLeast(2).toFloat()
        for (i in 0 until count) {
            val rx = (ev.getX(i) * (rw / viewW)).coerceIn(0f, rw - 1f)
            val ry = (ev.getY(i) * (rh / viewH)).coerceIn(0f, rh - 1f)
            bb.putInt(ev.getPointerId(i))
            bb.putFloat(rx)
            bb.putFloat(ry)
            bb.putFloat(ev.getPressure(i).coerceIn(0f, 1f))
        }
        enqueue(TYPE_INPUT_TOUCH, 0, bb.array())
    }

    fun sendMouseEvent(ev: MotionEvent, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val rw = remoteWidth.coerceAtLeast(2).toFloat()
        val rh = remoteHeight.coerceAtLeast(2).toFloat()
        val rx = (ev.x * (rw / viewW)).coerceIn(0f, rw - 1f)
        val ry = (ev.y * (rh / viewH)).coerceIn(0f, rh - 1f)
        val masked = ev.actionMasked
        val mouseAction = when (masked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> 1 // MOUSE_BUTTON_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> 2 // MOUSE_BUTTON_UP
            MotionEvent.ACTION_SCROLL -> 3 // MOUSE_SCROLL
            MotionEvent.ACTION_HOVER_EXIT -> 4 // MOUSE_HOVER_EXIT
            else -> 0 // MOUSE_MOVE_ABS
        }
        val actBtn = if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
            ev.actionButton
        } else if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_UP) {
            if (ev.buttonState != 0) ev.buttonState else MotionEvent.BUTTON_PRIMARY
        } else {
            0
        }
        val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
        val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f

        val bb = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
        bb.put((mouseAction and 0xFF).toByte())
        bb.putFloat(rx)
        bb.putFloat(ry)
        bb.putInt(ev.buttonState)
        bb.putInt(actBtn)
        bb.putFloat(hScroll)
        bb.putFloat(vScroll)
        enqueue(TYPE_INPUT_MOUSE, 0, bb.array())
    }

    fun isMouseMotion(ev: MotionEvent): Boolean =
        (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    private fun enqueue(type: Int, flags: Int, payload: ByteArray) {
        if (closed.get()) return
        outQueue.offer(OutPacket(type, flags, SystemClock.elapsedRealtimeNanos() / 1000L, payload))
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun openTlsSocket(): Pair<SSLSocket, String> {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        val ssl = ctx.socketFactory.createSocket() as SSLSocket
        ssl.tcpNoDelay = true
        ssl.connect(InetSocketAddress(host, port), 6_000)
        ssl.soTimeout = 10_000
        ssl.startHandshake()
        val peerCert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
        val fp = if (peerCert != null) sha256Hex(peerCert.encoded) else ""
        ssl.soTimeout = 0
        return ssl to fp
    }

    private fun ioLoop() {
        var disconnectReason = "已断开连接"
        try {
            val (ssl, peerFp) = openTlsSocket()
            socket = ssl
            val input = BufferedInputStream(ssl.inputStream, 131_072)
            val output = BufferedOutputStream(ssl.outputStream, 65_536)

            // 1. Send OpalCast v1.1 HELLO
            val helloJson = JSONObject().apply {
                put("protocolVersion", 1)
                put("clientName", Build.MODEL ?: "Android")
                put("clientId", clientId)
                put("preferredCodec", "hevc")
                put("width", reqWidth)
                put("height", reqHeight)
                put("dpi", reqDpi)
                put("fps", reqFps)
                put("bitrateKbps", 16000)
                put("displayMode", 0) // DEX virtual desktop mode
                put("audioEnabled", true)
                put("authToken", savedAuthToken)
                put("pinCode", pinCode.trim())
                put("transportHint", "wifi_tls")
                put("sessionRole", "desktop")
            }.toString().toByteArray(StandardCharsets.UTF_8)
            writeFrame(output, TYPE_HELLO, 0, SystemClock.elapsedRealtimeNanos() / 1000L, helloJson)
            output.flush()

            // 2. Start dedicated sender + keepalive thread
            val txThread = Thread({ sendLoop(output) }, "OpalPeerClientTx").apply {
                isDaemon = true
                start()
            }

            // 3. Receive loop
            while (running.get() && !closed.get()) {
                val frame = readFrame(input) ?: break
                handleDownlinkFrame(frame, peerFp)
            }
            txThread.interrupt()
        } catch (t: Throwable) {
            if (!closed.get()) {
                val msg = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Peer cast session error: $msg")
                disconnectReason = msg
                listener.onError(msg)
            }
        } finally {
            running.set(false)
            closed.set(true)
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            releaseDecoders()
            listener.onDisconnected(disconnectReason)
        }
    }

    private fun sendLoop(out: OutputStream) {
        var lastPingMs = SystemClock.uptimeMillis()
        var pingSeq = 1L
        try {
            while (running.get() && !closed.get()) {
                val pkt = outQueue.poll(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (pkt != null) {
                    writeFrame(out, pkt.type, pkt.flags, pkt.tsUs, pkt.payload)
                    out.flush()
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastPingMs >= 4_000L) {
                    lastPingMs = now
                    val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(pingSeq++)
                    writeFrame(out, TYPE_PING, 0, now * 1000L, bb.array())
                    out.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleDownlinkFrame(frame: RxFrame, peerFp: String) {
        when (frame.type) {
            TYPE_AUTH_RESULT -> {
                if (frame.payload.isEmpty()) return
                val ok = frame.payload[0].toInt() != 0
                val jsonText = if (frame.payload.size > 1) {
                    String(frame.payload, 1, frame.payload.size - 1, StandardCharsets.UTF_8)
                } else {
                    "{}"
                }
                val obj = try { JSONObject(jsonText) } catch (_: Throwable) { JSONObject() }
                if (ok) {
                    val peerName = obj.optString("deviceName", host)
                    val token = obj.optString("authToken", "")
                    val fp = obj.optString("certFingerprint", peerFp)
                    listener.onAuthenticated(peerName, token, fp)
                } else {
                    val err = obj.optString("message", "配对 PIN 校验失败")
                    listener.onError(err)
                    stop()
                }
            }
            TYPE_VIDEO_CONFIG -> handleVideoConfig(frame.payload)
            TYPE_VIDEO_FRAME -> handleVideoFrame(frame.payload, frame.flags, frame.timestampUs)
            TYPE_AUDIO_CONFIG -> handleAudioConfig(frame.payload)
            TYPE_AUDIO_FRAME -> handleAudioFrame(frame.payload, frame.timestampUs)
        }
    }

    private fun handleVideoConfig(payload: ByteArray) {
        if (payload.size < 11) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val codecId = bb.get().toInt() and 0xFF
        val w = bb.short.toInt() and 0xFFFF
        val h = bb.short.toInt() and 0xFFFF
        bb.short // dpi
        bb.get() // rotation
        val fpsX100 = bb.short.toInt() and 0xFFFF
        bb.get() // displayMode
        val csd = if (payload.size > 11) payload.copyOfRange(11, payload.size) else ByteArray(0)
        val mime = if (codecId == CODEC_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        remoteWidth = w.coerceAtLeast(2)
        remoteHeight = h.coerceAtLeast(2)
        listener.onVideoSizeChanged(remoteWidth, remoteHeight, (fpsX100 / 100).coerceAtLeast(30))

        synchronized(videoLock) {
            videoMime = mime
            pendingCsd = csd
            configureVideoDecoderLocked(mime, remoteWidth, remoteHeight, csd)
        }
    }

    private fun configureVideoDecoderLocked(mime: String, width: Int, height: Int, csd: ByteArray) {
        val surf = outputSurface
        if (surf == null || !surf.isValid) {
            videoConfigured = false
            return
        }
        releaseVideoDecoderLocked()
        try {
            val codec = MediaCodec.createDecoderByType(mime)
            val fmt = MediaFormat.createVideoFormat(mime, width, height)
            if (Build.VERSION.SDK_INT >= 30) {
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (csd.isNotEmpty()) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            codec.configure(fmt, surf, null, 0)
            codec.start()
            videoCodec = codec
            videoConfigured = true
        } catch (t: Throwable) {
            Log.w(TAG, "Video decoder configure failed ($mime ${width}x${height}): ${t.message}")
            releaseVideoDecoderLocked()
        }
    }

    private fun handleVideoFrame(payload: ByteArray, flags: Int, tsUs: Long) {
        if (payload.isEmpty()) return
        synchronized(videoLock) {
            val codec = videoCodec ?: return
            try {
                val inIdx = codec.dequeueInputBuffer(8_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null && buf.capacity() >= payload.size) {
                        buf.clear()
                        buf.put(payload)
                        val mcFlags = if ((flags and FLAG_VIDEO_KEYFRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        codec.queueInputBuffer(inIdx, 0, payload.size, tsUs, mcFlags)
                    }
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx >= 0) {
                        codec.releaseOutputBuffer(outIdx, true)
                    } else {
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Video decode frame error: ${t.message}")
                videoConfigured = false
            }
        }
    }

    private fun handleAudioConfig(payload: ByteArray) {
        if (payload.size < 6) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        bb.get() // codec (AAC-LC)
        val sampleRate = bb.int.coerceIn(16_000, 96_000)
        val channels = (bb.get().toInt() and 0xFF).coerceIn(1, 2)
        val asc = if (payload.size > 6) payload.copyOfRange(6, payload.size) else ByteArray(0)

        synchronized(audioLock) {
            releaseAudioDecoderLocked()
            try {
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                if (asc.isNotEmpty()) {
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                }
                codec.configure(fmt, null, null, 0)
                codec.start()

                val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(4096)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                audioCodec = codec
                audioTrack = track
            } catch (t: Throwable) {
                Log.w(TAG, "Audio decoder init failed: ${t.message}")
                releaseAudioDecoderLocked()
            }
        }
    }

    private fun handleAudioFrame(payload: ByteArray, tsUs: Long) {
        if (payload.isEmpty()) return
        synchronized(audioLock) {
            val codec = audioCodec ?: return
            val track = audioTrack ?: return
            try {
                val inIdx = codec.dequeueInputBuffer(4_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null && buf.capacity() >= payload.size) {
                        buf.clear()
                        buf.put(payload)
                        codec.queueInputBuffer(inIdx, 0, payload.size, tsUs, 0)
                    }
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            track.write(outBuf, info.size, AudioTrack.WRITE_NON_BLOCKING)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseDecoders() {
        synchronized(videoLock) { releaseVideoDecoderLocked() }
        synchronized(audioLock) { releaseAudioDecoderLocked() }
    }

    private fun releaseVideoDecoderLocked() {
        videoConfigured = false
        val c = videoCodec
        videoCodec = null
        if (c != null) {
            try { c.stop() } catch (_: Throwable) {}
            try { c.release() } catch (_: Throwable) {}
        }
    }

    private fun releaseAudioDecoderLocked() {
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            try { t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
        }
        val c = audioCodec
        audioCodec = null
        if (c != null) {
            try { c.stop() } catch (_: Throwable) {}
            try { c.release() } catch (_: Throwable) {}
        }
    }

    private data class RxFrame(val type: Int, val flags: Int, val timestampUs: Long, val payload: ByteArray)

    private fun writeFrame(out: OutputStream, type: Int, flags: Int, tsUs: Long, payload: ByteArray) {
        val hdr = ByteArray(HEADER_SIZE)
        hdr[0] = 0x4F // 'O'
        hdr[1] = 0x50 // 'P'
        hdr[2] = 0x44 // 'D'
        hdr[3] = 0x31 // '1'
        hdr[4] = (type and 0xFF).toByte()
        hdr[5] = (flags and 0xFF).toByte()
        val len = payload.size
        hdr[6] = ((len ushr 24) and 0xFF).toByte()
        hdr[7] = ((len ushr 16) and 0xFF).toByte()
        hdr[8] = ((len ushr 8) and 0xFF).toByte()
        hdr[9] = (len and 0xFF).toByte()
        hdr[10] = ((tsUs ushr 56) and 0xFF).toByte()
        hdr[11] = ((tsUs ushr 48) and 0xFF).toByte()
        hdr[12] = ((tsUs ushr 40) and 0xFF).toByte()
        hdr[13] = ((tsUs ushr 32) and 0xFF).toByte()
        hdr[14] = ((tsUs ushr 24) and 0xFF).toByte()
        hdr[15] = ((tsUs ushr 16) and 0xFF).toByte()
        hdr[16] = ((tsUs ushr 8) and 0xFF).toByte()
        hdr[17] = (tsUs and 0xFF).toByte()
        out.write(hdr)
        if (len > 0) out.write(payload)
    }

    private fun readFrame(input: InputStream): RxFrame? {
        val hdr = ByteArray(HEADER_SIZE)
        val first = input.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        readFully(input, hdr, 1, HEADER_SIZE - 1)
        if (hdr[0] != 0x4F.toByte() || hdr[1] != 0x50.toByte()
            || hdr[2] != 0x44.toByte() || hdr[3] != 0x31.toByte()
        ) {
            throw IOException("Invalid OpalCast frame magic")
        }
        val type = hdr[4].toInt() and 0xFF
        val flags = hdr[5].toInt() and 0xFF
        val len = ((hdr[6].toInt() and 0xFF) shl 24) or
            ((hdr[7].toInt() and 0xFF) shl 16) or
            ((hdr[8].toInt() and 0xFF) shl 8) or
            (hdr[9].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD_LEN) {
            throw IOException("Invalid OpalCast payload length: $len")
        }
        val tsUs = ((hdr[10].toLong() and 0xFF) shl 56) or
            ((hdr[11].toLong() and 0xFF) shl 48) or
            ((hdr[12].toLong() and 0xFF) shl 40) or
            ((hdr[13].toLong() and 0xFF) shl 32) or
            ((hdr[14].toLong() and 0xFF) shl 24) or
            ((hdr[15].toLong() and 0xFF) shl 16) or
            ((hdr[16].toLong() and 0xFF) shl 8) or
            (hdr[17].toLong() and 0xFF)
        val payload = if (len == 0) ByteArray(0) else ByteArray(len).also { readFully(input, it, 0, len) }
        return RxFrame(type, flags, tsUs, payload)
    }

    private fun readFully(input: InputStream, dst: ByteArray, off: Int, len: Int) {
        var pos = off
        var rem = len
        while (rem > 0) {
            val n = input.read(dst, pos, rem)
            if (n < 0) throw EOFException("Unexpected EOF in OpalCast stream")
            pos += n
            rem -= n
        }
    }

    private fun encodeSystemAction(action: Int, arg: Int): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put((action and 0xFF).toByte())
            .putInt(arg)
            .array()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
