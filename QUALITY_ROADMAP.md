# RawLite — Plan maestro de calidad y producto

Actualizado: 2026-08-02  
Línea base: `v0.32`  
Dispositivo objetivo: Google Pixel 6  
Prioridad de producto: captura íntegra, operación manual esencial, eficiencia
energética y conservación del material original.

## Propósito

Este documento es la fuente única de verdad para los defectos, pruebas y
cambios pendientes de RawLite. Evita que corregir un problema haga desaparecer
de vista los demás o que un hallazgo nuevo cambie silenciosamente el alcance de
una versión.

Un cambio no se considera terminado solo porque compile o porque funcione una
vez. Debe tener una causa identificada, una prueba reproducible, evidencia de
que la corrección funciona y una regresión proporcional al riesgo.

## Reglas no negociables

- Nunca borrar, reemplazar ni modificar una toma del usuario. Los conversores y
  validadores abren el original en solo lectura.
- El metraje creado exclusivamente para QA se identifica, se verifica y se
  elimina al terminar la prueba.
- Compilar y ejecutar Android Lint con JDK 21. JDK 26 queda fuera del flujo por
  el fallo interno conocido de Lint.
- Probar en el Pixel 6 real mediante ADB cuando esté conectado y desbloqueado.
- Conservar Open Sans, fondo negro, amarillo `#FFC100`, controles blancos y
  rojo reservado a grabación o acciones destructivas.
- No cambiar geometría, hitboxes, orden de foco, navegación por D-pad ni
  distribución para una mano sin aprobación previa.
- Verificar vertical, horizontal izquierda y horizontal derecha. Verificar modo
  diestro y zurdo cuando el elemento afectado tenga una variante de mano.
- No generar proxies, miniaturas costosas ni conversiones durante la grabación.
- Estabilizar 2K antes de prometer o habilitar 4K.
- Cada versión entregable debe tener número, changelog, etiqueta Git, artefacto
  compilado y evidencia de regresión.

## Leyenda de estado

- `[x]` terminado y validado con evidencia.
- `[~]` implementado parcialmente o pendiente de aceptación completa.
- `[ ]` pendiente.
- `[!]` bloqueo crítico, riesgo de pérdida de toma o regresión.
- `[B]` bloqueado deliberadamente por una dependencia anterior.

## Contrato de formatos

Los tres modos deben distinguirse en documentación, diagnósticos, galería y
pruebas. No son nombres intercambiables.

| Modo | Captura | Salida principal | Uso previsto |
| --- | --- | --- | --- |
| CinemaDNG FULL | RAW de sensor completo | Secuencia `frame_XXXXXX.dng` creada directamente con Android `DngCreator` | Máxima resolución y referencia de compatibilidad |
| RAW10 2K | Bayer RAW10 empaquetado, 2032 × 1536 | `clip.raw10`, metadatos y conversión posterior a DNG | Captura RAW sostenida y eficiente |
| PURE 10 | P010 procesado por la cámara | HEVC Main10 HLG en `pure10.mp4`, más WAV y `sync.json` | Video reproducible por hardware con flujo HLG |

RAW10 no debe presentarse como un archivo que Resolve pueda abrir directamente.
El producto compatible con Resolve es la secuencia CinemaDNG resultante del
conversor, acompañada por su audio sincronizado.

## Estado ejecutivo

| Prioridad | Área | Estado actual | Puerta pendiente |
| --- | --- | --- | --- |
| P1 | Integridad RAW y Resolve | `[x]` Conversor corregido, probado e importado en Resolve Studio 20 en `v0.31` | Mantenerlo como regresión de cada release |
| P2 | Estabilidad de grabación | `[!]` Hay reportes de bloqueo, toma cortada, preview estirado y orientación automática incorrecta | Matriz de grabación sin bloqueo, corrupción ni cambio geométrico |
| P3 | Audio y sincronización | `[~]` Cola final y continuidad corregidas en `v0.32`; falta prueba prolongada | Clips de 1, 5, 10 y 30 min sin doble audio, cortes ni deriva |
| P4 | Reproductor RAW | `[~]` Revelado nativo y archivo persistente desde `v0.31`; aún requiere estrés y optimización | Búsqueda/stepping inmediatos y reproducción estable |
| P5 | Color, exposición y HLG | `[!]` Sesgo verdoso, desaturación y ruido cromático con aumento de color no caracterizados | Preview y archivo comparables; saturación cromáticamente estable |
| P6 | Rendimiento, temperatura y 4K | `[B]` No iniciar hasta cerrar estabilidad 2K y caracterización de color | 4K fiable o explícitamente marcado como limitado |
| P7 | Automatización y lanzamiento | `[~]` Hay validadores, pruebas y Lint; falta una matriz instrumentada y proceso de release completo | Release reproducible con evidencia y checklist firmado |

