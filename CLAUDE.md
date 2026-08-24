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
