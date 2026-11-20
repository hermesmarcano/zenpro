# ZEN — Guía del proyecto

App de iOS (SwiftUI nativo) que actúa como "commitment device": alarma que solo se
apaga escaneando un QR físico fuera del cuarto, bloqueo duro de apps (Screen Time /
Family Controls) configurable por horario y ubicación, y una lista de tareas con una
única Tarea Más Importante (MIT) por día. Ver `docs/ARCHITECTURE.md` para el diseño
técnico completo y `docs/UX-SPEC.md` (pendiente) para el detalle de cada pantalla.

Tono de producto: minimalista, serio, sin gamificación. Sin mascotas, sin "candy
colors". Destino: App Store (uso personal primero, publicación pública después).

## Contexto de entorno

Esta sesión remota **no tiene Xcode ni un Mac** — no se puede compilar ni correr la
app aquí. La validación real de build ocurre vía CI (GitHub Actions o Xcode Cloud)
una vez exista la cuenta Apple Developer Program. Hasta entonces, todo el código Swift
se escribe con la máxima disciplina posible pero **sin confirmación de compilación
local** — ver ciclo de trabajo abajo para cómo se compensa esto.

## Regla de planificación y edición (obligatoria, todo el proyecto)

Para **cualquier** cambio de código no trivial, seguir siempre este ciclo completo
antes de dar el trabajo por terminado:

1. **Planificar**: desglosar la tarea, identificar qué archivos/módulos toca,
   enumerar explícitamente los edge cases y casos límite relevantes (permisos
   denegados, estados a mitad de camino, condiciones de carrera, límites de iOS,
   fallos de red/hardware, etc.) *antes* de escribir código.
2. **Implementar**: el código mínimo necesario para la tarea planificada, sin
   abstracciones ni features no pedidas.
3. **Revisar**: revisión crítica propia del diff (y con `/code-review` cuando el
   cambio lo amerite) buscando bugs de lógica, condiciones de carrera, fugas de
   memoria/recursos, manejo incorrecto de estados de error o permisos, y
   conflictos con código o convenciones ya existentes en el repo.
4. **Corregir**: arreglar todo lo encontrado en la revisión.
5. Repetir 3–4 hasta que no queden hallazgos pendientes.
6. Solo entonces se considera la implementación limpia y se hace commit.

Como no hay Xcode local, el CI (una vez configurado en M0) es parte **obligatoria**
de este ciclo, no opcional: cualquier fallo de build/lint/test en CI se trata como un
hallazgo de la fase de revisión y bloquea el cierre del ciclo hasta corregirse.

No se pasa a la siguiente feature/milestone con hallazgos de revisión sin resolver.

### Verificación local de la lógica pura

El código que solo depende de Foundation (motor de volumen inteligente, clasificador de
movimiento, políticas de decisión) **se compila y se testea en segundos** con
`scripts/logic-tests.sh`, sin esperar al CI de macOS. Requiere un toolchain de Swift
(≥ 6.0, de swift.org) en el `PATH`; en una sesión nueva sin Xcode se descarga el
tarball de Linux de `download.swift.org` a un directorio temporal.

Regla de diseño que se desprende de esto: la lógica con reglas de negocio (umbrales,
máquinas de estado, decisiones de aceptar/rechazar) va en tipos puros sin UIKit /
SwiftUI / SwiftData / Vision / AVFoundation, y los adaptadores de hardware son capas
finas encima. Todo archivo nuevo de lógica pura se suma a `LOGIC_SOURCES` /
`LOGIC_TESTS` del script. Lo que sí necesita frameworks de Apple (Vision, cámara,
SwiftData, AlarmKit) solo lo valida CI.

## Decisiones técnicas ya tomadas

- **Stack**: SwiftUI nativo, sin frameworks multiplataforma (requerido por Family
  Controls / Screen Time, exclusivo de Swift).
- **Anti-snooze de la alarma**: QR físico fijo fuera del cuarto (no GPS, no NFC).
- **Selección de apps a bloquear**: `FamilyActivityPicker` nativo de Apple — la app
  nunca puede leer nombres/iconos reales de las apps elegidas (limitación de
  privacidad de Apple, no un bug).
- **Distribución**: pensada para App Store desde el inicio (copy y flujos deben
  respetar las reglas reales de Apple, nunca prometer capacidades que Apple no
  permite).
- **Build/test sin Mac**: CI en la nube (GitHub Actions o Xcode Cloud) + TestFlight
  para instalar en el iPhone. Requiere Apple Developer Program ($99/año, aún no
  activada) y la entitlement especial `com.apple.developer.family-controls`
  (aprobación manual de Apple — solicitar en cuanto exista la cuenta, ver M0).

## Variante personal «Xiaomi» (versión god) — regla de separación (obligatoria en la rama)

La rama `claude/xiaomi-note8-layer` es una **variante personal**: la usa solo su dueño, en su Redmi Note 8. Lleva la capa
inteligente (accesibilidad, micrófono, lectura de notificaciones, bloqueo; ver `docs/XIAOMI-LAYER.md`), cosas que **no** pertenecen
a la app normal ni se publicarán en App Store / Google Play. Por eso:

- **Esta rama no se fusiona en `main` ni en ninguna otra rama, y no se abren PR desde ella hacia otra.** Traer `main` a esta
  rama sí está bien (merge de `main` → rama), para recibir las mejoras del núcleo común.
