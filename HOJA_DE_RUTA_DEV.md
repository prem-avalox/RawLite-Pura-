# Hoja de Ruta de Desarrollo: RawLite (4 Sprints Priorizados)

**Proyecto:** RawLite (Cámara cinematográfica manual para Android)  
**Dispositivo de Referencia:** Google Pixel 6 (`oriole`), sensor Samsung GN1 (50 MP, f/1.85, focal principal 6.8 mm)  
**Documentos Base:** [`AGENTS.md`](AGENTS.md), [`plan_de_implementacion.md`](plan_de_implementacion.md), [`design/investigacion_de_ui.md`](design/investigacion_de_ui.md)  
**Director del Proyecto / Product Owner:** Martin Dávalos  

---

## Directiva de Trabajo Multi-Agente
Todo desarrollo sigue el protocolo de tres entidades definido en [`AGENTS.md`](AGENTS.md):
1. **Lead Architect / Consultor:** Define viabilidad física, óptica, matemática y asigna tareas.
2. **Core Developer:** Implementa cambios en ramas dedicadas (`feat/...`), garantizando cero regresiones y compilación limpia con Gradle.
3. **QA Reviewer:** Audita buffers, latencias (< 2 ms en C++), fugas de memoria, concurrencia en Camera2 y comportamiento táctil/hardware antes de entregar al PO.

---

## Especificación Técnica de los 4 Sprints

### Sprint 1: Ergonomía Táctil, Bloqueo de Eje en D-Pad y Disparo por Botones Físicos de Volumen
* **Objetivo:** Garantizar operación física y monomanual infalible durante el rodaje en mano.
* **Componentes Afectados:**
  - `MainActivity.kt`: Intercepción de eventos de hardware (`onKeyDown`, `onKeyUp`) para `KEYCODE_VOLUME_DOWN` y `KEYCODE_VOLUME_UP`. Disparo/detención de grabación sin disparos repetidos por `repeatCount == 0`, consumiendo el evento para no alterar el volumen multimedia del sistema operativo.
  - `CameraChromeView.kt`:
    * **Bloqueo estricto de eje en el D-pad:** Prevención de saltos diagonales o desvíos accidentales de parámetro durante el arrastre vertical/horizontal. Zona muerta calibrada (`DPAD_CENTER_DEAD_ZONE`) y resolución precisa de dirección sin interferencia cruzada.
    * **Margen de seguridad (Gutter buffer):** Espaciado ergonómico mínimo entre el D-pad y el botón de grabación (`insideRecordButton`) para evitar iniciar o detener tomas por toques marginales al ajustar parámetros con el pulgar.

### Sprint 2: Máquina de Estados de Superposiciones (Exclusividad Mutua, D-Pad y Anclaje 700 ms) — [COMPLETADO Y AUDITADO]
* **Objetivo:** Unificar y disciplinar las herramientas técnicas de exposición y enfoque en pantalla (PURA UI) con activación unificada por D-pad y Rueda.
* **Componentes Afectados y Resultados:**
  - `CameraChromeView.kt` / `MainActivity.kt` / `ActiveOverlay.kt`:
    * **Activación por D-Pad:** Callbacks `onPrecisionStarted` / `onPrecisionReleased` integrados estrictamente en el eje de ajuste de valor (`!action.parameterAxis`), con inmunidad a falsos disparos durante la navegación entre parámetros.
    * **Exclusividad mutua:** Solo una herramienta analítica (Zebra, False Color, Peaking) activa a la vez en GPU via `TechnicalShaderManager`.
    * **Anclaje táctil (700 ms):** Toques cortos (< 700 ms) seleccionan parámetros inmediatamente sin retardo; mantener pulsado $\ge 700\text{ ms}$ ancla la herramienta en modo *always-on* con háptica `selectorLongPressHaptic()`.
    * **Conmutación inteligente con restauración:** Si False Color o Zebras están fijas y se ajusta el foco (vía rueda o D-pad), conmuta momentáneamente a Focus Peaking y restaura la herramienta previa fija al soltar.
    * **Persistencia en SharedPreferences:** `PREF_ACTIVE_OVERLAY` y `PREF_ACTIVE_OVERLAY_ALWAYS_ON` serializados y restaurados al inicio.
