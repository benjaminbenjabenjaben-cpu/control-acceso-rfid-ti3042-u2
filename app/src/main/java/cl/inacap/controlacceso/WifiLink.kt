package cl.inacap.controlacceso

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Una conexión TCP local. Cada mensaje lleva cifrado autenticado y número de secuencia. */
class WifiLink(private val changed: () -> Unit, private val received: (JSONObject) -> Unit) {
    companion object { const val PORT = 8988 }
    private val workers = Executors.newCachedThreadPool()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile var status = "Sin conexión"; private set
    @Volatile var connected = false; private set
    @Volatile var hosting = false; private set
    @Volatile var code = ""; private set
    @Volatile private var generation = 0
    val connectionVersion get() = generation
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var channel: SecureChannel? = null
    private var heartbeat: ScheduledFuture<*>? = null

    fun startHost() {
        disconnect()
        hosting = true
        code = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        val run = generation
        status = "Esperando al segundo Android"; changed()
        workers.execute {
            try {
                val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                synchronized(this) { if (run != generation) { listener.close(); return@execute }; server = listener }
                while (run == generation) {
                    val peer = listener.accept()
                    try { serve(peer, true, code, run) }
                    catch (_: Exception) { peer.close() }
                    if (run == generation) { connected = false; status = "Conexión terminada o código incorrecto. Esperando..."; changed() }
                }
            } catch (_: Exception) {
                if (run == generation) { hosting = false; status = "No se pudo iniciar el servidor. Desconecta e intenta otra vez."; changed() }
            }
        }
    }
    fun connect(ip: String, secret: String) {
        disconnect()
        val run = generation
        status = "Conectando..."; changed()
        workers.execute {
            val peer = Socket()
            try {
                synchronized(this) { if (run != generation) return@execute; socket = peer }
                peer.connect(InetSocketAddress(ip, PORT), 5_000)
                serve(peer, false, secret, run)
                if (run == generation) { connected = false; status = "El otro Android se desconectó"; changed() }
            } catch (_: Exception) {
                peer.close()
                if (run == generation) { connected = false; status = "Sin conexión. Revisa la IP, el código y la red Wi-Fi."; changed() }
            }
        }
    }
    private fun serve(peer: Socket, host: Boolean, secret: String, run: Int) {
        peer.soTimeout = 8_000
        peer.tcpNoDelay = true
        val input = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.UTF_8))
        val output = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
        val challenge = if (host) ByteArray(16).also { SecureRandom().nextBytes(it) } else {
            val hello = readLine(input)
            require(hello.startsWith("RFID1:"))
            Security.decode(hello.substringAfter(':')).also { require(it.size == 16) }
        }
        if (host) { output.write("RFID1:" + Security.encode(challenge) + "\n"); output.flush() }
        val secure = SecureChannel(secret, challenge, host)
        // La prueba de conocimiento del código se hace dentro de mensajes AES-GCM.
        if (host) {
            require(secure.decode(readLine(input)).getString("type") == "HELLO")
            output.write(secure.encode(JSONObject().put("type", "READY")) + "\n"); output.flush()
        } else {
            output.write(secure.encode(JSONObject().put("type", "HELLO")) + "\n"); output.flush()
            require(secure.decode(readLine(input)).getString("type") == "READY")
        }
        synchronized(this) {
            if (run != generation) { peer.close(); return }
            socket = peer; writer = output; channel = secure; connected = true
            status = if (host) "Conectado · servidor" else "Conectado · activador"
            heartbeat?.cancel(false)
            heartbeat = timer.scheduleWithFixedDelay({
                if (run == generation) try { send(JSONObject().put("type", "PING")) } catch (_: Exception) { peer.close() }
            }, 2, 2, TimeUnit.SECONDS)
        }
        changed()
        try {
            while (run == generation) {
                val message = secure.decode(readLine(input))
                if (message.getString("type") != "PING") received(message)
            }
        } finally {
            peer.close()
            synchronized(this) {
                if (run == generation) {
                    heartbeat?.cancel(false); heartbeat = null
                    connected = false; socket = null; writer = null; channel = null
                }
            }
        }
    }
    @Synchronized fun send(message: JSONObject) {
        check(connected) { "No hay otro Android conectado" }
        writer!!.write(channel!!.encode(message) + "\n"); writer!!.flush()
    }
    fun sendAsync(message: JSONObject, failed: () -> Unit = {}) {
        workers.execute { try { send(message) } catch (_: Exception) { failed(); socket?.close() } }
    }
    @Synchronized fun disconnect() {
        generation++
        heartbeat?.cancel(false); heartbeat = null
        try { socket?.close() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
        socket = null; server = null; writer = null; channel = null
        connected = false; hosting = false; code = ""; status = "Sin conexión"; changed()
    }
    private fun readLine(input: BufferedReader): String {
        val text = StringBuilder()
        while (text.length <= 4096) {
            val value = input.read()
            check(value >= 0) { "Fin de conexión" }
            if (value == 10) return text.toString()
            text.append(value.toChar())
        }
        error("Mensaje demasiado largo")
    }
}

/** Código aleatorio de 128 bits y reto nuevo por conexión; claves distintas por dirección. */
internal class SecureChannel(private val secret: String, private val challenge: ByteArray, host: Boolean) {
    private fun key(direction: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256")
        .digest((secret.uppercase() + ":" + direction).toByteArray(Charsets.UTF_8) + challenge), "AES")
    private val outKey = key(if (host) "server" else "client")
    private val inKey = key(if (host) "client" else "server")
    private var sent = 0L
    private var accepted = 0L
    @Synchronized fun encode(message: JSONObject): String {
        val payload = JSONObject().put("sequence", ++sent).put("data", message).toString()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, outKey, GCMParameterSpec(128, iv))
        return Security.encode(iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }
    fun decode(frame: String): JSONObject {
        val bytes = Security.decode(frame)
        require(bytes.size in 29..3072)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, inKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val payload = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(payload.getLong("sequence") == accepted + 1) { "Mensaje repetido o fuera de orden" }
        accepted++
        return payload.getJSONObject("data")
    }
}