## Método de trabajo por bloques

Cada bloque de trabajo sigue este orden:

1. Congelar la línea base y confirmar que las tomas del usuario están
   respaldadas.
2. Definir un caso mínimo que reproduzca el defecto y guardar sus métricas, no
   solo una impresión visual.
3. Cambiar una causa a la vez. Un hallazgo relacionado se registra con un ID;
   no se incorpora silenciosamente al mismo parche.
4. Ejecutar la prueba específica hasta que falle antes y pase después.
5. Ejecutar la regresión común de modos, FPS y orientación que corresponda.
6. Comparar rendimiento, temperatura, memoria y tamaño contra la línea base.
7. Actualizar este documento y el changelog; después crear versión y etiqueta.

Si aparece un defecto que puede perder o corromper una toma, ese hallazgo pasa
por delante de una mejora visual. Si no amenaza el material, permanece en su
prioridad y no interrumpe el bloque activo.

## P1 — Integridad RAW y compatibilidad con Resolve

### Objetivo

Garantizar que toda toma RAW10 cerrada correctamente pueda convertirse en una
secuencia CinemaDNG íntegra, reconocida como un solo clip por DaVinci Resolve
Studio 20.

Estado: `[x]` aceptado en `v0.31`. Los controles siguientes permanecen como
regresión obligatoria; no constituyen un nuevo bloque de implementación salvo
que una versión futura vuelva a fallarlos.

### Trabajo

- `[x]` Diferenciar CinemaDNG FULL, RAW10 2K y PURE 10 en el contrato técnico.
- `[x]` Comparar un DNG directo de Android `DngCreator` con uno creado por el
  conversor, incluyendo IFD, tipos y orden de etiquetas.
- `[x]` Corregir etiquetas y estructura que Resolve interpreta de forma
  estricta. La corrección quedó en `v0.31`.
- `[x]` Verificar por fotograma:
  - cantidad y numeración temporal;
  - dimensiones, stride y tamaño de payload;
  - patrón CFA y fase Bayer;
  - black level, white level y bits efectivos;
  - matrices de calibración y balance de blancos;
  - orientación y crop;
  - timestamps monotónicos y huecos de cadencia.
- `[x]` Importar la secuencia convertida en Resolve Studio 20 y contrastarla con
  CinemaDNG FULL de la misma escena.
- `[x]` Validar que los hold frames mantengan tiempo y audio sin alterar el RAW
  original.
- `[ ]` En cada release, repetir una conversión corta y conservar únicamente el
  reporte de validación, salvo que el usuario decida conservar el metraje QA.

### Prueba de aceptación

Aceptado en `v0.31`: una toma RAW10 2K se convierte sin errores; Resolve la muestra
como un único clip CinemaDNG, con todos sus fotogramas, duración, geometría,
orientación y audio correctos. La toma fuente conserva hash y fecha de
modificación.

## P2 — Estabilidad de grabación

### Objetivo

La cámara debe conservar captura, geometría y capacidad de respuesta desde que
se pulsa REC hasta que todos los escritores terminan y la toma queda cerrada.

### Defectos que deben reproducirse y cerrarse

- `[!]` Congelamiento de toda la aplicación durante PURE 10.
- `[!]` Archivo ocasionalmente cortado o con apariencia de RAW incompleto.
- `[!]` Preview que se estira al iniciar la grabación.
- `[!]` Grabación automática invertida o recortada.
- `[ ]` Recuperación segura ante fallo de actividad, cámara, audio o encoder.
- `[~]` A 30 FPS se observó un descarte en captura durante una prueba de unos
  31,6 segundos; no debe ocultarse aunque el encoder haya cerrado bien.

### Trabajo

- Instrumentar por separado estado de sesión Camera2, cola de `ImageReader`,
  escritor RAW, encoder, muxer, audio y UI.
