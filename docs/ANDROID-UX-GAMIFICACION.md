# ZEN Android — Análisis de UX y gamificación

Cliente ideal: alguien que quiere **levantarse a su hora** y **cumplir sus objetivos sin distracciones**.
Este documento recoge la evaluación de la interfaz, las decisiones de gamificación y lo que queda por
validar. Lo marcado como *juicio* es criterio de diseño, no un dato medido; ZEN no envía telemetría, así
que nada de esto se ha probado con usuarios reales todavía.

## 1. Evaluación heurística de la interfaz anterior

Se revisó la app tal como estaba (una sola pantalla de alarmas) con las 10 heurísticas de Nielsen y con la
pregunta "¿qué hace el cliente ideal en los primeros 10 segundos de una mañana?".

| # | Hallazgo | Heurística | Qué se hizo |
|---|----------|-----------|-------------|
| 1 | La app abría en una lista de alarmas: no había un lugar para "mi día". Levantarse no llevaba a nada. | Ajuste con el mundo real, coincidencia con el objetivo | Pestaña **Hoy** como pantalla de inicio: tareas, anillo de progreso, próxima alarma y nivel. |
| 2 | Tras apagar la alarma no pasaba nada más: la rutina se rompía justo cuando el teléfono distrae más. | Flujo de tarea | La pantalla "Despierto" muestra **la tarea prioritaria de hoy** y lleva directo a las tareas. |
| 3 | El aviso de permisos (cámara, notificaciones, pantalla completa) solo estaba en Alarmas. Sin ellos la alarma suena pero no se apaga bien. | Visibilidad del estado, prevención de errores | Aviso también en **Hoy**, con botón "Revisar". |
| 4 | Borrar una alarma era inmediato y sin vuelta atrás. | Control del usuario, prevención de errores | Confirmación en dos toques (sin diálogo, para no tapar la pantalla). |
| 5 | Con "Sin alarmas activas" no había camino para crear una. | Reconocer mejor que recordar | Botón "Crear mi primera alarma" en el resumen. |
| 6 | Ajustes siempre volvía al inicio aunque vinieras de Alarmas. | Consistencia, control del usuario | "Listo" y "atrás" vuelven a la pestaña de origen. |
| 7 | Estar fuera de casa dejaba la app inservible (el ancla está en casa). | Flexibilidad y eficiencia de uso | **Modo viaje**: pausa con fecha, o apagar en otro lugar. |
| 8 | Sin respuesta a las acciones (marcar, añadir, eliminar). | Visibilidad del estado | Háptico, animación de casilla, avisos breves (+XP, logros) y "Deshacer" al eliminar. |
| 9 | Reordenar y priorizar exigía menús. | Eficiencia | **Arrastrar y soltar**: la posición decide la prioridad. |

### Mapa de la app

```
Hoy (inicio)      Alarmas                   Progreso
 ├ resumen         ├ permisos                ├ nivel y XP
 ├ Hoy / Mañana    ├ Modo viaje ─► pantalla  ├ rachas
 ├ añadir rápido   ├ lista de alarmas        └ logros
 └ tareas ⠿        └ Nueva alarma ─► editor
Ajustes: sonidos, volumen inicial, sonido de éxito
```

Barra inferior solo en las tres pestañas; las pantallas secundarias (editor, viaje, ajustes, cámara) la
ocultan para no competir con su acción principal.

### Una mañana típica (el camino que se optimizó)

1. Suena la alarma, bajito (10–30 %, elegible) y sube.
2. Va al lugar (QR o foto) → se apaga y suena el sonido de éxito.
3. Pantalla **Despierto**: tiempo que tardó, racha, XP, y **lo primero de hoy** (su tarea prioritaria).
4. Un toque a "Ir a mis tareas": la lista de hoy, con la prioritaria arriba.
5. Marca la tarea al terminar: háptico, +30 XP, "Prioritaria cumplida".

## 2. Tareas: reglas y por qué

- **Hasta 10 al día: 1 prioritaria · 3 medias · 6 bajas.** La prioridad la da la **posición**, no una etiqueta.
  Arrastrar ya cambia la prioridad; no hay menús que mantener. *Juicio:* limitar a una prioritaria obliga a
  decidir, y 10 es suficiente sin convertir la lista en un cajón.
- El campo de añadir dice **en qué nivel va a entrar** ("entra como media · +15 XP") y cuántas quedan (3/10).
- **Hoy / Mañana.** Mañana se planifica, no se completa: evita ganar XP por adelantado y refuerza el ritual
  de decidir el día la noche anterior.
- **Pendientes:** al día siguiente aparece "Te quedaron N pendientes · Traer a hoy". No se arrastran solas
  (no se acumula deuda silenciosa) y se olvidan a los 7 días.
