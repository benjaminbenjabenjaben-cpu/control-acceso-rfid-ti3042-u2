# Control de acceso RFID

Aplicación de la Unidad 2 de Aplicaciones Móviles para IoT, TI3042.
Autor: Benjamin Dinamarca.

Simula el proyecto de la Unidad 1 con lector RFID RC522 y servomotor SG90. Un Android recibe una lectura desde otro Android por la red local, compara el UID y muestra el resultado. El UID autorizado del prototipo es `40 84 2C 33`. La puerta simulada abre a 90 grados durante 3 segundos y luego vuelve a 0 grados.

## Abrir en Android Studio

1. Seleccionar **Open** y abrir esta carpeta, donde está `settings.gradle.kts`.
2. Esperar la sincronización de Gradle. Se necesita conexión para descargar las dependencias la primera vez.
3. Usar **JDK 21** como Gradle JDK y el SDK Android 35. La aplicación funciona desde Android 8.0, API 26.
4. Elegir un emulador o teléfono y pulsar **Run**.

En este equipo se incluye `Abrir proyecto.bat` junto a la carpeta del proyecto. La [APK de demostración](apk/AccesoRFID.apk) también está disponible para instalar en los teléfonos.

## Probar primero en un solo Android

1. Pulsar **Crear cuenta local**. Elegir un usuario de 3 a 24 caracteres y contraseña de 8 a 64 caracteres.
2. Iniciar sesión con la cuenta creada. Cada dispositivo guarda sus propias cuentas.
3. Ir al **Activador** y probar la tarjeta autorizada y la no autorizada.
4. Revisar **Monitoreo** e **Historial**. Los registros se conservan al cerrar y volver a abrir la aplicación.

## Conectar dos teléfonos

1. Instalar la misma aplicación en ambos e iniciar sesión en cada uno.
2. Conectar ambos a una misma red Wi-Fi que permita comunicación entre equipos. No se necesita salida a internet.
3. En Android 1 entrar a **Conectar otro Android** y pulsar **Iniciar servidor**.
4. En Android 2 escribir la IP que muestra Android 1 y su código de conexión de 32 caracteres. Pulsar **Conectar**.
5. En Android 1 dejar abierta la pantalla **Monitoreo**. En Android 2 ir al **Activador** y pulsar **Enviar lectura por Wi-Fi**.
6. Comprobar el resultado en ambos. Cambiar a tarjeta no autorizada y repetir.
7. Pulsar **Desconectar** para terminar. Un servidor nuevo genera un código nuevo.

Si no conecta, revisar la IP y el código y evitar redes de invitados con aislamiento entre dispositivos. La conexión admite un compañero a la vez y está pensada para una demostración con las aplicaciones en primer plano.

## Archivos que conviene explicar

| Archivo | Función |
| --- | --- |
| `Activities.kt` | Login, Monitoreo, Activador, Conexión e Historial. |
| `DatabaseHelper.kt` | Creación de tablas, registro, login y consultas SQLite. |
| `AccessRules.kt` | Formato de UID y tarjeta autorizada del prototipo. |
| `AccesoApp.kt` | Sesión, procesamiento de lecturas y apertura por tres segundos. |
| `WifiLink.kt` | Servidor y cliente TCP, mensajes cifrados, comprobaciones y desconexión. |
| `Security.kt` | Hash de contraseñas y cifrado de UID con Android Keystore. |
| `AndroidManifest.xml` | Actividades y permiso `INTERNET` para sockets de red local. |

## Seguridad y alcance

Las contraseñas se guardan como hash PBKDF2 con sal individual, nunca como texto plano. El UID de los registros se cifra con AES-GCM y una clave del Keystore del teléfono. Las consultas usan parámetros y cada usuario ve su historial.

Los mensajes JSON de la red se cifran y autentican con AES-256-GCM usando un código aleatorio de conexión de 128 bits, un reto nuevo por conexión y claves distintas para cada dirección. El número de secuencia rechaza repeticiones. No se usa HTTP ni un servidor en la nube. Este canal de aplicación es una implementación académica; no equivale a una certificación ISO ni reemplaza TLS y revisión de seguridad para uso industrial.

No hay Arduino conectado. Los UID son datos simulados; el estado de la puerta no es una medición física. La base de datos y las cuentas son locales y no se sincronizan como un sistema central de usuarios. Las copias de seguridad de la app están deshabilitadas. Desinstalar borra las cuentas y registros.

## Comprobaciones

En Windows:

```powershell
.\gradlew.bat assembleDebug lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

Las pruebas instrumentadas comprueban SQLite, autenticación, aislamiento de usuarios, protección de mensajes y estabilidad del canal. Ver `docs/PRUEBAS.txt` para los resultados y sus límites. La prueba entre dos emuladores usa reenvío de puerto de ADB; se debe repetir la demostración sobre Wi-Fi con dos teléfonos antes de presentar.

## Fuentes

- [Guía oficial de SQLite en Android](https://developer.android.com/training/data-storage/sqlite)
- [Conexiones de red en Android](https://developer.android.com/develop/connectivity/network-ops/connecting)
- [Criptografía en Android](https://developer.android.com/privacy-and-security/cryptography)
- [ISO IEC 27400](https://www.iso.org/standard/44373.html)
- [ISO IEC 27001](https://www.iso.org/standard/27001)
- [NIST SP 800 82 Rev 3](https://csrc.nist.gov/pubs/sp/800/82/r3/final)
