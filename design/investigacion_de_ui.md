# Investigación de UI y Rediseño de Mesa de Luz (PURA)
**Proyecto:** RawLite  
**Fecha:** 6 de octubre de 2026  
**Documento:** Investigación de UI y Arquitectura de Interacción  
**Alcance:** Crítica de Usabilidad, Jerarquía de Dominio, Guías de Composición y Sistema de Escala Proporcional  

---

## 1. Resumen Ejecutivo y Diagnóstico

La propuesta de rediseño para **Mesa de Luz** aborda problemas de inconsistencia jerárquica, sobrecarga de lenguaje no operativo, ambigüedades en la selección de formatos y falta de contraste en acciones críticas.

Al ser RawLite una herramienta de captura técnica profesional, su interfaz debe seguir los principios de los monitores de producción y sistemas de cámara de cine (ARRI, RED, Blackmagic):
1. **Claridad sobre abreviaciones crípticas:** Las acciones deben ser legibles de inmediato.
2. **Dominio estricto:** Los filtros y metadatos deben pertenecer a la cinematografía, no al sistema operativo de escritorio.
3. **Flujo integrado de tomas:** La reproducción es un estado de inspección de la galería, no una aplicación independiente.
4. **Economía de lenguaje:** Cero textos editoriales, decorativos o "motivacionales". Cada píxel de texto debe ser operativo o informativo.
5. **Herramientas de encuadre modernas:** Guías de área segura (*Safe Zones*) para plataformas verticales (TikTok, Reels, Shorts) con terminología técnica correcta (retículas vs. cuadrículas).
6. **Unificación conceptual de RAW:** Separar el formato físico del contenedor y resolución para evitar la confusión CinemaDNG vs. RAW10.
7. **Affordance y jerarquía de confirmación:** Botones con presencia clara, delimitación espacial y contraste alto.

---

## 2. Análisis Detallado de los Puntos Propuestos

### Punto 1: Etiquetado Explícito (`SELECCIONAR` vs. `SEL`)
* **Problema detectado:** El botón de cabecera utiliza la abreviatura `SEL`. En pantallas móviles horizontales hay espacio horizontal suficiente. Las abreviaturas truncadas añaden carga cognitiva y restan profesionalidad al entorno.
* **Diagnóstico:** `SELECCIONAR` comunica claramente el estado y la acción.
* **Propuesta:**
  - Estado inactivo: `SELECCIONAR` (blanco película `#F7F7F7`, caja alta, tracking técnico `0.08em`).
  - Estado activo (en selección por lotes): `CANCELAR` o `LISTO` (amarillo señal `#FFC100`).

---

### Punto 2: Filtros de Galería Orientados al Dominio Cinematográfico
* **Problema detectado:** Los menús de ordenación/filtrado heredan conceptos de explorador de archivos de escritorio (Finder/Windows Explorer): *"Aplicación"*, *"Clase"*, *"Fecha de modificación"*.
  - En RawLite, el 100% de los archivos pertenecen a la misma aplicación.
  - Las tomas capturadas son **inmutables** por definición; la *"Fecha de modificación"* es un concepto irrelevante y redundante respecto a la *"Fecha de captura/creación"*.
* **Propuesta de Filtros Técnicos de Dominio:**
  1. **Formato / Códec:** `TODOS` | `HLG 10` | `RAW 10` | `CINEMADNG`.
  2. **Resolución:** `FULL (4080×3072)` | `2K (2032×1536)` | `FHD (1920×1080)`.
  3. **Cadencia / FPS:** `24 FPS` | `30 FPS` | `15 FPS`.
  4. **Lente / Cámara:** `Principal (6.8 mm)` | `Ultra Gran Angular`.
  5. **Duración / Tamaño:** Filtro por metraje largo vs. tomas cortas.
  6. **Estado de audio:** `Con audio externo` | `Con audio interno` | `Mute`.
  7. **Orden:** `Más recientes primero` (predeterminado) | `Más antiguas primero`.