- Medir backpressure, buffers pendientes, GC, memoria nativa y tiempos de cierre.
- Congelar al iniciar REC la transformación derivada de orientación física,
  orientación del sensor y crop; no reinterpretarla a mitad de toma.
- Registrar dimensiones de superficie, aspect ratio y matriz del preview antes,
  durante y después de grabar.
- Revisar el camino automático y manual como configuraciones distintas, no solo
  como valores diferentes de ISO/exposición.
- Hacer el cierre idempotente: cada recurso se cierra una vez y una excepción no
  impide cerrar los demás.
- Evaluar archivo temporal/estado `recording` y promoción a `closed` sin
  sobrescribir una toma existente. Una toma incompleta debe marcarse y
  conservarse para recuperación; nunca presentarse silenciosamente como válida.
- Confirmar que captura, encoder, audio, telemetría y UI no compiten de forma
  innecesaria por el mismo hilo, CPU o memoria.

### Matriz mínima

- Modos: RAW10, CinemaDNG y PURE 10.
- Cadencias: 24 y 30 FPS.
- Orientaciones: vertical, horizontal izquierda y horizontal derecha.
- Exposición: automática y manual.
- Mano: diestra y zurda cuando cambie la interfaz.
- Duración escalonada: 30 s para cobertura amplia y 5 min para las combinaciones
  de mayor carga. Las pruebas largas de P3 se reutilizan como estrés.

### Prueba de aceptación

No hay bloqueo, ANR, toma corrupta ni cierre incompleto. La geometría visible es
idéntica antes, durante y después de REC, y el archivo conserva esa orientación
al reproducirse y exportarse. Los descartes se reportan con su etapa exacta.

## P3 — Audio y sincronización

### Objetivo

Mantener una sola ruta audible y sincronizar PCM con video mediante un reloj
monotónico común, sin repeticiones, cortes ni deriva acumulativa.

### Estado conocido

- `[x]` `v0.32` deja terminar la lectura PCM en curso antes de cerrar
  `AudioRecord`, evitando perder la cola final.
- `[x]` `v0.32` conserva la cola de `AudioTrack` y un solo hilo de streaming en
  pausa/reanudación.
- `[~]` En pruebas de unos 31,6 s, la cola WAV terminó aproximadamente 93–95 ms
  después del video y no aparecieron advertencias del validador.
- `[ ]` Falta demostrar que no existe deriva acumulativa en tomas largas.

### Trabajo

- Verificar que PURE 10 no active simultáneamente una pista del MP4 y el WAV.
- Auditar que todos los timestamps de captura, encoder, WAV y `sync.json` sean
  monotónicos y usen la misma base de tiempo declarada.
- Medir por separado offset inicial, deriva final, huecos y discontinuidades.
- Probar reproducción lineal, búsqueda, pausa/reanudación, stepping y mute.
- Usar una señal audiovisual medible —destello con pulso o claqueta— en vez de
  evaluar sincronía únicamente de oído.
- Probar clips de 1, 5, 10 y 30 minutos a 24 y 30 FPS.

### Prueba de aceptación

No se oye audio doble, repetido ni cortado. El error audiovisual al final de una
toma prolongada es menor a un fotograma de su cadencia, sin correcciones bruscas
que introduzcan saltos audibles.

## P4 — Reproductor RAW eficiente

### Objetivo

Reproducir y recorrer RAW10 sin bloquear la interfaz, sin alterar el archivo y
sin realizar asignaciones del tamaño de varios fotogramas en cada ciclo.

### Estado conocido

- `[x]` Desde `v0.31`, `clip.raw10` permanece abierto y el desempaquetado/revelado
  principal se realiza en C++.
- `[~]` La mejora reduce mucho el atraso previo, pero aún requiere pruebas
  repetibles de latencia, memoria y cadencia.

### Trabajo

- Perfilar lectura, desempaquetado RAW10, demosaico, transformación de color,
  creación de bitmap y presentación.
- Reutilizar buffers y evitar objetos o arreglos de decenas de MB por fotograma.
- Añadir un búfer anticipado pequeño y acotado; detenerlo inmediatamente al
  buscar o salir.
- Separar reloj de reproducción, trabajo de revelado y actualización de UI.
- Al atrasarse, saltar según timestamps para conservar sincronía, sin cambiar la
  duración del clip.
