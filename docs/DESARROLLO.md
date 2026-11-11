# Guía de desarrollo

## Estructura

| Carpeta | Qué hay |
|---|---|
| `android/core` | Cliente en Kotlin puro, sin dependencias de Android: WiZ (UDP), Tapo (TPAP), criptografía y programaciones. Tiene un CLI para probar desde el PC. |
| `android/app` | La interfaz Android: pantalla principal, programaciones, selectores de color y almacén cifrado de secretos. |

El módulo `core` no usa Android, así que se puede ejecutar y probar en el PC
contra tus bombillas reales.

## Compilar

Necesitas **JDK 17** y el **SDK de Android** con la plataforma 34. Crea
`android/local.properties` con la ruta de tu SDK (el fichero está en `.gitignore`):

```
sdk.dir=/ruta/al/Android
```

Después:

```bash
cd android
./gradlew :app:assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`. Va firmado con
la clave de depuración: sirve para instalarlo en tu móvil, no para publicarlo.

El identificador de la app es `com.smartlights.app`. Si lo cambias, Android la
tratará como otra app distinta y perderás los datos guardados.

## Probar el núcleo desde el PC

```bash
cd android
./gradlew :core:run --args="discover"                      # busca WiZ en la red
./gradlew :core:run --args="state 192.168.1.50"            # lee una WiZ
./gradlew :core:run --args="set 192.168.1.50 brightness 40"
```

Para la Tapo, el CLI lee un JSON con `host`, `username` y `password` desde la
ruta de la variable `TAPO_CONFIG` o, si no existe, `~/.config/luces/tapo.json`:

```bash
./gradlew :core:run --args="tapo"                  # estado
./gradlew :core:run --args="tapo brightness 40"
./gradlew :core:run --args="tapo-sched"            # lista las programaciones
```

> **No subas nunca ese fichero.** `tapo.json` y `config.json` están en
> `.gitignore` por esa razón.

`tapo-raw <método> [json]` envía una petición cruda. Úsalo solo con métodos de
lectura (`get_*`): los de escritura modifican la bombilla de verdad.

## Cómo funciona cada protocolo

**WiZ.** Mensajes JSON por UDP al puerto 38899 (`getPilot`, `setPilot`). Si la
bombilla responde `Invalid params` a todas las escrituras, el control local está
desactivado en la app de WiZ.

**Tapo (TPAP).** Handshake en dos pasos (`pake_register` y `pake_share`) con
SPAKE2+ sobre P-256, y sesión cifrada con AES-128-CCM. Las peticiones van a
`/stok=<sesión>/ds`. Está en `Tpap.kt` y `Crypto.kt`.

**Programaciones.** `get_schedule_rules`, `add_schedule_rule`, `edit_schedule_rule`
y `remove_schedule_rules`. En el borrado, `remove_all` debe ir siempre a `false`:
en `true` la bombilla borra **todas** las reglas. Los días son una máscara de
bits: bit 0 = domingo … bit 6 = sábado, así que 62 es de lunes a viernes.

## Licencia

GPL-3.0. Cualquier derivado debe publicarse bajo la misma licencia.