---

### Punto 3: Arquitectura de Navegación — Integración de "Reproductor" en "Tomas"
* **Problema detectado:** "REPRODUCTOR" aparece en la consola superior como un módulo principal independiente al mismo nivel que "TOMAS" y "AJUSTES".
* **Diagnóstico:** En un flujo de cámara, el usuario no "entra al reproductor en vacío". El reproductor es un **estado de inspección profunda de una toma específica**.
* **Propuesta:**
  - Eliminar "REPRODUCTOR" de la barra principal de navegación.
  - La barra principal se simplifica a dos destinos operativos:
    ```
    [ CÁMARA / VISOR ] <───> [ TOMAS (Mesa de luz) ] <───> [ AJUSTES ]
    ```
  - Al pulsar sobre una toma en la mesa de luz, la interfaz transiciona fluidamente al **Visor de Inspección de Toma** (transporte, histograma, metadatos `clip.json` y herramientas de exportación). El botón de retorno regresa directamente a la cuadrícula de Tomas.

---

### Punto 4: Eliminación de Copia Editorial / "Motivacional"
* **Problema detectado:** Cabeceras y paneles muestran frases como *"La imagen primero. Las ayudas, sólo cuando sirven"* o explicaciones redundantes como *"Estos ajustes modifican la operación y el visor. Nunca alteran el archivo capturado"*.
* **Diagnóstico:** En software técnico de precisión (Blackmagic OS, RED OS), los textos de autojustificación o eslóganes reducen la densidad de información y restan seriedad.
* **Propuesta:**
  - Sustituir frases editoriales por descripciones funcionales concisas de una sola línea (máximo 8–10 palabras) o eliminarlas por completo cuando el control sea autoexplicativo:
    - *Antes:* *"La imagen primero. Las ayudas, sólo cuando sirven."*
    - *Después:* `CONFIGURACIÓN DE VISOR Y ASISTENCIAS ÓPTICAS` (o simplemente el encabezado de categoría `VISOR`).

---

### Punto 5: Retículas, Cuadrículas y Safe Zones para Redes Verticales
* **Problema detectado:**
  1. El distintivo *"NO SE GRABA"* es innecesario. Un operador de cámara RAW sabe por defecto que las asistencias de pantalla no se queman en el negativo.
  2. La opción *"CENTRO"* se titula erróneamente como cuadrícula cuando se trata de una **retícula de punto central / cruz óptica (*center crosshair*)**.
* **Nueva funcionalidad: Superposición de Safe Zones para Redes Sociales (9:16):**
  Al capturar vídeo horizontal (16:9 o 4:3), es vital encuadrar pensando en la distribución vertical en plataformas como **Instagram Reels, TikTok y YouTube Shorts**.
* **Especificaciones recomendadas de Safe Zones (Canvas vertical 1080×1920 / Proporción 9:16 centrada):**
  - **Margen Superior (Top Inset):** `15%` (aprox. 288 px). Zona reservada para botones de navegación, pestañas "Siguiendo/Para ti" y barras de estado.
  - **Margen Inferior (Bottom Inset):** `20%–22%` (aprox. 384–422 px). Zona crítica donde las aplicaciones superponen nombre de usuario, descripción, hashtags, selector de audio y botón de acción.
  - **Margen Lateral Derecho (Right Inset):** `12%–15%` (aprox. 130–160 px). Columna vertical de interacción (avatar, botón de like, comentarios, guardar y compartir).
  - **Zona Segura Central Cuadrada (1:1):** Caja centrada de `1080×1080` (utilizada por Instagram para el recorte en cuadrícula del feed).
* **Propuesta de controles:**
  - Separar en dos selectores independientes:
    1. **Guía de Composición:** `NINGUNA` | `TERCIOS (3×3)` | `RETÍCULA DE CENTRO (+)` | `PANORÁMICO (2.39:1)`.
    2. **Área Segura Social (Toggle superpuesto):** `OFF` | `VERTICAL 9:16 (Reels / TikTok / Shorts)` | `FEED 1:1`.

