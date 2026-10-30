# ZEN — Arquitectura técnica (MVP)

Este documento traduce el diseño UX (ver el canvas de mockups y `docs/UX-SPEC.md`)
a decisiones técnicas concretas de iOS. Todo lo marcado **[VERIFICAR]** depende de
una API muy nueva (AlarmKit, iOS 26) que no se puede probar en este entorno sin
Xcode — queda como primera tarea del ciclo de revisión en cuanto haya acceso a un
build real.

## 1. Targets del proyecto Xcode

| Target | Tipo | Responsabilidad |
|---|---|---|
| `ZEN` | App (SwiftUI) | UI completa, lógica de negocio, persistencia |
| `ZENDeviceActivityMonitor` | App Extension (Device Activity Monitor) | Aplica/quita el shield automáticamente al entrar/salir de una ventana de horario, incluso con la app cerrada |
| `ZENShieldConfiguration` | App Extension (Shield Configuration) | Personaliza título/subtítulo/botones de la pantalla nativa de bloqueo |
| `ZENShieldAction` | App Extension (Shield Action) | Maneja el tap en los botones del shield (abrir ZEN) |

Todos comparten un **App Group** (`group.<bundleID>.zen`) para el estado mínimo que
necesitan las extensiones (reglas de horario/ubicación resueltas, no la selección de
apps en sí — esa la gestiona `ManagedSettingsStore` directamente y permanece opaca).

Widget de Lock Screen con el MIT: **fuera del MVP**, se evalúa en v1.1.

## 2. Frameworks de Apple por feature

- **Alarma + QR**: AlarmKit (iOS 26+) para el disparo garantizado a nivel de OS
  (salta DND, funciona con la app cerrada) **[VERIFICAR]**; AVFoundation
  (`AVCaptureMetadataOutput`, no Vision — más simple y es el estándar para
  escaneo de QR en tiempo real) para escanear; CoreImage para generar el QR.
- **Bloqueo de apps**: `FamilyControls` (autorización + picker), `ManagedSettings`
  (aplicar el shield), `DeviceActivity` (automatizar por horario vía la extensión).
- **Ubicación**: `CoreLocation`, geofencing con `CLCircularRegion` (máx. ~20 regiones
  monitoreadas por app — limita el número de "lugares" configurables).
- **Persistencia**: `SwiftData` para todo el modelo local (sin backend en el MVP).
- **Notificaciones**: `UserNotifications` para prime de permisos y recordatorios.

## 3. Modelo de datos (SwiftData, resumen)

```swift
@Model final class AlarmConfig {
    var id: UUID
    var time: DateComponents          // hora/minuto
    var repeatDays: Set<Weekday>
    var isEnabled: Bool
    var soundName: String
    var escalationEnabled: Bool
    var anchorID: UUID                // -> QRAnchor
    var graceSeconds: Int             // duración de "necesito 2 minutos"
}

@Model final class QRAnchor {
    var id: UUID
    var label: String                 // "Espejo del baño"
    var secretToken: String           // aleatorio, generado on-device, nunca sale del dispositivo
    var backupCode: String            // código numérico de respaldo (ver edge case §5)
    var confirmedAt: Date?
}

@Model final class BlockRuleSet {
    var id: UUID
    var selectionData: Data           // FamilyActivitySelection codificada — opaca
    var scheduleWindows: [ScheduleWindow]
    var locationRules: [LocationRule]
}

struct ScheduleWindow: Codable {
    var start: DateComponents
    var end: DateComponents
    var days: Set<Weekday>
    var isUnlockWindow: Bool
}

@Model final class LocationRule {
    var id: UUID
    var label: String
    var latitude: Double
    var longitude: Double
    var radiusMeters: Double
    var isUnlockZone: Bool
}

@Model final class TaskItem {
    var id: UUID
    var text: String
    var isDone: Bool
    var isMIT: Bool
    var dayKey: String                // bucket de día local, ej. "2026-08-24"
}

@Model final class UnlockRequest {    // auditoría del flujo de fricción
    var id: UUID
    var requestedAt: Date
    var reason: String
    var grantedAt: Date?
}
```

## 4. Flujos críticos

### 4.1 Alarma → QR → MIT
1. `AlarmManager` (AlarmKit) programa la alarma con `secondaryButtonBehavior`
   apuntando a abrir la app en vez de detener directamente **[VERIFICAR API exacta]**
   — el objetivo es que ningún botón del sistema apague la alarma sin pasar por
   ZEN.