- **Identidad propia**: `applicationId = com.hermesmarcano.zen.xiaomi`, nombre «ZEN Xiaomi», `versionName` con sufijo `-xiaomi`,
  icono con otro fondo. El `namespace` del código sigue siendo `com.hermesmarcano.zen`. Nunca volver al ID de la app normal: ambas
  deben poder instalarse a la vez sin pisarse (el CI `android-xiaomi.yml` falla si el APK no trae el ID de la variante).
- **CI y descarga propios**: `android-xiaomi.yml` publica solo en la pre-release `xiaomi-latest` (archivo `zen-xiaomi.apk`). Jamás
  se publica en `android-latest` (esa es la app normal); `android.yml` ignora esta rama para no duplicar compilaciones.
- **Lo común se mejora en `main`** y se trae aquí. Nada de la capa (servicios de accesibilidad, micrófono en segundo plano,
  `NotificationListener`, permisos asociados) debe aparecer en `main`.
- Cualquier texto o ayuda que nombre al paquete (p. ej. el comando `adb shell pm grant …`) debe derivarse de
  `context.packageName`, no escribirse a mano.
- Esta regla vive en la rama de la variante; `main` solo la tendrá si su dueño la añade allí.

## Android (carpeta `android/`)

Versión nativa Kotlin + Compose instalable por APK. Ver `docs/ANDROID.md`. Mismo ciclo de
trabajo que iOS, pero aquí **sí se compila y se testea en la sesión remota**: hay JDK + SDK de
Android, así que `./gradlew testDebugUnitTest lintDebug assembleRelease` es parte obligatoria de
la fase de revisión antes de cada commit (el CI `android.yml` repite lo mismo).

- Sin emulador ni dispositivo: lo que depende de hardware (sensores, cámara, servicio en primer
  plano, pantalla de bloqueo, fabricantes) queda como "pendiente de validar en dispositivo".
- La lógica con reglas de negocio va en `core/` y `data/` **sin imports de `android.*`** y con
  tests JVM en `app/src/test`; los adaptadores de Android son finos.
- Entorno: exportar `ANDROID_HOME` al SDK (platforms;android-35, build-tools;35.0.0). Si Maven
  Central responde 429, usar `--max-workers=1 -Dorg.gradle.parallel=false`.
- Las pruebas de capa Android corren en JVM con Robolectric (`app/src/test/.../android`): receptores,
  servicio, manifiesto y pantallas Compose. Cada clase de test va en su propia JVM (`forkEvery = 1`):
  los recompositores con relojes en bucle de una clase contaminan a las siguientes. Las pantallas se
  prueban con `mainClock.autoAdvance = false` y se destruyen las actividades al terminar.
- No usar `--refresh-dependencies` con el proxy limitado (429): deja la caché de Gradle con "no
  encontrado". Si pasa, borrar `~/.gradle/caches/modules-2/metadata-*` y reintentar.
- Sonidos personalizables, volumen inicial elegible, rachas, **XP, niveles y logros**: **pedidos
  explícitamente por el usuario para Android** ("gamificarlo", "que sea divertido de usar"). Se mantienen
  sobrios y sin patrones oscuros: sin mascotas ni colores de juego, sin clasificaciones, **sin castigos ni
  avisos de "vas a perder tu racha"**, XP con tope diario, y reglas justas (días sin alarma y pausas de
  viaje no rompen la racha; el código de respaldo ni suma ni rompe). Ver `docs/ANDROID-UX-GAMIFICACION.md`.
  No añadir más gamificación sin que se pida.
- Tareas: hasta 10 por día, la prioridad la da la **posición** (1 prioritaria · 3 medias · 6 bajas) y se
  reordena arrastrando (`sh.calvin.reorderable`). Solo se completan las de hoy.
- Modo viaje: pausa con fecha o ancla alternativo; el primer día cuenta desde la **próxima alarma**
  (`TripPolicy.startDay`), no desde hoy.
- Todo nivel de volumen se expresa como sonoridad *percibida* (0..1) y pasa por `VolumeCurve` antes de
  llegar a un reproductor; nunca usar el nivel como ganancia lineal (causaba que "15 %" sonara fuerte).
- `zen-sideload.jks` es una clave pública solo para sideload; nunca para Google Play.
- **Capa inteligente del Xiaomi** (`layer/`, ver `docs/XIAOMI-LAYER.md`): todo apagado por defecto, visible y con mínimo privilegio.
  Reglas que no se rompen: el cerebro puede restringir pero no relajar; aflojar un compromiso tarda 24 h; nunca se envía un mensaje
  sin aprobación del usuario (y como mucho una vez); Ajustes, el teléfono y ZEN nunca se bloquean (`NeverBlock`); título y cuerpo de
  una notificación se redactan juntos; todo trabajo en el hilo de E/S pasa por `LayerRuntime.runIo` (una excepción suelta en un hilo mata
  la app); un servicio en primer plano solo se arranca con `AmbientAudioService.start` (comprueba antes sus precondiciones). Los editores de la
  pantalla son paneles y no diálogos: un campo de texto dentro de un `AlertDialog` impide que Compose llegue a reposo en Robolectric.
- Entorno remoto sin SDK: se instala en `~/android-sdk` (cmdline-tools 11076708 + `platforms;android-35` + `build-tools;35.0.0`) y se usa
  `--max-workers=1 -Dorg.gradle.parallel=false` (Maven Central responde 429 en cuanto se paraleliza).