---

### Punto 6: Relación Técnica entre CinemaDNG y RAW 10 (Cámara vs. Galería)
* **Verificación técnica en el código de RawLite:**
  Al auditar `MainActivity.kt`, `CameraCatalog.kt` y `Raw10CinemaDngConverter.kt`, la distinción en la arquitectura es **completamente exacta**:
  1. **`CinemaDNG FULL` (Grabación en 4K / Sensor Completo):**
     - Resolución: Sensor completo **4080×3072 (12.5 MP)** en el Pixel 6.
     - Captura Camera2: `ImageFormat.RAW_SENSOR` (16 bits por píxel).
     - Almacenamiento: Se guarda directamente como una **secuencia de archivos individuales `.dng`** (`frame_000000.dng`, `frame_000001.dng`, ...) en una subcarpeta `CINEMADNG_YYYYMMDD_HHMMSS/dng/`.
  2. **`RAW 10` (Grabación en 2K):**
     - Resolución: **2K (2032×1536)**.
     - Captura Camera2: `ImageFormat.RAW10` (empaquetado MIPI CSI-2, 10 bits por píxel).
     - Almacenamiento: Se guarda en un **único archivo binario contenedor continuo `clip.raw10c`** mediante el escritor nativo C++ (`rawlite_writer.cpp`) con compresión sin pérdidas para sostener la cadencia sin saturar el almacenamiento.
  3. **Conversión en la app (`Raw10CinemaDngConverter.kt`):**
     - La app incluye la acción nativa `CONVERTIR A CINEMADNG`, que toma una toma RAW10 2K (`clip.raw10c`), descomprime y desempaqueta los fotogramas, y genera una **nueva toma CinemaDNG 2K independiente** (`CINEMADNG_.../dng/frame_XXXXXX.dng`) lista para importar en DaVinci Resolve junto con el audio WAV alineado.
* **Criterio de Diseño UI (Distinción entre Galería y Cámara):**
  - **En la Galería / Mesa de Luz (Filtros):** `RAW 10` y `CinemaDNG` **SÍ deben ser filtros separados**.
    * *Razón:* Tienen estados y acciones operativas distintas. Una toma `RAW 10` es un contenedor propietario que permite la acción contextual `CONVERTIR A CINEMADNG`. En cambio, una toma `CinemaDNG` (sea nativa 4K o convertida 2K) ya es una secuencia terminada para postproducción que no admite reconversión.
  - **En el Selector de Captura de la Cámara:**
    * Mantenerlos como formatos paralelos a `HLG 10` en la rueda 1 causaba confusión conceptual.
    * La Rueda 1 debe elegir el Pipeline (`RAW` vs. `HLG 10`).
    * Al seleccionar `RAW`, la Rueda 2 ofrece las opciones físicas reales:
      - `FULL 4K (4080×3072, Secuencia CinemaDNG)`
      - `2K (2032×1536, Contenedor RAW10C)`

---

### Punto 7: Botones de Confirmación y Cancelación Explícitos
* **Problema detectado:** En diálogos y menús de confirmación, `CANCELAR` es solo texto gris atenuado y `USAR CONFIGURACIÓN` es texto amarillo con una línea superior delgada.
* **Diagnóstico:** Carecen de *affordance* (delimitación física). En condiciones de luz solar o uso rápido con una mano, no se distinguen como zonas táctiles activas y se confunden con etiquetas pasivas.
* **Propuesta:**
  - **CANCELAR (Secundario):** Botón con contorno sutil (`border: 1px solid rgba(247, 247, 247, 0.25)`), fondo transparente y texto en blanco película (`#F7F7F7`).
  - **CONFIRMAR / APLICAR (Primario):** Botón con masa visual sólida o fondo técnico diferenciado (`background: #FFC100`, texto negro óptico `#000000`, peso semibold 600) o contorno de alta intensidad con barra de estado activa.
  - Altura táctil mínima garantizada: **48 dp** (estándar ergonómico táctil).