- Evaluar proxy de baja resolución solo bajo demanda y fuera de grabación. Debe
  ser descartable, regenerable y nunca sustituir ni modificar el RAW.

### Prueba de aceptación

Stepping y búsqueda responden sin bloquear la UI. La reproducción es continua
cuando el dispositivo lo permite; si no alcanza la cadencia, salta cuadros de
forma temporalmente correcta y explícita. Memoria permanece acotada y el hash
del original no cambia.

## P5 — Color, exposición, tinte y HLG

### Objetivo

Obtener preview y archivo comparables en luminosidad y balance, y ofrecer color
útil sin convertir la saturación en amplificación de ruido cromático.

### Diagnóstico que debe separarse

El aspecto verdoso puede proceder de más de una etapa y no debe corregirse con
un desplazamiento arbitrario hasta medirlas:

1. ganancias de balance de blancos y ausencia de un eje verde↔magenta;
2. fase Bayer, demosaico o matrices en RAW/P010;
3. transformación entre espacio de cámara, BT.2020 y la señal HLG;
4. metadatos de primarias, transferencia y rango interpretados por el player;
5. diferencia entre preview SDR y archivo HLG;
6. ruido cromático amplificado en sombras o a ISO alto.

El control existente de ganancia post-RAW no es una saturación colorimétrica.
Elevar ganancia aumenta señal y ruido, por lo que puede hacer visibles puntos
verdes o magenta. No debe usarse internamente como sustituto de `Saturación`.

### Trabajo de medición

- Grabar carta gris y carta de color bajo iluminación estable, con exposición y
  temperatura de color registradas.
- Comparar preview y archivo con waveform, RGB parade, vectorescopio y parches
  numéricos, no solo con una captura de pantalla.
- Medir luminancia media, relaciones R/G y B/G en neutros, desviación cromática
  en sombras y clipping por canal.
- Repetir en automático y manual, al menos a ISO 100, 400 y 800 cuando la luz lo
  permita.
- Verificar matriz de color, rango, primarias BT.2020, transferencia HLG y
  metadatos HEVC con herramientas independientes.
- Comparar el frame recibido por el encoder con el frame decodificado para
  distinguir procesamiento de captura de interpretación del reproductor.

### Modelo de controles recomendado

- `GANANCIA/EXPOSICIÓN`: modifica nivel; no promete saturación.
- `SATURACIÓN`: modifica cromaticidad conservando luminancia y limita el efecto
  en sombras ruidosas.
- `TINT`: corrección de balance verde↔magenta alrededor de un cero neutro.

No recomiendo llamar `MATIZ` al último control: una rotación global de hue
cambiaría todos los colores y ocultaría el defecto sin corregir el balance de
blancos. `TINT` o `TINTE` expresa mejor el ajuste cinematográfico esperado.

El nuevo Color Boost, si se conserva como concepto de producto, debe operar en
un espacio que separe luma y croma, preservar neutros, limitar gamut y reducir
su fuerza en sombras según el ruido. Primero debe existir como transformación
validada; después se decide si merece un control visible.

### Propuesta de interfaz pendiente de aprobación

No forma parte de una implementación automática.

- Desplazar el selector `RAW / PURE 10` al contenedor inferior que agrupa la
  regleta y `AUTO`.
- Usar el espacio liberado para `TINT`, con centro cero y dirección visual
  verde↔magenta.
- Revisar antes de programar: maqueta vertical, horizontal izquierda/derecha,
  versión diestra/zurda, hitboxes, orden de foco D-pad, etiqueta y forma de
  recuperar el valor neutro.
- Si el contenedor inferior pierde legibilidad o acceso a una mano, se descarta
  la reubicación y se busca una alternativa contextual sin alterar geometría.

### Prueba de aceptación

En una escena controlada, preview y archivo presentan exposición y neutros
comparables dentro de una tolerancia documentada. El ajuste de TINT neutraliza
un sesgo verde sin rotar colores globalmente. Aumentar saturación no crea una
cantidad desproporcionada de puntos verdes/magenta ni cambia la luminancia de
forma inesperada.

## P6 — Rendimiento, temperatura, energía y 4K

### Dependencia

Este bloque permanece `[B]` hasta que P2 sea fiable en 2K y P5 permita interpretar
correctamente la salida. Habilitar 4K antes mezclaría defectos funcionales con
un aumento grande de ancho de banda y temperatura.

