package cl.inacap.controlacceso

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoginActivity : AppCompatActivity() {
    private var creating = false
    private var busy = false
    private val app get() = application as AccesoApp
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        creating = savedInstanceState?.getBoolean("creating") ?: false
        findViewById<MaterialButton>(R.id.auth_toggle).setOnClickListener { creating = !creating; renderMode() }
        findViewById<MaterialButton>(R.id.auth_submit).setOnClickListener { submit() }
        renderMode()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("creating", creating); super.onSaveInstanceState(outState) }
    private fun renderMode() {
        findViewById<TextView>(R.id.auth_description).text = if (creating) "Crea una cuenta en este dispositivo. Usuario de 3 a 24 caracteres y contraseña de 8 a 64." else "Inicia sesión para monitorear y probar el acceso."
        findViewById<MaterialButton>(R.id.auth_submit).text = if (creating) "Registrar cuenta" else "Ingresar"
        findViewById<MaterialButton>(R.id.auth_toggle).text = if (creating) "Ya tengo cuenta" else "Crear cuenta local"
        findViewById<TextView>(R.id.auth_message).text = ""
    }
    private fun submit() {
        if (busy) return
        val username = findViewById<EditText>(R.id.username).text.toString().trim().lowercase(Locale.ROOT)
        val password = findViewById<EditText>(R.id.password).text.toString()
        val message = findViewById<TextView>(R.id.auth_message)
        if (!username.matches(Regex("[a-z0-9_.]{3,24}")) || password.length !in 8..64) {
            message.text = "Usa un usuario de 3 a 24 letras, números, punto o guion bajo, y una contraseña de 8 a 64 caracteres."; return
        }
        val wasCreating = creating
        busy = true
        findViewById<MaterialButton>(R.id.auth_submit).isEnabled = false
        findViewById<MaterialButton>(R.id.auth_toggle).isEnabled = false
        message.text = "Validando..."
        app.io.execute {
            try {
                val registered = wasCreating && app.db.register(username, password)
                val user = if (wasCreating) null else app.db.login(username, password)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    busy = false
                    findViewById<MaterialButton>(R.id.auth_submit).isEnabled = true
                    findViewById<MaterialButton>(R.id.auth_toggle).isEnabled = true
                    when {
                        registered -> { creating = false; renderMode(); findViewById<EditText>(R.id.password).text.clear(); message.text = "Cuenta creada. Ingresa tu contraseña para iniciar sesión." }
                        wasCreating -> message.text = "Ese usuario ya existe en este dispositivo."
                        user == null -> message.text = "Usuario o contraseña incorrectos."
                        else -> { app.user = user; startActivity(Intent(this, MonitorActivity::class.java)); finish() }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    busy = false; message.text = "No se pudo acceder a la base de datos. Intenta otra vez."
                    findViewById<MaterialButton>(R.id.auth_submit).isEnabled = true
                    findViewById<MaterialButton>(R.id.auth_toggle).isEnabled = true
                }
            }
        }
    }
}

abstract class ProtectedActivity : AppCompatActivity() {
    protected val app get() = application as AccesoApp
    private val refresh: () -> Unit = { if (!isFinishing) render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.user == null) { startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() }
    }
    override fun onResume() { super.onResume(); if (app.user != null) { app.listeners.add(refresh); render() } }
    override fun onPause() { app.listeners.remove(refresh); super.onPause() }
    protected abstract fun render()
    protected fun button(id: Int, action: () -> Unit) { findViewById<MaterialButton>(id).setOnClickListener { action() } }
    protected fun open(type: Class<*>) { startActivity(Intent(this, type)) }
    protected fun back() { finish() }
}

class MonitorActivity : ProtectedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); if (isFinishing) return
        setContentView(R.layout.activity_monitor)
        button(R.id.open_activator) { open(ActivatorActivity::class.java) }
        button(R.id.open_connection) { open(ConnectionActivity::class.java) }
        button(R.id.open_history) { open(HistoryActivity::class.java) }
        button(R.id.logout) {
            app.logout()
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
    override fun render() {
        findViewById<TextView>(R.id.welcome).text = "Usuario: ${app.user?.name}"
        findViewById<TextView>(R.id.door_state).text = if (app.doorOpen) "ABIERTA · 90°" else "CERRADA · 0°"
        findViewById<TextView>(R.id.latest_reading).text = app.lastEvent?.let { "Último UID: ${it.uid}\n${if (it.allowed) "PERMITIDO" else "DENEGADO"} · ${it.origin}" } ?: "Sin lecturas en esta sesión"
        findViewById<TextView>(R.id.monitor_notice).text = app.notice
        findViewById<TextView>(R.id.network_state).text = app.link.status
    }
}

