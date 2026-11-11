# SmartLights

App Android para controlar bombillas **WiZ** y **Tapo (TP-Link)** directamente por la red local, sin servidor ni nube.

- Brillo, blanco, color (panel 2D) y preajustes de color editables.
- Habitaciones: agrupan bombillas y se controlan a la vez.
- Programaciones de la Tapo (se guardan en la propia bombilla).
- La contraseña de Tapo se guarda cifrada con el Android Keystore.

## Estructura
- `android/core`: cliente en Kotlin puro (WiZ por UDP, Tapo con el protocolo TPAP).
- `android/app`: interfaz Android.

## Requisitos para compilar
JDK 17 y Android SDK (plataforma 34). Luego:

    cd android && ./gradlew :app:assembleDebug

## Notas
- WiZ: activar el control local en la app oficial.
- Tapo: usa el email y la contraseña de tu cuenta Tapo. Solo está soportado TPAP sobre HTTP (tls=0, suite 1, aes_128_ccm).
- Protocolo TPAP portado a partir de [plugp100](https://github.com/petretiandrea/plugp100).

## Licencia
GPL-3.0. Ver [LICENSE](LICENSE). El soporte TPAP de Tapo está portado de plugp100 (GPL-3.0), por lo que todo el proyecto se distribuye bajo la misma licencia.