2. Al abrir, se presenta la vista de sonando a pantalla completa con
   `AVCaptureSession` embebido.
3. El QR escaneado se valida localmente: `anchorID` + `secretToken` deben coincidir
   con un `QRAnchor` guardado. Sin red, sin backend.
4. Éxito → `AlarmManager.stop(id:)` + log de dismissal + navegación a la pantalla de
   MIT.

### 4.2 Bloqueo automático por horario
`DeviceActivityCenter.startMonitoring` registra los `ScheduleWindow` como
`DeviceActivitySchedule`. La extensión `ZENDeviceActivityMonitor` recibe
`intervalDidStart`/`intervalDidEnd` y aplica/retira `ManagedSettingsStore().shield.applications`
directamente — funciona aunque la app principal esté cerrada.

### 4.3 Desbloqueo por ubicación
`CLLocationManager` con monitoreo de región en la app principal. Al entrar a una
`LocationRule` marcada como zona de desbloqueo, se recalcula el estado combinado
(horario ∧ ubicación) y se reaplica el shield completo — nunca un toggle parcial,
para evitar condiciones de carrera entre el trigger de horario (extensión) y el de
ubicación (app principal). Fuente de verdad única: un `BlockingStateCoordinator` que
escribe el estado resuelto al App Group.

### 4.4 Fricción de desbloqueo anticipado
Cooldown persistido en App Group (sobrevive el cierre de la app). Al cumplirse +
justificación escrita, se levanta el shield por una ventana de gracia (ej. 30 min) y
se reprograma su reaplicación automática al vencer.

## 5. Edge cases técnicos (más allá de los de UX)

| Caso | Resolución |
|---|---|
| App matada por el sistema cuando debe sonar la alarma | AlarmKit dispara a nivel OS, independiente del proceso de la app (como el Reloj nativo) |
| Extensión DeviceActivityMonitor tiene límite de memoria estricto y sin red | Mantenerla trivial: solo llamadas a `ManagedSettingsStore`, sin lógica pesada |
| Usuario desactiva Screen Time desde Ajustes (fuera de ZEN) | Chequear `AuthorizationCenter.shared.authorizationStatus` en cada foreground; mostrar banner honesto, nunca fingir que el bloqueo sigue activo |
| Límite de ~20 regiones geofenced por app | Tope de reglas de ubicación configurables en el MVP (ej. máx. 8) |
| Permiso de ubicación baja de "Siempre" a "Mientras se usa" | El geofencing deja de funcionar en background; detectar y pedir re-autorización |
| Cámara denegada específicamente (distinto de otros permisos) | **Código de respaldo numérico** impreso junto al QR como vía de emergencia — si no existe esto, un fallo de cámara deja al usuario sin forma de apagar la alarma. Es un edge case de seguridad, no cosmético |
| Cambio de zona horaria / DST | `DeviceActivitySchedule` maneja DST en horario local automáticamente (framework de Apple); el repeat de la alarma debe recalcularse en `significantTimeChangeNotification` |
| Revisión de App Store para apps de Screen Time | Solicitar la entitlement `com.apple.developer.family-controls` apenas exista la cuenta Developer (puede tardar días/semanas en aprobarse) — bloqueante para TestFlight/App Store, no para desarrollo local |
| Foto del QR tomada por otra persona / capturada de antemano | Aceptado como limitación conocida (igual que apps como Alarmy) — el objetivo es romper el hábito de un toque, no defender contra un atacante sofisticado; no vale la pena la complejidad de rotar el token |
| AlarmKit exige un `stopButton` real y no-opcional en la alerta | Confirmado por CI: no existe forma de construir una alarma sin un botón de "Detener" garantizado por el sistema — misma filosofía de plataforma que el override de Screen Time desde Ajustes. Se acepta como límite honesto (botón "Detener" real, sin fingir que no existe); rastrear cuándo se usó sin pasar por el QR es mejora de v1.1 vía `stopIntent` propio, no bloquea el MVP |
| Se borra un `QRAnchor` que una `AlarmConfig` sigue referenciando | Hoy no hay UI para borrar anclas, así que es teórico. Cuando se construya esa UI (M5/M6), debe bloquear o advertir el borrado si hay alarmas dependientes — nunca dejar que `AlarmRingingView` se quede sin ancla en runtime, porque ese estado no debe tener un botón de salida (sería una puerta trasera para apagar la alarma sin QR) |

## 6. Hoja de ruta por milestones