* **Auditoría QA:** Dictamen **APROBADO** (PASS en QG-2.1 a QG-2.7). Build limpio e instalado en Pixel 6 (`1C061FDF60063K`).

### Sprint 3: Desacoplar Histograma de Kelvin y Modo Clean Feed Real — [COMPLETADO Y AUDITADO]
* **Objetivo:** Limpieza del Head-Up Display (HUD) eliminando saturación visual innecesaria e independizando el histograma técnico.
* **Componentes Afectados y Resultados:**
  - `CameraChromeView.kt`:
    * **Clean Feed Real:** Supresión total de D-pad, Push Auto, selectores, reglas y labels cuando `chromeReveal <= 0.001f`. Solo preview, botón de visibilidad y botón de disparo quedan visibles.
    * **Disparo Operativo y Aislamiento:** El botón de grabación (`TARGET_RECORD`) se evalúa antes de la guarda de Clean Feed, permitiendo grabar/detener con la pantalla despejada. Cero toques fantasma en el resto de la pantalla.
  - `MainActivity.kt` / `TechnicalOverlayView.kt`:
    * **Histograma RAW en Tiempo Real:** Guarda corregida a `if (!histogramEnabled) return` en `maybeUpdateRawHistogram`, computando bins RAW10 en segundo plano.
    * **Geometría y Rendimiento:** Panel compacto de $120\text{ dp} \times 60\text{ dp}$ en esquina superior. Reutilización de `histogramBounds` y `histogramPath` para cero alocaciones dinámicas de memoria en `onDraw`.
    * **Persistencia:** Switch `HISTOGRAMA GLOBAL` en Ajustes serializado y restaurado en `SharedPreferences`.
* **Auditoría QA:** Dictamen **APROBADO** (PASS en QG-3.1 a QG-3.7).


### Sprint 4: Arquitectura de Visor Técnico Neutro a 30 Hz Desacoplado en GPU
* **Objetivo:** Monitorización a 30 FPS en tiempo real con cero sobrecarga en el hilo principal y bypass de filtros destructivos.
* **Componentes Afectados:**
  - `TechnicalShaderManager.kt`: Shaders AGSL nativos acelerados en GPU (Android 13+ / Tiramisu) para Peaking, Zebras y False Color calibrado.
  - Desacoplamiento de renderizado para evitar caídas de cuadros en el hilo de Camera2 y mantener la latencia de captura por debajo de los 2 ms.

---

## Hito de Pipeline Óptico: Curación de Aberración Cromática y Rendimiento HLG 10 (Completado)
* **Problema Físico:** En aperturas luminosas ($f/1.85$), la aberración cromática longitudinal (LoCA) y el subpixel centering en Bayer CFA dispersan fotones rojos y azules hacia la zona oscura de las luces altas. La simple desaturación deja un "bleed" (halo difuso de luma elevada).
* **Solución Implementada (`rawlite_writer.cpp`):**
  - **Pass 1c (Green-Guided Optical Despilling):** Identifica el hombro oscuro de la transición de altas luces (`g_max >= 1200`, `gf < edge_midpoint`). Restringe los deltas $(R-G)$ y $(B-G)$ estrictamente a los vecinos fotométricos del lado oscuro, cortando de raíz el sangrado óptico de $R$ y $B$ sin desaturar objetos coloreados normales.
  - **Pass 2 (Retención de Frecuencia Verde Nativa):** Elimina el aspecto borroso/miope de la interpolación bilineal inyectando la diferencia espacial $(G_0 - G_1)$ en los sitios nativos de la celda Bayer.
  - **Optimización de Rendimiento:** Eliminación del Pass 3 unsharp mask iterativo (que consumía 53.6 ms y botaba 12 cuadros). Streaming directo a `y_plane` en un solo pase. Frame time reducido a $< 18\text{ ms}$ (sin pérdidas a 24 y 30 FPS).
* **Estado de Verificación:** Compilación limpia con Clang C++20 / NDK, 11 tests Python pasando, desplegado y verificado inalámbricamente en Google Pixel 6 (`192.168.200.3:35983`).