- **Eliminar con Deshacer**, y **editar/subir/bajar** desde el menú ⋮ (alternativa accesible al arrastre).
- Una pista de "mantén pulsado ☰ y arrastra" aparece una vez y se cierra con "Entendido".

## 3. Gamificación

### Principios

Se parte de la teoría de la autodeterminación (autonomía, competencia, relación) y de no usar patrones
oscuros. *Juicio:* en una app cuya promesa es disciplina, el juego debe **reflejar** el progreso, no
sustituirlo ni castigar.

- **Competencia:** nivel, XP, rachas y logros dan retroalimentación visible de avance.
- **Autonomía:** el volumen, los sonidos y las tareas los elige el usuario; no hay objetivos impuestos.
- **Relación:** no hay clasificaciones ni social. Es un juego contra uno mismo, a propósito.

### Qué da XP

| Acción | XP | Notas |
|---|---|---|
| Despertar verificado en el ancla | 10 | Una vez al día |
| Tarea prioritaria | 30 | Por la posición en que esté al completarla |
| Tarea media | 15 | |
| Tarea baja | 5 | |
| Día completo (despertar + prioritaria el mismo día) | 25 | Una vez al día, en cualquier orden |
| Día redondo (todas las tareas, mínimo 3) | 20 | Una vez al día |

- **Tope de 100 XP al día por tareas**: borrar y recrear tareas no da más.
- Marcar/desmarcar nunca paga dos veces; desmarcar no quita lo ganado.
- Solo se completan tareas **de hoy**.

### Niveles y logros

Nivel *n* pide 50·n·(n−1) XP en total (100, 300, 600, 1000…), con títulos sobrios (Despertando → Maestro
del alba) y tope en el nivel 100. Hay 13 logros (primer despertar, 3/7/30 días, "sin demora" < 1 min,
primera prioritaria, rachas de prioritaria, día redondo, diez de diez, modo viaje, 100 tareas, nivel 5).
Ninguno es un castigo ni caduca.

### Rachas (reglas de justicia)

- **Racha de despertar:** solo cuenta llegar al ancla. El **código de respaldo no suma ni rompe**.
- **Racha de prioritaria:** días con la tarea prioritaria cumplida.
- Los **días sin alarma programada no rompen** la racha (alarma de lunes a viernes: el fin de semana no cuenta).
- El **modo viaje en pausa no rompe** la racha; al terminarlo antes, los días no usados vuelven a contar.
- Una racha rota se reinicia en silencio: **sin mensajes de culpa ni notificaciones de "vas a perderla"**.

### Riesgos conocidos y mitigaciones

| Riesgo | Mitigación | Estado |
|---|---|---|
| Sobrejustificación (hacerlo por los puntos) | Pocos números, sin recompensas externas; los puntos reflejan acciones que ya importan | Juicio, a observar |
| Ansiedad por la racha | Sin avisos de pérdida, rachas justas con días libres y viajes | Aplicado |
| Hacer trampas (tarea trivial arriba) | Es un juego **contra uno mismo**; el XP tiene tope diario. No se vigila: si el usuario se engaña, lo sabe | Aceptado y documentado |
| Aburrimiento a largo plazo | Niveles hasta 100 y logros de largo plazo (30 días, 100 tareas) | Parcial |
| Distraer del objetivo | Nada de animaciones largas ni sonidos extra al completar tareas; el sonido "grande" es solo el del despertar | Aplicado |

### Divertido, sin ser infantil

Casilla redonda con rebote, anillo de progreso del día, barra de nivel que se llena, háptico al completar
y al arrastrar, un sonido de éxito elegible (o tu propio archivo) y mensajes cortos. No hay mascotas,
confeti ni colores de juego: un solo acento (ámbar) sobre fondo oscuro, coherente con el tono del producto.

## 4. Qué falta y cómo validarlo

- **Probar en dispositivo real** (no hay emulador en el entorno de desarrollo): el arrastre en distintos
  teléfonos, el háptico, la barra inferior con navegación por gestos y por botones, el teclado.
- **TalkBack:** el arrastre tiene alternativa (menú Subir/Bajar), pero falta una pasada real con lector de pantalla.
- **Pruebas con usuarios:** tres a cinco personas del perfil durante una semana. Observar: ¿planifican la
  noche anterior? ¿abren Hoy tras despertar? ¿usan el modo viaje? Hasta entonces los umbrales de XP son estimaciones.
- **Siguientes pasos sugeridos:** temporizador de enfoque para la tarea prioritaria, recordatorio nocturno
  para planificar mañana, y bloqueo de apps durante la primera hora (lo más pesado y lo más diferencial).