Cada milestone se ejecuta bajo el ciclo de planificación/código/revisión definido en
`CLAUDE.md` — no se avanza al siguiente con hallazgos abiertos.

- **M0 — Fundaciones**: cuenta Apple Developer, solicitud de entitlement Family
  Controls, scaffold del proyecto Xcode (targets + App Group), pipeline CI
  (GitHub Actions/Xcode Cloud) con build+test en cada push, modelos SwiftData.
- **M1 — Ancla QR**: generación del QR, flujo de confirmación de ubicación, escaneo
  y validación local, código de respaldo.
- **M2 — Alarma**: creación/edición, integración AlarmKit, pantalla de sonando,
  dismissal vía QR, handoff a MIT. *(El núcleo del producto — máxima prioridad de
  revisión.)*
- **M3 — Bloqueo de apps**: picker nativo, reglas de horario, extensión
  DeviceActivityMonitor, personalización del Shield.
- **M4 — Ubicación**: geofencing, reglas de lugar, fricción de desbloqueo anticipado.
- **M5 — Tareas/MIT + Home**: lista de tareas, dashboard.
- **M6 — Endurecimiento**: recorrido completo de edge cases de este documento, beta
  en TestFlight, preparación de envío a App Store.

## 7. Ancla por foto y alarma inteligente (añadido después de M2)

### 7.1 Ancla por foto (alternativa al QR)

El usuario registra un punto (ej. "Patio") con 1–3 fotos de referencia tomadas con la
cámara; para apagar la alarma debe tomar otra foto del mismo lugar.

- **Comparación**: `VNGenerateImageFeaturePrintRequest` (Vision), distancia entre
  feature prints; se acepta si la distancia mínima contra cualquier referencia es
  estrictamente menor al umbral. Se guardan las fotos de referencia normalizadas (no
  los feature prints) y los de la candidata y las referencias se calculan juntos en cada
  comparación: así comparten siempre la misma revisión de Vision y un cambio de
  revisión en una versión futura de iOS no invalida el ancla.
- **Solo cámara en vivo**: sin acceso a la fototeca, para que no se pueda "enviar" una
  foto vieja. La foto de referencia **no se muestra** en la pantalla de la alarma (solo
  la etiqueta del lugar), para no regalar la respuesta.
- **Umbral sin calibrar**: no hay dispositivo para medir distancias reales
  (luz del día vs. madrugada). El umbral es una constante única, `PhotoMatcher`,
  marcada **[CALIBRAR EN DISPOSITIVO]**. Mitigaciones: el alta exige una foto de
  verificación (el fallo se ve al configurar, no a las 6am), se recomienda una 2ª
  referencia con poca luz, y siempre existe el código de respaldo.

| Caso | Resolución |
|---|---|
| Cámara denegada / sin hardware | Código de respaldo numérico (igual que el QR) |
| Foto oscura o movida a la madrugada | Reintento ilimitado; tras 3 fallos se sugiere encender la luz o usar el respaldo |
| Orientación distinta entre foto de referencia y de verificación | Ambas se normalizan (orientación corregida, máx. 800 px) antes de comparar |
| Fotografiar una foto/pantalla del lugar | Limitación aceptada (igual que fotografiar un QR impreso) |
| Revisión de feature print incomparable | La distancia lanza error → se trata como "no coincide"; las referencias se recalculan siempre |
| Modelo de Vision degenerado (el simulador devuelve distancia 0 para imágenes distintas — visto en CI) | Una distancia exactamente 0 se descarta como inválida: nunca ocurre entre dos fotos tomadas por separado, y de aceptarla "todo coincidiría" y la alarma se apagaría sin ir al lugar. Resultado: "no se pudo analizar" (y la verificación del alta lo delata al configurar). Los tests de semántica de similitud solo corren en dispositivo; en el simulador solo se prueba que el pipeline funciona |

### 7.2 Volumen inteligente (modo armado)

Comportamiento pedido: arranca bajo y vibrando; al **coger** el teléfono baja a la
mitad y, mientras nos movemos, a ~10 %; si lo **dejamos quieto** sin apagar la alarma
vuelve al 50 % y sube progresivamente a 100 %.

**Restricción de plataforma**: el sonido de una alarma de AlarmKit lo reproduce el
sistema y la app **no puede cambiar su volumen**; además la app ni siquiera está en
ejecución cuando suena. Por eso el volumen dinámico exige que ZEN reproduzca el
sonido él mismo, lo que solo es posible con la app viva: **modo armado**. Al
acostarse, el usuario arma la alarma; ZEN mantiene una sesión de audio en segundo
plano (`UIBackgroundModes: audio`, buffer silencioso) y, a la hora, reproduce el tono
con la curva de volumen.

