package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Philips Hue через Entertainment API: лампы зоны развлечений из приложения Hue получают цвет
 * потоком по DTLS на порт 2100 моста, до 25 раз в секунду, а не парой REST-команд в секунду.
 *
 * Каждая лампа берёт цвет того места кадра, где стоит в зоне: x из настроек зоны идёт слева
 * направо, z снизу вверх (пол, уровень ТВ, потолок). Глубина y на картинку не влияет.
 *
 * Градиентные ленты (OmniGlow, Play Gradient) в Entertainment API v1 приходят как одна лампа,
 * поэтому вся лента заливалась одним цветом. Если мост отдаёт конфигурацию CLIP v2, поток
 * идёт протоколом HueStream 2.0 и каждый канал/сегмент получает свой цвет.
 */
class HueEntertainmentClient(
    host: String,
    private val mUsername: String,
    private val mClientKey: String,
    private val mAreaId: String,
    private val mTurnOffOnStop: Boolean,
    private val mSmoothGradient: () -> Boolean = { false },
) : HyperionClient {

    internal class Light(val id: Int, val u: Float, val v: Float)

    private val mHost = host.trim()
    private val mBase = "http://$mHost/api/${mUsername.trim()}"
    private var mLights: List<Light> = emptyList()
    private var mStreamTargets: List<Light> = emptyList()
    private var mV2ConfigId: String? = null
    private var mSocket: DatagramSocket? = null
    private var mTransport: DTLSTransport? = null
    private var mMessage = ByteArray(0)
    private var mSequence = 0
    private var mDirty = false
    private val mTicker = Executors.newSingleThreadScheduledExecutor { Thread(it, "hue-stream-ticker") }

    @Volatile
    private var mLastSendMs = 0L

    @Volatile
    private var mConnected = false

    init {
        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        if (mClientKey.isBlank()) throw IOException("The Hue bridge was paired without a streaming key")
        mLights = readArea()
        val v2 = readV2Channels()
        if (v2 != null) {
            mV2ConfigId = v2.first
            mStreamTargets = v2.second
        } else {
            mStreamTargets = mLights
        }
        if (mStreamTargets.isEmpty()) throw IOException("The entertainment area has no lights")
        // Выключенную лампу поток не зажигает
        for (light in mLights) HueClient.request("PUT", "$mBase/lights/${light.id}/state", """{"on":true}""")
        try {
            setStreamActive(true)
        } catch (e: IOException) {
            if (mV2ConfigId == null) throw e
            Log.w(TAG, "Hue entertainment v2 stream failed, falling back to v1: ${e.message}")
            mV2ConfigId = null
            mStreamTargets = mLights
            setStreamActive(true)
        }
        try {
            val socket = DatagramSocket()
            socket.connect(InetAddress.getByName(mHost), STREAM_PORT)
            mSocket = socket
            mTransport = DTLSClientProtocol().connect(PskClient(mUsername.trim(), hexToBytes(mClientKey)), UDPTransport(socket, MTU))
        } catch (e: IOException) {
            closeTransport()
            setStreamActiveQuietly(false)
            throw IOException("DTLS handshake with the Hue bridge failed: ${e.message}", e)
        }
        mMessage = mV2ConfigId?.let { messageV2(mStreamTargets.size, it) } ?: message(mStreamTargets.size)
        mConnected = true
        // Кадр, пришедший раньше срока, уходит здесь: иначе последний цвет сцены терялся бы
        // до следующей смены картинки. Без сообщений десять секунд мост закрывает поток и
        // возвращает лампам прежний свет, поэтому и на статичной картинке раз в секунду повтор
        mTicker.scheduleWithFixedDelay({
            val idle = System.currentTimeMillis() - mLastSendMs
            if (idle >= KEEPALIVE_MS || idle >= FRAME_INTERVAL_MS && isDirty()) {
                try {
                    send()
                } catch (e: IOException) {
                    Log.w(TAG, "Hue stream send failed: ${e.message}")
                }
            }
        }, FRAME_INTERVAL_MS, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Hue entertainment stream to ${mStreamTargets.size} ${if (mV2ConfigId != null) "v2 channels" else "lights"}")
    }

    @Throws(IOException::class)
    private fun readArea(): List<Light> {
        val reply = HueClient.request("GET", "$mBase/groups/$mAreaId", null)
        val group = try {
            JSONObject(reply)
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from the Hue bridge", e)
        }
        if (group.optString("type") != "Entertainment") throw IOException("Hue group $mAreaId is not an entertainment area")
        val locations = group.optJSONObject("locations") ?: throw IOException("The entertainment area has no light positions")
        return locations.keys().asSequence().mapNotNull { id ->
            val position = locations.optJSONArray(id) ?: return@mapNotNull null
            val x = position.optDouble(0, 0.0).toFloat()
            val z = position.optDouble(2, 0.0).toFloat()
            val point = screenPoint(x, z)
            Light(id.toIntOrNull() ?: return@mapNotNull null, point[0], point[1])
        }.toList()
    }

    private fun readV2Channels(): Pair<String, List<Light>>? {
        return try {
            val reply = HueClient.requestV2(
                "GET",
                mHost,
                mUsername,
                "/clip/v2/resource/entertainment_configuration",
                null,
            )
            parseEntertainmentConfiguration(reply, mAreaId)
        } catch (e: IOException) {
            Log.w(TAG, "Hue entertainment v2 is unavailable: ${e.message}")
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Hue entertainment v2 reply was unreadable: ${e.message}")
            null
        }
    }

    override fun isConnected(): Boolean = mConnected

    @Throws(IOException::class)
    override fun disconnect() {
        mTicker.shutdownNow()
        if (mConnected) {
            fill { _, _ -> 0 }
            try {
                send()
            } catch (e: IOException) {
                Log.w(TAG, "Final black message failed: ${e.message}")
            }
        }
        mConnected = false
        closeTransport()
        setStreamActiveQuietly(false)
        if (mTurnOffOnStop) {
            for (light in mLights) {
                try {
                    HueClient.request("PUT", "$mBase/lights/${light.id}/state", """{"on":false}""")
                } catch (e: IOException) {
                    Log.w(TAG, "Could not switch off Hue light ${light.id}: ${e.message}")
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        if (!mConnected) return
        fill { _, _ -> 0 }
        send()
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(0)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (!mConnected) return
        fill { _, _ -> color and 0xFFFFFF }
        send()
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!mConnected || width <= 0 || height <= 0) return
        val colors = IntArray(mStreamTargets.size) { sample(data, width, height, mStreamTargets[it]) }
        val output = if (mSmoothGradient() && colors.size > 1) {
            HueGradientSmoother.apply(mStreamTargets, colors)
        } else {
            colors
        }
        fill { index, _ -> output[index] }
        if (System.currentTimeMillis() - mLastSendMs >= FRAME_INTERVAL_MS) send()
    }

    @Synchronized
    private fun fill(colorOf: (Int, Light) -> Int) {
        val v2 = mV2ConfigId != null
        for ((index, light) in mStreamTargets.withIndex()) {
            if (v2) writeChannel(mMessage, index, light.id, colorOf(index, light))
            else writeLight(mMessage, index, light.id, colorOf(index, light))
        }
        mDirty = true
    }

    @Synchronized
    private fun isDirty(): Boolean = mDirty

    @Synchronized
    @Throws(IOException::class)
    private fun send() {
        val transport = mTransport ?: return
        mMessage[SEQUENCE_OFFSET] = mSequence.toByte()
        mSequence = (mSequence + 1) and 0xFF
        transport.send(mMessage, 0, mMessage.size)
        mDirty = false
        mLastSendMs = System.currentTimeMillis()
    }

    private fun closeTransport() {
        try {
            mTransport?.close()
        } catch (e: IOException) {
            Log.w(TAG, "DTLS close failed: ${e.message}")
        }
        mTransport = null
        mSocket?.close()
        mSocket = null
    }

    @Throws(IOException::class)
    private fun setStreamActive(active: Boolean) {
        val configId = mV2ConfigId
        if (configId != null) {
            val action = if (active) "start" else "stop"
            HueClient.requestV2(
                "PUT",
                mHost,
                mUsername,
                "/clip/v2/resource/entertainment_configuration/$configId",
                """{"action":"$action"}""",
            )
        } else {
            HueClient.request("PUT", "$mBase/groups/$mAreaId", """{"stream":{"active":$active}}""")
        }
    }

    private fun setStreamActiveQuietly(active: Boolean) {
        try {
            setStreamActive(active)
        } catch (e: IOException) {
            Log.w(TAG, "Could not switch the Hue stream to $active: ${e.message}")
        }
    }

    /** Средний цвет квадрата кадра вокруг места лампы. */
    private fun sample(data: ByteArray, width: Int, height: Int, light: Light): Int {
        val half = maxOf(width, height) / 8
        val cx = (light.u * (width - 1)).toInt()
        val cy = (light.v * (height - 1)).toInt()
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in (cy - half).coerceAtLeast(0)..(cy + half).coerceAtMost(height - 1)) {
            var i = (y * width + (cx - half).coerceAtLeast(0)) * 3
            for (x in (cx - half).coerceAtLeast(0)..(cx + half).coerceAtMost(width - 1)) {
                if (i + 2 >= data.size) break
                r += data[i].toInt() and 0xFF
                g += data[i + 1].toInt() and 0xFF
                b += data[i + 2].toInt() and 0xFF
                count++
                i += 3
            }
        }
        if (count == 0) return 0
        return ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    }

    /**
     * Мосту нужны ровно DTLS 1.2 и PSK с AES-128-GCM; сертификатов у него нет. Шифры даёт
     * платформа (Conscrypt): BcTlsCrypto тянет за собой все движки Bouncy Castle, это +420 КБ dex.
     */
    private class PskClient(identity: String, psk: ByteArray) :
        PSKTlsClient(JcaTlsCryptoProvider().create(SecureRandom()), BasicTlsPSKIdentity(identity, psk)) {

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()

        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

        override fun getHandshakeTimeoutMillis(): Int = HANDSHAKE_TIMEOUT_MS
    }

    companion object {
        private const val TAG = "HueEntertainment"
        private const val STREAM_PORT = 2100
        private const val MTU = 1400
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val FRAME_INTERVAL_MS = 40L
        private const val KEEPALIVE_MS = 1000L
        private const val HEADER_SIZE = 16
        private const val LIGHT_SIZE = 9
        private const val V2_ID_SIZE = 36
        private const val V2_HEADER_SIZE = HEADER_SIZE + V2_ID_SIZE
        private const val V2_CHANNEL_SIZE = 7
        private const val SEQUENCE_OFFSET = 11

        /** Заголовок HueStream 1.0 и место под [lights] ламп, цвета в RGB. */
        internal fun message(lights: Int): ByteArray {
            val message = ByteArray(HEADER_SIZE + lights * LIGHT_SIZE)
            "HueStream".toByteArray(Charsets.US_ASCII).copyInto(message)
            message[9] = 0x01
            return message
        }

        /** Лампа [index] сообщения: тип «лампа», номер и RGB по 16 бит на канал. */
        internal fun writeLight(message: ByteArray, index: Int, id: Int, rgb: Int) {
            val offset = HEADER_SIZE + index * LIGHT_SIZE
            message[offset] = 0
            message[offset + 1] = (id shr 8).toByte()
            message[offset + 2] = id.toByte()
            for (channel in 0 until 3) {
                // 0..255 в 0..65535 без провала у верхней границы: 255 * 257 = 65535
                val value = ((rgb shr (16 - channel * 8)) and 0xFF) * 257
                message[offset + 3 + channel * 2] = (value shr 8).toByte()
                message[offset + 4 + channel * 2] = value.toByte()
            }
        }

        /** Заголовок HueStream 2.0: 16 байт плюс ASCII UUID зоны и каналы по 7 байт. */
        internal fun messageV2(channels: Int, configId: String): ByteArray {
            val message = ByteArray(V2_HEADER_SIZE + channels * V2_CHANNEL_SIZE)
            "HueStream".toByteArray(Charsets.US_ASCII).copyInto(message)
            message[9] = 0x02
            val id = configId.toByteArray(Charsets.US_ASCII)
            val length = minOf(id.size, V2_ID_SIZE)
            id.copyInto(message, HEADER_SIZE, 0, length)
            return message
        }

        internal fun writeChannel(message: ByteArray, index: Int, channelId: Int, rgb: Int) {
            val offset = V2_HEADER_SIZE + index * V2_CHANNEL_SIZE
            message[offset] = channelId.toByte()
            for (channel in 0 until 3) {
                val value = ((rgb shr (16 - channel * 8)) and 0xFF) * 257
                message[offset + 1 + channel * 2] = (value shr 8).toByte()
                message[offset + 2 + channel * 2] = value.toByte()
            }
        }

        internal fun parseEntertainmentConfiguration(reply: String, areaId: String): Pair<String, List<Light>>? {
            val data = JSONObject(reply).optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val config = data.optJSONObject(i) ?: continue
                val id = config.optString("id")
                if (id.isEmpty() || !matchesArea(config.optString("id_v1"), id, areaId)) continue
                val channels = config.optJSONArray("channels") ?: return null
                val lights = ArrayList<Light>(channels.length())
                for (j in 0 until channels.length()) {
                    val channel = channels.optJSONObject(j) ?: continue
                    val position = channel.optJSONObject("position") ?: continue
                    val x = position.optDouble("x", 0.0).toFloat()
                    val z = position.optDouble("z", 0.0).toFloat()
                    val point = screenPoint(x, z)
                    lights += Light(channel.optInt("channel_id", j), point[0], point[1])
                }
                if (lights.isEmpty()) return null
                return id to lights
            }
            return null
        }

        private fun matchesArea(idV1: String, id: String, areaId: String): Boolean =
            id == areaId || idV1 == "/groups/$areaId" || idV1.endsWith("/$areaId")

        /** x zóny (-1 vlevo, 1 vpravo) a z (1 strop, -1 podlaha) na souřadnice snímku 0..1. */
        internal fun screenPoint(x: Float, z: Float): FloatArray = floatArrayOf(
            ((x + 1f) / 2f).coerceIn(0f, 1f),
            ((1f - z) / 2f).coerceIn(0f, 1f),
        )

        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
