package cl.inacap.controlacceso

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class AccesoApp : Application() {
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newSingleThreadExecutor()
    val listeners = mutableSetOf<() -> Unit>()
    lateinit var db: DatabaseHelper
    lateinit var link: WifiLink
    private var sessionVersion = 0
    var user: User? = null
        set(value) { field = value; sessionVersion++ }
    var lastEvent: AccessEvent? = null
    var doorUntil = 0L
    var pendingId: String? = null
    var notice = "Esperando una lectura"
    private val handled = linkedSetOf<String>()
    val doorOpen get() = SystemClock.elapsedRealtime() < doorUntil
    override fun onCreate() {
        super.onCreate()
        db = DatabaseHelper(this)
        link = WifiLink({ main.post {
            if (!link.connected) { doorUntil = 0; pendingId = null }
            notifyUi()
        } }, { msg -> main.post { receive(msg) } })
    }
    fun notifyUi() { listeners.toList().forEach { it() } }
    fun logout() {
        link.disconnect(); user = null; lastEvent = null; doorUntil = 0; pendingId = null
        notice = "Esperando una lectura"; handled.clear()
    }
    fun readUid(raw: String) {
        val uid = AccessRules.normalizeUid(raw)
        if (uid == null) { notice = "Escribe 4 bytes hexadecimales, por ejemplo 40 84 2C 33"; notifyUi(); return }
        if (doorOpen || pendingId != null) { notice = "Espera a que finalice la lectura actual"; notifyUi(); return }
        if (link.connected && !link.hosting) {
            val id = UUID.randomUUID().toString()
            pendingId = id; notice = "Esperando respuesta del servidor..."; notifyUi()
            link.sendAsync(JSONObject().put("type", "READ").put("id", id).put("uid", uid)) {
                main.post { pendingId = null; notice = "No se pudo enviar la lectura"; notifyUi() }
            }
            main.postDelayed({
                if (pendingId == id) { pendingId = null; notice = "Sin respuesta. No se confirmó la apertura."; notifyUi() }
            }, 6_000)
        } else if (link.hosting || link.status == "Sin conexión") {
            process(uid, "Local", null)
        } else {
            notice = "Conecta el otro Android o desconecta para probar en modo local"; notifyUi()
        }
    }
    private fun receive(message: JSONObject) {
        if (user == null) return
        try {
            when (message.getString("type")) {
                "READ" -> {
                    require(link.hosting)
                    val uid = AccessRules.normalizeUid(message.getString("uid")) ?: error("UID inválido")
                    val id = UUID.fromString(message.getString("id")).toString()
                    if (id in handled) return
                    handled.add(id); if (handled.size > 100) handled.remove(handled.first())
                    if (doorOpen || pendingId != null) {
                        link.sendAsync(JSONObject().put("type", "BUSY").put("id", id))
                    } else process(uid, "Recibido por Wi-Fi", id)
                }
                "RESULT", "UPDATE" -> {
                    require(!link.hosting)
                    val update = message.getString("type") == "UPDATE"
                    if (!update && message.getString("id") != pendingId) return
                    val uid = AccessRules.normalizeUid(message.getString("uid")) ?: error("UID inválido")
                    val allowed = message.getBoolean("allowed")
                    require(allowed == AccessRules.isAllowed(uid))
                    save(uid, allowed, "Respuesta por Wi-Fi") { pendingId = null; showResult(it) }
                }
                "BUSY" -> if (message.getString("id") == pendingId) {
                    pendingId = null; notice = "El servidor está ocupado. Intenta en unos segundos."; notifyUi()
                }
                else -> error("Mensaje desconocido")
            }
        } catch (_: Exception) {
            link.disconnect(); notice = "Mensaje inválido. Se cerró la conexión."; notifyUi()
        }
    }
    private fun process(uid: String, origin: String, requestId: String?) {
        val allowed = AccessRules.isAllowed(uid)
        pendingId = requestId ?: "local"
        save(uid, allowed, origin) { event ->
            pendingId = null; showResult(event)
            if (link.connected && link.hosting) {
                link.sendAsync(JSONObject().put("type", if (requestId == null) "UPDATE" else "RESULT")
                    .put("id", requestId ?: "local").put("uid", uid).put("allowed", allowed))
            }
        }
    }
    private fun save(uid: String, allowed: Boolean, origin: String, done: (AccessEvent) -> Unit) {
        val active = user ?: return
        val session = sessionVersion
        val connection = link.connectionVersion
        val needsConnection = link.connected
        io.execute {
            try {
                val event = db.addEvent(active.id, uid, allowed, origin)
                main.post {
                    if (user?.id != active.id || session != sessionVersion) return@post
                    if (needsConnection && (!link.connected || connection != link.connectionVersion)) {
                        pendingId = null; doorUntil = 0
                        notice = "La conexión terminó. No se confirmó la apertura."; notifyUi()
                    } else done(event)
                }
            } catch (_: Exception) {
                main.post { pendingId = null; doorUntil = 0; notice = "No se pudo guardar. El acceso permanece cerrado."; notifyUi() }
            }
        }
    }
    private fun showResult(event: AccessEvent) {
        lastEvent = event
        doorUntil = if (event.allowed) SystemClock.elapsedRealtime() + 3_000 else 0
        notice = if (event.allowed) "Acceso permitido · apertura simulada por 3 segundos" else "Acceso denegado · puerta cerrada"
        notifyUi()
        if (event.allowed) main.postDelayed({ notifyUi() }, 3_050)
    }
}