---

## 3. Sistema de Escala Proporcional y Matemático (Design Tokens)

La propuesta del usuario de aplicar múltiplos o proporciones armónicas es fundamental para dotar al sistema de coherencia visual rigurosa.

### A. La Decisión Técnica: Cuadrícula Base de 8 dp con Escala Modular Aumentada
Aunque la proporción áurea pura ($\phi \approx 1.618$) es estéticamente atractiva en papel o diseño estático, en pantallas digitales con densidades variables (mdpi, hdpi, xhdpi, xxhdpi) genera fracciones decimales que provocan **renderizado subpíxel borroso** si los valores no son enteros.

La solución más refinada para software técnico de alta gama consiste en combinar:
1. **Un sistema base de 8 dp / 4 dp** para toda la geometría espacial (márgenes, paddings, alturas de botones y contenedores).
2. **Una escala modular tipográfica basada en la Cuarta Justa (1.333) o Tercera Mayor (1.25)**, garantizando que cada salto tipográfico sea armónico y encaje perfectamente en múltiplos enteros de la rejilla.

### B. Tabla de Tokens Espaciales (Múltiplos de 4 / 8)

| Token | Valor (dp / px) | Proporción | Uso Principal |
| :--- | :--- | :--- | :--- |
| `space-1` | **4 dp** | Base / 2 | Microespaciado entre icono y texto corto |
| `space-2` | **8 dp** | **Base (1x)** | Padding interno de chips, etiquetas y badges |
| `space-3` | **12 dp** | 1.5x | Separación entre elementos compactos de lista |
| `space-4` | **16 dp** | **2x (Módulo estándar)** | Padding lateral de botones, márgenes de tarjetas |
| `space-5` | **24 dp** | 3x | Separación entre grupos de controles |
| `space-6` | **32 dp** | 4x | Separación entre secciones mayores |
| `space-7` | **48 dp** | 6x | **Target táctil mínimo** (altura de botones principales) |
| `space-8` | **64 dp** | 8x | Altura de barras de navegación y transporte |

### C. Escala Modular Tipográfica Armónica (Open Sans / Tabular)

| Nivel | Tamaño (sp) | Interlineado (dp) | Peso | Tracking | Uso |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `caption-micro` | **10 sp** | 12 dp | 400 | +0.06em | Metadatos secundarios, timecode micro |
| `body-small` | **12 sp** | 16 dp | 400 | +0.04em | Descripciones de ajustes, estados secundarios |
| `body-regular` | **14 sp** | 20 dp | 400 | 0.00em | Lectura general, opciones de menú |
| `action-button` | **15 sp** | 20 dp | 600 | +0.08em | Botones de acción, selección y confirmación |
| `title-section` | **18 sp** | 24 dp | 600 | +0.04em | Encabezados de paneles y ruedas |
| `metric-display`| **24 sp** | 32 dp | 400 (Tabular) | -0.02em | Valores numéricos primarios (ISO, Shutter, FPS) |
| `hero-metric`   | **36 sp** | 44 dp | 300 (Tabular) | -0.04em | Contador de tiempo en grabación |

---

## 4. Próximos Pasos Recomendados

1. **Mantener el código congelado** mientras se valida este documento de investigación de UI.
2. Tras la validación, prototipar los cambios en `design/rawlite-ui-language/`:
   - Cambiar `SEL` por `SELECCIONAR`.
   - Reestructurar el menú de Tomas absorbiendo el Reproductor como visor de detalle.
   - Implementar el toggle de Safe Zone 9:16 con recortes semitransparentes.
   - Refactorizar las ruedas de formato para agrupar CinemaDNG y RAW10 bajo la raíz `RAW`.
   - Aplicar la escala de botones con botón primario amarillo delimitado.
