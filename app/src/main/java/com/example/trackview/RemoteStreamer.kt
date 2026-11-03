package com.example.trackview

import android.content.Context
import androidx.camera.core.ImageProxy
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * البث من أي مكان بتقنية WebRTC (صورة + صوت المايك).
 */
class RemoteStreamer(
    context: Context,
    private val dbUrl: String,
    private val room: String
) {

    companion object {
        private const val MAX_SESSIONS = 3
        private const val MIN_FRAME_GAP_MS = 66L // تقريبا 15 صورة بالثانية
    }

    private val appContext = context.applicationContext

    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var lastFrameMs = 0L

    private val netExecutor = Executors.newFixedThreadPool(4)
    private val sessions = ConcurrentHashMap<String, PeerConnection>()
    private val seenSessions = HashSet<String>()
    private val activeViewers = AtomicInteger(0)

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    /** تحديثات الحالة تنعرض على الشاشة */
    @Volatile var onStatus: ((String) -> Unit)? = null

    // ------------------------------------------------------------------
    // التشغيل والإيقاف
    // ------------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                initWebRtc()
                try {
                    http("DELETE", "rooms/$room.json", null)
                } catch (e: Exception) {
                }
                postStatus("جاهز للمشاهدة من أي مكان")
                pollJoinRequests()
            } catch (e: Exception) {
                postStatus("خطأ في البث عن بعد: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        ready = false
        for (pc in sessions.values) {
            try {
                pc.close()
            } catch (e: Exception) {
            }
        }
        Thread {
            try {
                http("DELETE", "rooms/$room.json", null)
            } catch (e: Exception) {
            }
            var waited = 0
            while (sessions.isNotEmpty() && waited < 30) {
                try {
                    Thread.sleep(100)
                } catch (e: Exception) {
                }
                waited++
            }
            try {
                videoTrack?.dispose()
            } catch (e: Exception) {
            }
            try {
                videoSource?.dispose()
            } catch (e: Exception) {
            }
            try {
                audioTrack?.dispose()
            } catch (e: Exception) {
            }
            try {
                audioSource?.dispose()
            } catch (e: Exception) {
            }
            try {
                factory?.dispose()
            } catch (e: Exception) {
            }
            try {
                eglBase?.release()
            } catch (e: Exception) {
            }
            netExecutor.shutdown()
        }.start()
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val egl = EglBase.create()
        eglBase = egl

        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f

        val vSource = f.createVideoSource(false)
        vSource.capturerObserver.onCapturerStarted(true)
        videoSource = vSource
        videoTrack = f.createVideoTrack("video0", vSource)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val aSource = f.createAudioSource(audioConstraints)
        audioSource = aSource
        audioTrack = f.createAudioTrack("audio0", aSource)

        ready = true
    }

    // ------------------------------------------------------------------
    // استقبال طلبات المشاهدين
    // ------------------------------------------------------------------

    private fun pollJoinRequests() {
        try {
            http("DELETE", "rooms/$room/join.json", null)
            http("DELETE", "rooms/$room/s.json", null)
        } catch (e: Exception) {}

        while (running) {
            try {
                val body = http("GET", "rooms/$room/join.json", null)
                if (!body.isNullOrBlank() && body != "null") {
                    val obj = JSONObject(body)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val sid = keys.next()
                        
                        netExecutor.execute {
                            try {
                                http("DELETE", "rooms/$room/join/$sid.json", null)
                            } catch (e: Exception) {}
                        }

                        // إغلاق أي جلسة سابقة معلقة فور وصول طلب جديد
                        if (sessions.isNotEmpty()) {
                            for ((oldSid, pc) in sessions) {
                                try {
                                    pc.close()
                                } catch (e: Exception) {}
                                sessions.remove(oldSid)
                                seenSessions.remove(oldSid)
                                netExecutor.execute {
                                    try {
                                        http("DELETE", "rooms/$room/s/$oldSid.json", null)
                                    } catch (e: Exception) {}
                                }
                            }
                        }

                        seenSessions.remove(sid)
                        if (seenSessions.add(sid)) {
                            Thread { runSession(sid) }.start()
                        }
                    }
                } else {
                    if (sessions.isEmpty() && running) {
                        try {
                            http("DELETE", "rooms/$room/s.json", null)
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
            }
            sleepQuiet(400)
        }
    }

    // ------------------------------------------------------------------
    // جلسة مشاهد واحد
    // ------------------------------------------------------------------

    private fun runSession(sid: String) {
        val f = factory ?: return
        val vTrack = videoTrack ?: return
        val aTrack = audioTrack
        val base = "rooms/$room/s/$sid"

        val connected = AtomicBoolean(false)
        val ended = AtomicBoolean(false)
        val disconnectedAt = AtomicLong(0L)

        val pc = f.createPeerConnection(rtcConfig(), object : SimplePcObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val json = JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
                netExecutor.execute {
                    try {
                        http("POST", "$base/c.json", json.toString())
                    } catch (e: Exception) {
                    }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        connected.set(true)
                        disconnectedAt.set(0L)
                        postStatus("مشاهد متصل ✅")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        disconnectedAt.set(System.currentTimeMillis())
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        ended.set(true)
                    }
                    else -> {
                    }
                }
            }
        } ) ?: return

        sessions[sid] = pc
        activeViewers.incrementAndGet()
        postStatus("مشاهد جديد يتصل…")

        try {
            pc.addTrack(vTrack, listOf("stream0"))
            if (aTrack != null) pc.addTrack(aTrack, listOf("stream0"))

            val offerLatch = CountDownLatch(1)
            val offerRef = AtomicReference<SessionDescription?>(null)
            pc.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    offerRef.set(sdp)
                    offerLatch.countDown()
                }

                override fun onCreateFailure(error: String?) {
                    offerLatch.countDown()
                }
            }, MediaConstraints())
            offerLatch.await(10, TimeUnit.SECONDS)
            val offer = offerRef.get() ?: return

            val localLatch = CountDownLatch(1)
            pc.setLocalDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    localLatch.countDown()
                }

                override fun onSetFailure(error: String?) {
                    localLatch.countDown()
                }
            }, offer)
            localLatch.await(10, TimeUnit.SECONDS)

            val offerJson = JSONObject()
                .put("type", "offer")
                .put("sdp", offer.description)
            http("PUT", "$base/offer.json", offerJson.toString())

            val seenCandidates = HashSet<String>()
            var remoteSet = false
            val deadline = System.currentTimeMillis() + 60_000L

            while (running && !ended.get()) {
                try {
                    if (!remoteSet) {
                        val a = http("GET", "$base/answer.json", null)
                        if (!a.isNullOrBlank() && a != "null") {
                            val answerSdp = JSONObject(a).getString("sdp")
                            val remoteLatch = CountDownLatch(1)
                            pc.setRemoteDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    remoteLatch.countDown()
                                }

                                override fun onSetFailure(error: String?) {
                                    remoteLatch.countDown()
                                }
                            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                            remoteLatch.await(10, TimeUnit.SECONDS)
                            remoteSet = true
                        }
                    } else {
                        val v = http("GET", "$base/v.json", null)
                        if (!v.isNullOrBlank() && v != "null") {
                            val obj = JSONObject(v)
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (seenCandidates.add(key)) {
                                    val c = obj.getJSONObject(key)
                                    pc.addIceCandidate(
                                        IceCandidate(
                                            c.optString("sdpMid"),
                                            c.optInt("sdpMLineIndex"),
                                            c.getString("candidate")
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                }

                val now = System.currentTimeMillis()
                if (!connected.get() && now > deadline) break

                val d = disconnectedAt.get()
                if (d != 0L && now - d > 10_000L) break

                sleepQuiet(if (connected.get()) 2000L else 700L)
            }
        } catch (e: Exception) {
            postStatus("خطأ في اتصال مشاهد: ${e.message}")
        } finally {
            try {
                pc.close()
            } catch (e: Exception) {
            }
            try {
                pc.dispose()
            } catch (e: Exception) {
            }
            sessions.remove(sid)
            activeViewers.decrementAndGet()
            try {
                http("DELETE", "$base.json", null)
                http("DELETE", "rooms/$room/join/$sid.json", null)
            } catch (e: Exception) {
            }
            if (running) postStatus("جاهز للمشاهدة من أي مكان")
        }
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
    }

    // ------------------------------------------------------------------
    // إرسال صور الكاميرا
    // ------------------------------------------------------------------

    fun pushFrame(image: ImageProxy) {
        if (!ready || activeViewers.get() == 0) return

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFrameMs < MIN_FRAME_GAP_MS) return
        lastFrameMs = nowMs

        val source = videoSource ?: return
        val w = image.width
        val h = image.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2

        val buffer = JavaI420Buffer.allocate(w, h)
        copyPlane(image.planes[0], w, h, buffer.dataY, buffer.strideY)
        copyPlane(image.planes[1], cw, ch, buffer.dataU, buffer.strideU)
        copyPlane(image.planes[2], cw, ch, buffer.dataV, buffer.strideV)

        val frame = VideoFrame(buffer, image.imageInfo.rotationDegrees, System.nanoTime())
        source.capturerObserver.onFrameCaptured(frame)
        frame.release()
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        dst: ByteBuffer,
        dstStride: Int
    ) {
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            val srcRow = y * rowStride
            val dstRow = y * dstStride
            for (x in 0 until width) {
                dst.put(dstRow + x, src.get(srcRow + x * pixelStride))
            }
        }
    }

    // ------------------------------------------------------------------
    // أدوات مساعدة
    // ------------------------------------------------------------------

    private fun postStatus(message: String) {
        onStatus?.invoke(message)
    }

    private fun sleepQuiet(ms: Long) {
        var left = ms
        while (left > 0 && running) {
            try {
                Thread.sleep(minOf(100L, left))
            } catch (e: InterruptedException) {
                return
            }
            left -= 100L
        }
    }

    private fun http(method: String, path: String, body: String?): String? {
        val conn = URL("$dbUrl/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

open class SimplePcObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
}