- **Red de seguridad**: al armar, la alarma de AlarmKit se mueve a `hora + 2 min`;
  si ZEN fue terminado por iOS (o el usuario lo cerró deslizando), la alarma del
  sistema suena igualmente, con 2 min de retraso en el peor caso. Al desarmar o
  apagar la alarma con QR/foto se reprograma/cancela. Sin armar, la alarma suena a la
  hora exacta con el sonido normal del sistema.
- **Máquina de estados** (`SmartVolumeController`, pura y testeable): `initialRamp`
  (0.15→1.0 en 90 s) → `carried` (mitad del volumen al coger, 0.10 mientras hay
  movimiento) → `leftBehind` (tras 5 s quieto: 0.5 → 1.0 en 60 s). Cambios de volumen
  limitados por pendiente para que siempre sean progresivos.
- **Detección de movimiento** (`MotionClassifier`, pura y testeable): energía RMS en
  ventana de 1.5 s sobre aceleración sin gravedad + velocidad de rotación (CoreMotion
  `deviceMotion`, no requiere permiso), con histéresis. **[CALIBRAR EN DISPOSITIVO]**.
- **Compromiso del usuario**: mientras la cámara de escaneo/foto está abierta, el
  teléfono se considera "en mano" (volumen bajo) aunque esté quieto; si no, sostenerlo
  quieto para apuntar al QR dispararía el estado `leftBehind`.
- **Vibración**: pulsos con `AudioServicesPlaySystemSound` (la intensidad no es
  controlable): intervalo de 2.0 s a 0.6 s según el volumen; sin vibración mientras se
  lleva el teléfono.

| Caso | Resolución |
|---|---|
| iOS termina la app durante la noche | Alarma de AlarmKit a `hora + 2 min` como red de seguridad |
| Llamada/Siri/otra app interrumpe el audio | Observador de `AVAudioSession.interruptionNotification`: se reactiva la sesión y se reanuda el tono |
| Teléfono silenciado / No Molestar | La categoría `.playback` ignora el interruptor de silencio; DND no bloquea audio de app |
| Auriculares/Bluetooth conectados | La ruta de salida queda fuera de control de la app; se documenta como riesgo (el sonido podría ir a los auriculares). Pendiente de decidir en M6 |
| Reloj/cambio de zona horaria con la sesión armada | La hora de disparo se recalcula desde `AlarmConfig` al reanudar la app |
| Se sube/baja el volumen físico del teléfono | El volumen de ZEN es relativo al del sistema; se recomienda dejarlo alto al armar |

### 7.3 Piezas y flujo (implementado)

- `SmartAlarmSession` (armar/desarmar, sonar, red de seguridad) y `AlarmCoordinator`
  (decide qué alarma muestra la pantalla de apagado y cómo se silencia: sesión
  inteligente -> `finishRinging`; alarma del sistema sin armar -> `AlarmScheduler.stop`).
- `WakeNotification`: aviso sin sonido en la pantalla de bloqueo a la hora de la alarma.
  Con la alarma armada el tono suena desde ZEN pero la app no se abre sola; este aviso
  es lo que se toca para llegar a la pantalla de apagado. Bajo un Focus/No Molestar el
  aviso puede no mostrarse; el tono sí suena y la alarma del sistema entra a los 2 min.
- `OpenZENIntent`: acción del botón "Abrir ZEN" de la alarma del sistema (sin armar).
  Abre la app y fija la alarma que está sonando.
- `HomeView`: pantalla mínima funcional (alarmas, altas de ancla QR/foto, armar). El
  dashboard del diseño llega en M5.
- Las reglas viven en tipos puros con tests locales (`SmartVolumeController`,
  `MotionClassifier`, `NextFireCalculator`, `ScheduleShift`, `AlarmToneSynth`,
  `PhotoMatchPolicy`); `AlarmTonePlayer`, `MotionMonitor` y la vibración son adaptadores
  finos que solo se validan en un dispositivo real.

**Pendiente de validar en dispositivo** (no es posible sin hardware): que la sesión de
audio silenciosa mantenga viva la app toda la noche, la sensación real de la curva de
volumen, los umbrales de `MotionClassifier`, el umbral de coincidencia de fotos, y que
`.defer`/`OpenZENIntent` abran la app como se espera.
