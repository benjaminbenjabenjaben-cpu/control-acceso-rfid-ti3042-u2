package cl.inacap.controlacceso

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IntegrationTests {
    @Test fun sqliteLoginPersistenceIsolationAndEncryptedUid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "test_${System.nanoTime()}.db"
        var db = DatabaseHelper(context, filename)
        try {
            assertTrue(db.register("alumno", "ClavePrueba2026"))
            assertFalse(db.register("alumno", "OtraClave2026"))
            assertNull(db.login("alumno", "Incorrecta2026"))
            assertNull(db.login("' OR 1=1 --", "ClavePrueba2026"))
            val first = db.login("alumno", "ClavePrueba2026")!!
            assertTrue(db.register("companero", "ClavePrueba2026"))
            val other = db.login("companero", "ClavePrueba2026")!!
            db.addEvent(first.id, "40 84 2C 33", true, "Local")
            db.addEvent(first.id, "AA BB CC DD", false, "Recibido por Wi-Fi")
            assertEquals(0, db.events(other.id).size)
            db.readableDatabase.rawQuery("SELECT password_hash, salt FROM users ORDER BY id", null).use {
                assertTrue(it.moveToFirst()); val hash = it.getString(0); val salt = it.getString(1)
                assertNotEquals("ClavePrueba2026", hash)
                assertTrue(it.moveToNext()); assertNotEquals(hash, it.getString(0)); assertNotEquals(salt, it.getString(1))
            }
            db.readableDatabase.rawQuery("SELECT uid_encrypted FROM access_events", null).use {
                assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("40 84 2C 33"))
            }
            db.close(); db = DatabaseHelper(context, filename)
            assertNotNull(db.login("alumno", "ClavePrueba2026"))
            val events = db.events(first.id)
            assertEquals(2, events.size)
            assertEquals("AA BB CC DD", events[0].uid); assertFalse(events[0].allowed)
            assertEquals("40 84 2C 33", events[1].uid); assertTrue(events[1].allowed)
        } finally { db.close(); context.deleteDatabase(filename) }
    }

    @Test fun secureMessagesRejectTamperingWrongKeyReplayAndOldConnection() {
        val code = "00112233445566778899AABBCCDDEEFF"
        val challenge = ByteArray(16) { it.toByte() }
        val host = SecureChannel(code, challenge, true)
        val client = SecureChannel(code, challenge, false)
        val frame = client.encode(JSONObject().put("type", "READ").put("uid", "40 84 2C 33"))
        assertFalse(frame.contains("40 84 2C 33"))
        assertEquals("READ", host.decode(frame).getString("type"))
        rejected { host.decode(frame) }
        rejected { SecureChannel("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", challenge, true).decode(frame) }
        rejected { SecureChannel(code, ByteArray(16) { 99 }, true).decode(frame) }
        val altered = Security.decode(client.encode(JSONObject().put("type", "READ")))
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        rejected { host.decode(Security.encode(altered)) }
    }

    @Test fun interconnectionThirtyMessagesIdleDisconnectAndReconnect() {
        val arrived = CountDownLatch(30)
        val count = AtomicInteger(0)
        val host = WifiLink({}, { if (it.optString("type") == "TEST") { count.incrementAndGet(); arrived.countDown() } })
        val client = WifiLink({}, {})
        try {
            host.startHost()
            Thread.sleep(200)
            client.connect("127.0.0.1", host.code)
            await { host.connected && client.connected }
            val started = System.nanoTime()
            repeat(30) { client.send(JSONObject().put("type", "TEST").put("number", it)) }
            assertTrue("No llegaron los 30 mensajes", arrived.await(5, TimeUnit.SECONDS))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("RFID_EVIDENCE messages=${count.get()} elapsedMs=$elapsedMs")
            Thread.sleep(3_000)
            assertTrue(host.connected); assertTrue(client.connected)
            client.disconnect()
            await { !host.connected }
            client.connect("127.0.0.1", host.code)
            await { host.connected && client.connected }
        } finally { client.disconnect(); host.disconnect() }
    }

    @Test fun wrongPairingCodeAndMalformedFrameDoNotStopHost() {
        val host = WifiLink({}, {})
        val client = WifiLink({}, {})
        try {
            host.startHost(); Thread.sleep(200)
            val wrong = if (host.code == "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF") "00000000000000000000000000000000" else "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
            client.connect("127.0.0.1", wrong)
            await { client.status.startsWith("Sin conexión.") }
            assertFalse(client.connected); assertFalse(host.connected); assertTrue(host.hosting)
            Socket("127.0.0.1", WifiLink.PORT).use {
                it.soTimeout = 2_000
                assertTrue(it.getInputStream().bufferedReader().readLine().startsWith("RFID1:"))
                it.getOutputStream().write("mensaje_sin_cifrar\n".toByteArray()); it.getOutputStream().flush()
            }
            Thread.sleep(200)
            client.connect("127.0.0.1", host.code)
            await { host.connected && client.connected }
        } finally { client.disconnect(); host.disconnect() }
    }

    @Test fun delayedDatabaseResultCannotReopenDoorAfterLogout() {
        delayedResult(false)
    }

    @Test fun delayedDatabaseResultCannotReopenDoorAfterDisconnection() {
        delayedResult(true)
    }

    private fun delayedResult(network: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AccesoApp
        val filename = "race_${System.nanoTime()}.db"
        val fixture = DatabaseHelper(app, filename)
        fixture.register("prueba", "ClavePrueba2026")
        val user = fixture.login("prueba", "ClavePrueba2026")!!
        val original = app.db
        val gate = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val client = WifiLink({}, {})
        try {
            instrumentation.runOnMainSync { app.logout(); app.db = fixture; app.user = user }
            if (network) {
                app.link.startHost(); Thread.sleep(200)
                client.connect("127.0.0.1", app.link.code)
                await { app.link.connected && client.connected }
                instrumentation.waitForIdleSync()
            }
            app.io.execute { gate.await(3, TimeUnit.SECONDS) }
            instrumentation.runOnMainSync {
                app.readUid(AccessRules.AUTHORIZED_UID)
                if (network) app.link.disconnect() else { app.logout(); app.user = user }
            }
            gate.countDown()
            app.io.execute { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertFalse(app.doorOpen)
            assertNull(app.lastEvent)
        } finally {
            gate.countDown(); client.disconnect()
            instrumentation.runOnMainSync { app.logout(); app.db = original }
            fixture.close(); app.deleteDatabase(filename)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("La condición no se cumplió dentro de 8 segundos", condition())
    }
    private fun rejected(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        assertTrue("Se aceptó un mensaje que debía rechazarse", rejected)
    }
}