### Mediciones

- CPU por proceso/hilo, GPU cuando sea observable, memoria Java y nativa.
- GC, colas de imagen/encoder, velocidad de escritura y almacenamiento libre.
- temperatura de batería/sensores, estado térmico y thermal headroom.
- energía consumida, estado de carga y fotogramas perdidos por etapa.
- tiempo hasta degradación térmica, cierre o primera discontinuidad.
- tamaño y bitrate real de las tomas.

### Condiciones de prueba que deben registrarse

- Pixel 6 dentro y fuera del estuche Ghostek Covert.
- Conectado por USB/cargando y desconectado.
- Límite de carga por software al 80 %.
- Batería con más de 1.300 ciclos: condición real del dispositivo, no equivalente
  a un Pixel 6 nuevo.
- Temperatura ambiente y ubicación general; las pruebas iniciales se realizaron
  de noche en Quito.
- 24 frente a 30 FPS y automático frente a manual.

### Trabajo

- Establecer primero una línea base 2K repetible.
- Evaluar qué resoluciones 4K ofrece realmente la combinación Camera2 + RAW/P010
  + encoder del Pixel 6, sin asumir que todos los modos pueden compartirla.
- Añadir límites térmicos y cierre seguro antes de una condición crítica.
- Diseñar mensajes breves de advertencia o degradación segura sin convertir la
  interfaz principal en un panel de telemetría.

### Prueba de aceptación

4K no bloquea la app ni deja tomas aparentemente válidas pero incompletas. Si no
puede sostenerse de forma fiable en las condiciones objetivo, se presenta como
modo limitado con restricciones concretas o no se publica.

## P7 — Calidad automatizada y lanzamiento

### Estado conocido

- `[x]` Existen pruebas del conversor y validadores de tomas.
- `[x]` Unit tests, build y Android Lint pasan con JDK 21 en `v0.32`.
- `[x]` `v0.31` y `v0.32` están etiquetadas localmente.
- `[~]` El APK debug de la línea base mide 4.299.480 bytes; falta medir release,
  AAB y contribución de dependencias.
- `[ ]` El repositorio local no tiene remoto configurado; una etiqueta local no
  es todavía una copia externa.

### Automatización pendiente

- Pruebas unitarias con vectores conocidos para desempaquetado RAW10, stride,
  CFA, black/white level y orientación.
- Pruebas de timestamps, huecos, duración, offset y deriva de `sync.json`.
- Validación automática de DNG/IFD y metadatos HEVC Main10 HLG.
- Casos de cierre incompleto y recuperación sin escribir sobre el original.
- Pruebas instrumentadas del ciclo abrir cámara → grabar → detener → revisar.
- Pruebas instrumentadas de orientación y estado automático/manual.
- Smoke test de controles y orden de foco D-pad para ambas manos.
- Medición de APK/AAB, símbolos nativos y dependencias por versión.

### Puerta de release

Antes de etiquetar una versión candidata:

- árbol de trabajo revisado y cambios ajenos preservados;
- unit tests, pruebas del conversor, `assembleDebug`/`assembleRelease` según el
  artefacto y Lint con JDK 21;
- instalación limpia/actualización no destructiva en Pixel 6;
- regresión manual de los tres modos, 24/30 FPS y tres orientaciones;
- verificación de galería, reproducción, búsqueda, pausa, stepping, mute y
  borrado solo sobre una toma QA confirmada;
- hashes o inventario antes/después de las tomas del usuario;
- tamaño de artefacto, rendimiento y defectos conocidos registrados;
- changelog y etiqueta Git coincidentes con la versión instalada;
- copia externa o remoto verificado antes de considerar la versión preservada.

## Registro de solicitudes y defectos