class ActivatorActivity : ProtectedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); if (isFinishing) return
        setContentView(R.layout.activity_activator)
        findViewById<RadioGroup>(R.id.card_choice).setOnCheckedChangeListener { _, id ->
            findViewById<EditText>(R.id.uid_input).setText(if (id == R.id.authorized_card) AccessRules.AUTHORIZED_UID else "AA BB CC DD")
        }
        button(R.id.send_reading) { app.readUid(findViewById<EditText>(R.id.uid_input).text.toString()) }
        button(R.id.back_monitor) { back() }
    }
    override fun render() {
        val remote = app.link.connected && !app.link.hosting
        findViewById<TextView>(R.id.activator_mode).text = if (remote) "La lectura se enviará al otro Android por Wi-Fi." else "Prueba local en este Android${if (app.link.hosting) "; el resultado también se envía al compañero." else "."}"
        findViewById<MaterialButton>(R.id.send_reading).apply { text = if (remote) "Enviar lectura por Wi-Fi" else "Probar lectura local"; isEnabled = app.pendingId == null && !app.doorOpen }
        findViewById<TextView>(R.id.activator_result).text = app.notice + "\nPuerta simulada: " + if (app.doorOpen) "ABIERTA" else "CERRADA"
    }
}

class ConnectionActivity : ProtectedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); if (isFinishing) return
        setContentView(R.layout.activity_connection)
        button(R.id.start_host) { app.link.startHost() }
        button(R.id.connect_client) {
            val ip = findViewById<EditText>(R.id.server_ip).text.toString().trim()
            val secret = findViewById<EditText>(R.id.server_code).text.toString().trim().uppercase(Locale.ROOT)
            val parts = ip.split('.')
            val validIp = parts.size == 4 && parts.all { it.isNotEmpty() && it.toIntOrNull() in 0..255 }
            if (!validIp || !secret.matches(Regex("[0-9A-F]{32}"))) {
                findViewById<TextView>(R.id.connection_status).text = "Ingresa una IP válida y el código de 32 caracteres del servidor."
            } else app.link.connect(ip, secret)
        }
        button(R.id.disconnect) { app.link.disconnect() }
        button(R.id.connection_back) { back() }
        app.io.execute {
            val ips = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }.mapNotNull { it.hostAddress } } catch (_: Exception) { emptyList() }
            runOnUiThread { if (!isDestroyed) findViewById<TextView>(R.id.local_ip).text = "IP de este Android: ${ips.joinToString().ifEmpty { "conecta el Wi-Fi" }}\nPuerto: ${WifiLink.PORT}" }
        }
    }
    override fun render() {
        findViewById<TextView>(R.id.connection_status).text = app.link.status
        findViewById<TextView>(R.id.pairing_code).text = if (app.link.code.isEmpty()) "El código aparece al iniciar el servidor." else "Código: ${app.link.code}"
        val free = !app.link.connected && !app.link.hosting && app.link.status != "Conectando..."
        findViewById<MaterialButton>(R.id.start_host).isEnabled = free
        findViewById<MaterialButton>(R.id.connect_client).isEnabled = free
    }
}

class HistoryActivity : ProtectedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); if (isFinishing) return
        setContentView(R.layout.activity_history)
        button(R.id.history_back) { back() }
    }
    override fun render() {
        val user = app.user ?: return
        app.io.execute {
            try {
                val events = app.db.events(user.id)
                val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale("es", "CL"))
                val text = events.joinToString("\n\n") { "${if (it.allowed) "PERMITIDO" else "DENEGADO"} · ${it.uid}\n${dateFormat.format(Date(it.date))}\n${it.origin} · registro ${it.id}" }.ifEmpty { "Todavía no hay lecturas guardadas." }
                runOnUiThread {
                    if (!isDestroyed) {
                        findViewById<TextView>(R.id.history_summary).text = "Total: ${events.size} · Permitidos: ${events.count { it.allowed }} · Denegados: ${events.count { !it.allowed }}"
                        findViewById<TextView>(R.id.history_rows).text = text
                    }
                }
            } catch (_: Exception) { runOnUiThread { if (!isDestroyed) findViewById<TextView>(R.id.history_rows).text = "No se pudo leer el historial local." } }
        }
    }
}
