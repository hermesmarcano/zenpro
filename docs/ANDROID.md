# ZEN para Android

Versión nativa (Kotlin + Jetpack Compose) en `android/`. Misma idea que la de iOS: una
alarma que solo se apaga **yendo al lugar** (código QR pegado o foto de un punto de la
casa), con volumen inteligente. Se instala con un APK, sin Google Play.

> Estado: compila, pasa lint y 225 tests en JVM (lógica pura + receptores, servicio, pantallas y
> manifiesto bajo Robolectric), y genera un APK firmado. **No se ha
> probado en un teléfono real** (no hay emulador en el entorno de desarrollo). Ver
> [Pendiente de validar en dispositivo](#pendiente-de-validar-en-dispositivo).

## Instalar el APK

1. Descarga `zen-android.apk` desde la pre-release **android-latest** del repositorio
   (GitHub → *Releases*), o desde el artefacto `zen-android-apk` de la última ejecución de
   *Android CI*.
2. Ábrelo en el teléfono. Android pedirá permitir *instalar apps desconocidas* para el
   navegador o gestor de archivos que lo abrió: acéptalo solo para esta instalación.
3. Mínimo **Android 8.0 (API 26)**.
4. Al abrir ZEN, concede lo que pide la tarjeta de arriba:
   - **Cámara** (escanear el QR / tomar la foto).
   - **Notificaciones** (Android 13+).
   - **Pantalla completa** (Android 14+, *Ajustes → Apps → ZEN → Notificaciones de pantalla
     completa*): sin esto la alarma suena pero su pantalla no se abre sola con el teléfono bloqueado.
5. Recomendado: *Ajustes → Batería → ZEN → Sin restricciones*. Algunos fabricantes (Xiaomi,
   Huawei, Samsung, OnePlus…) matan apps en segundo plano y eso puede impedir la alarma.

Cada compilación sube el `versionCode` (minutos desde 1970, así que siempre crece). Las actualizaciones se instalan **encima** de la versión anterior sin perder alarmas ni anclas:
todos los APK se firman con la misma clave (`android/app/zen-sideload.jks`).

## Cómo funciona

- **Alarma**: `AlarmManager.setAlarmClock` (alarma "de reloj" del usuario; sobrevive a Doze).
  Al sonar, `AlarmReceiver` arranca `AlarmService` (servicio en primer plano) que reproduce un
  tono sintetizado por el canal de alarma, vibra y lee acelerómetro/giroscopio.
- **Apagar**: la pantalla `RingingActivity` (aparece sobre el bloqueo) exige verificar el ancla.
  Atrás/Inicio no la silencian.
- **Anclas**: QR (payload `zen://anchor/<id>/<secreto>`, comparado en tiempo constante) o
  foto (descriptor de bordes por celdas, estilo HOG, comparado con 1–3 referencias).
  Al crear el ancla se verifica obligatoriamente, no a las 6 a. m.
- **Código de respaldo** de 6 dígitos, impreso junto al QR / anotado: se habilita solo tras 2
  minutos de alarma (reloj monotónico) para que no sea un atajo desde la cama.
- **Volumen inteligente** (`core/SmartVolumeController`): arranca bajo y vibrando; al coger el
  teléfono baja a la mitad y luego a ~10 % mientras se mueve; si se deja quieto sin apagar vuelve
  al 50 % y sube progresivamente a 100 %. Con la cámara abierta cuenta como "en mano". La
  vibración propia se ignora en los sensores para no confundirla con movimiento.
- **Sonidos** (Ajustes): cinco tonos incluidos (Clásico, Suave, Campana, Pulso, Mañana, sintetizados
  en el teléfono) o un archivo de audio propio (se copia al almacenamiento privado; máx. 20 MB y
  10 min). Si el archivo falla al sonar, cae al tono clásico: la alarma nunca queda muda.
- **Volumen inicial** elegible (10, 15, 20 o 30 %). El nivel es *sonoridad percibida*: se convierte en
  ganancia con una curva de potencia 1,7 (`VolumeCurve`), porque el oído es logarítmico. Con ganancia
  lineal el "15 %" eran solo −16 dB y en un tono agudo seguía sonando fuerte; ahora son −28 dB.
  Al coger el teléfono baja a ~60 % del nivel inicial ("un poco") y, si se deja quieto sin apagar, sube.
- **Al despertar**: al verificar el ancla suena un sonido de éxito (Campanilla, Fanfarria, Moneda,
  Cuenco, o un archivo propio, cortado a 10 s) y aparece una pantalla con el tiempo que tardaste y
  tu **racha**. La racha solo cuenta llegar al ancla (QR o foto); el código de respaldo no suma ni
  rompe; los días sin alarma programada no la cortan (una alarma de lunes a viernes no se rompe el
  fin de semana). Es deliberadamente sobria: un número y un mensaje en 3, 7, 14, 30, 60, 100 y 365 días.
- **Reprogramación**: `BootReceiver` recalcula alarmas tras reinicio, cambio de hora/zona o
  actualización. Una alarma de una sola vez que ya pasó (teléfono apagado) se desactiva en vez
  de sonar al día siguiente.

La lógica con reglas de negocio vive en `app/src/main/java/com/hermesmarcano/zen/core` y
`data`, sin dependencias de Android, y se prueba en JVM (`app/src/test`).

## Compilar

```bash
cd android
export ANDROID_HOME=/ruta/al/android-sdk   # platforms;android-35 + build-tools;35.0.0
./gradlew testDebugUnitTest lintDebug assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Con muchas descargas en paralelo Maven Central puede responder 429 detrás de un proxy:
reintenta con `--max-workers=1 -Dorg.gradle.parallel=false`.

## Firma (importante)

`zen-sideload.jks` está en el repositorio **a propósito**: es una clave pública solo para
instalar por APK (contraseña `zen-sideload`). Cualquiera podría firmar un APK "con la misma
firma", así que **no la uses para publicar en Google Play**. Para Play hace falta una clave
privada aparte (nunca en el repositorio) y distribuir como AAB.

## Limitaciones conocidas

- Si el usuario *fuerza el cierre* de la app desde Ajustes, Android cancela sus alarmas hasta
  que vuelva a abrirla. No se puede evitar sin ser app de sistema.
- Tras un reinicio, las alarmas se reprograman cuando el usuario desbloquea el teléfono por
  primera vez (el estado está en almacenamiento protegido). Una alarma que debía sonar antes
  de ese primer desbloqueo no suena.
- Mientras suena, ZEN mantiene el volumen de alarmas por encima del 60 % del máximo: las teclas de
  volumen no pueden silenciarla (al apagarla se restaura el volumen que tenías).
- Si la alarma suena con otra app en primer plano y el teléfono desbloqueado, Android solo muestra
  un aviso emergente (no abre la pantalla sola): tócalo, o abre ZEN, que lleva directo a apagarla.
- Si el usuario revoca el permiso de cámara, solo queda el código de respaldo (tras la espera).
- Sin acelerómetro el volumen simplemente sube de forma continua.
- Aún no incluye el bloqueo de apps ni la lista de tareas (tampoco están terminados en iOS).

## Pendiente de validar en dispositivo

- **Audio**: el simulador de pruebas no tiene audio nativo, así que `AudioTrack`/`MediaPlayer` solo se
  prueban en lo que no suena (que no lancen, que el nivel se convierta bien, que los archivos rotos
  caigan al plan B). Cómo *suena* de verdad (volumen inicial, tonos, archivo propio) hay que oírlo.

Estimaciones sin medir en un teléfono real:

- Umbral de la foto (`PhotoMatchPolicy.DEFAULT_THRESHOLD = 0.42`). Calibrado con variaciones
  sintéticas (luz, ruido, desplazamiento de hasta 6 %, zoom del 12 %, giro de 5°): mismo lugar
  ≤ 0.37, lugares distintos ≥ 0.47 (`PhotoRobustnessTest`). Con
  fotos reales de día vs. madrugada puede requerir ajuste; la pantalla de alta muestra la
  distancia medida para poder calibrar.
- Umbrales de movimiento (`MotionClassifier.Tuning`) y tiempos del volumen.
- Arranque del servicio en primer plano desde la alarma y la pantalla completa sobre el
  bloqueo en Android 12–15, y el comportamiento con el ahorro de batería de cada fabricante.