| ID | Solicitud o defecto | Prioridad | Estado | Versión/evidencia |
| --- | --- | --- | --- | --- |
| RAW-001 | RAW10 convertido no aparece como clip completo en Resolve | P1 | `[x]` | Corregido y aceptado en Resolve con `v0.31` |
| RAW-002 | Distinguir claramente FULL, RAW10 2K y PURE 10 | P1 | `[x]` | Contrato documentado; cualquier texto UI se revisa antes de cambiarlo |
| STAB-001 | PURE 10 congela la aplicación | P2 | `[!]` | Reproducción sistemática pendiente |
| STAB-002 | Algunas capturas se guardan cortadas | P2 | `[!]` | Diagnóstico por etapas pendiente |
| STAB-003 | Preview se estira al iniciar REC | P2 | `[!]` | Instrumentación de superficies pendiente |
| STAB-004 | Automático graba invertido o recortado | P2 | `[!]` | Matriz orientación × auto/manual pendiente |
| STAB-005 | Recuperación segura tras fallo | P2 | `[ ]` | Diseño/ensayo pendiente |
| AV-001 | Audio final truncado | P3 | `[x]` | Corregido y medido en `v0.32` |
| AV-002 | Audio doble o cortado durante reproducción | P3 | `[~]` | Ruta/hilo corregidos en `v0.32`; falta duración larga |
| AV-003 | Deriva audiovisual progresiva | P3 | `[ ]` | Pruebas 1/5/10/30 min pendientes |
| PLAY-001 | Visor RAW muy lento | P4 | `[~]` | Revelado nativo y archivo persistente en `v0.31` |
| COL-001 | Sesgo verdoso y ausencia de control de tinte | P5 | `[!]` | Medición de neutros pendiente |
| COL-002 | PURE 10 original se percibe desaturado | P5 | `[ ]` | Separar captura, HLG y visualización |
| COL-003 | Color Boost produce ruido/puntos verdes y magenta | P5 | `[!]` | Ganancia no equivale a saturación; rediseño pendiente |
| COL-004 | Archivo PURE 10 se ve más oscuro que el preview | P5 | `[!]` | Comparación SDR/HLG objetiva pendiente |
| UI-001 | Reubicar selector de modo y añadir TINT | P5 | `[B]` | Pendiente de diagnóstico y aprobación de maqueta |
| PERF-001 | Descarte de captura observado a 30 FPS | P2/P6 | `[ ]` | Un descarte en prueba de ~31,6 s |
| PERF-002 | Evaluar operación 4K | P6 | `[B]` | Espera estabilidad 2K |
| QA-001 | Matriz automatizada e instrumentada | P7 | `[~]` | Infraestructura parcial |
| REL-001 | Medir APK/AAB y dependencias | P7 | `[~]` | APK debug base: 4.299.480 bytes |
| REL-002 | Copia externa de versiones | P7 | `[ ]` | No hay remoto Git configurado |

## Orden recomendado de ejecución

1. **Bloque B — P2:** aislar congelamiento, archivo cortado, estiramiento y
   transformación automática. Ningún trabajo de 4K antes de cerrar este bloque.
2. **Bloque C — P3:** ejecutar sincronización prolongada y cerrar la ruta única
   de audio.
3. **Bloque D — P5:** medir color/HLG, diseñar TINT y saturación; presentar
   maqueta antes de tocar geometría.
4. **Bloque E — P4:** completar perfilado, prebuffer y política de atraso del
   reproductor RAW.
5. **Bloque F — P6:** establecer línea térmica 2K y solo entonces estudiar 4K.
6. **P7 es continuo:** añadir pruebas y evidencia dentro de cada bloque, no al
   final del proyecto.

P1 ya está cerrado. Su conversión corta y validación en Resolve forman parte de
la regresión de release, pero no retrasan el inicio de P2.

P4 puede adelantarse si no toca la ruta de captura, pero nunca debe competir en
la misma versión con una corrección crítica de P2 sin una regresión completa.

## Decisiones que requieren aprobación del usuario

- Cualquier movimiento del selector `RAW / PURE 10`.
- Nombre, escala, rango y posición del control `TINT/TINTE`.
- Cambio de etiquetas visibles como `GANANCIA`, `COLOR BOOST` o `SATURACIÓN`.
- Alteración del orden D-pad, hitboxes, geometría o distribución diestra/zurda.
- Publicar 4K como normal, experimental o limitado.
- Conservar metraje QA después de terminar una prueba.

## Historial de la línea base

- `v0.31`: compatibilidad del conversor DNG endurecida y reproductor RAW movido
  a una ruta nativa con archivo persistente.
- `v0.32`: cierre de audio sin perder la cola PCM y reproducción con una única
  ruta de streaming continua.

Este historial no sustituye al changelog por versión. Resume únicamente las
correcciones que sirven como punto de partida para este plan.
