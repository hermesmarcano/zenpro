# ZEN para Android

Versión nativa (Kotlin + Jetpack Compose) en `android/`. Misma idea que la de iOS: una
alarma que solo se apaga **yendo al lugar** (código QR pegado o foto de un punto de la
casa), con volumen inteligente. Se instala con un APK, sin Google Play.

> Estado: compila, pasa lint y 327 tests en JVM (lógica pura + receptores, servicio, pantallas y
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
  Cuenco, o un archivo propio, cortado a 10 s) al **20 % del máximo** (sonoridad percibida, también en
  "Probar" y para archivos propios; arranca 350 ms después para que el servicio devuelva antes el
  volumen de alarmas del usuario) y aparece una pantalla con el tiempo que tardaste y
  tu **racha**. La racha solo cuenta llegar al ancla (QR o foto); el código de respaldo no suma ni
  rompe; los días sin alarma programada no la cortan (una alarma de lunes a viernes no se rompe el
  fin de semana). Es deliberadamente sobria: un número y un mensaje en 3, 7, 14, 30, 60, 100 y 365 días.
- **Tareas del día** (pestaña *Hoy*): hasta 10 por día, 1 prioritaria · 3 medias · 6 bajas según la
  posición; se reordenan arrastrando, se marcan, se añaden, se editan y se eliminan (con deshacer).
  Hoy / Mañana, y "traer a hoy" las pendientes de días anteriores.
- **Modo viaje**: pausar las alarmas unos días (la racha no se rompe) o apagarlas en otro ancla (un QR de
  repuesto, una foto del hotel). Cuenta desde la próxima alarma.
- **Entreno** (pestaña *Entreno*, inspirada en Hevy): rutinas por día **sin atar al día de la semana** (la app
  propone la siguiente en la rotación; se salta un día y sigue tocando la misma). Cada serie anota **kg × repeticiones**
  (o solo repeticiones, o tiempo), con lo de la última vez ya puesto; calentamientos, RPE 6–10, notas, **supersets**,
  ejercicios propios y una biblioteca de ~55 ejercicios. **Cronómetro de descanso** entre series (por ejercicio, ±15 s, saltar):
  se guarda la hora de fin, así que sobrevive a que Android cierre la app; avisa con una alarma exacta
  (`USE_EXACT_ALARM`, `RestTimer`/`RestReceiver`), una notificación con cuenta atrás y un sonido corto a nivel bajo
  (`VolumeCurve.REST_LEVEL`) por el canal de notificaciones (respeta el modo silencio). Además: calculadora de discos
  (barra de 20/15/10 kg), récords personales (peso máximo, 1RM estimada Epley, mejor serie), gráficas por ejercicio,
  resumen semanal y objetivo de días por semana. El historial vive en `workouts.json` (la sesión en curso, en
  `active_workout.json`, para no reescribir años de historial en cada serie) y se **exporta/importa como CSV** (la app no
  usa la copia de seguridad de Android). Unidades: kg.
  - **Juego**: un entrenamiento que cuenta (≥ 3 series de trabajo y ≥ 5 min) da +40 XP el primero de cada día,
    +5 XP por ejercicio con récord (máx. 3), +50 XP al cumplir el objetivo semanal (una vez por semana) y 7 logros
    (primer entreno, 10, 50, primera marca, semana cumplida, 4 semanas seguidas, 10 toneladas). Sin castigos ni
    avisos de pérdida: la racha semanal solo se muestra, no se amenaza.
  - Una sesión que se olvidó cerrar solo cuenta hasta 15 min después de la última serie.
- **Progreso**: XP, niveles, racha de despertar y de tarea prioritaria, estadísticas de entreno y 20 logros.
  Análisis completo de UX y gamificación en [`ANDROID-UX-GAMIFICACION.md`](ANDROID-UX-GAMIFICACION.md).
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
- Aún no incluye el bloqueo de apps (tampoco está terminado en iOS).
- `USE_EXACT_ALARM` es válido para el APK por sideload; si algún día se publica en Google Play habrá que justificarlo (la app es de alarmas) o pedir el permiso especial.

## Pendiente de validar en dispositivo

- **Audio**: el simulador de pruebas no tiene audio nativo, así que `AudioTrack`/`MediaPlayer` solo se
  prueban en lo que no suena (que no lancen, que el nivel se convierta bien, que los archivos rotos
  caigan al plan B). Cómo *suena* de verdad (volumen inicial, tonos, archivo propio, aviso de fin de descanso) hay que oírlo.
- **Entreno**: el aviso de descanso con la pantalla apagada y el ahorro de batería de cada fabricante, y escribir con el teclado numérico (coma o punto decimal).

Estimaciones sin medir en un teléfono real:

- Umbral de la foto (`PhotoMatchPolicy.DEFAULT_THRESHOLD = 0.42`). Calibrado con variaciones
  sintéticas (luz, ruido, desplazamiento de hasta 6 %, zoom del 12 %, giro de 5°): mismo lugar
  ≤ 0.37, lugares distintos ≥ 0.47 (`PhotoRobustnessTest`). Con
  fotos reales de día vs. madrugada puede requerir ajuste; la pantalla de alta muestra la
  distancia medida para poder calibrar.
- Umbrales de movimiento (`MotionClassifier.Tuning`) y tiempos del volumen.
- Arranque del servicio en primer plano desde la alarma y la pantalla completa sobre el
  bloqueo en Android 12–15, y el comportamiento con el ahorro de batería de cada fabricante.
