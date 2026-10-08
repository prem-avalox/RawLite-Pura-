# Plan de Implementación y Arquitectura: RawLite

**Proyecto:** RawLite (Cámara RAW & HLG10 para Android)  
**Fecha:** 8 de octubre de 2026  
**Documento maestro:** Hoja de Ruta, Diagnósticos Técnicos y Especificaciones de UI/UX  
**Repositorio local:** `/Users/martin/RawLite`  

---

## 1. Estado del Proyecto y Respaldos Existentes

Antes de continuar con nuevas modificaciones, el proyecto cuenta con los siguientes puntos de seguridad verificados:
1. **Respaldos de Código y UI:**
   - Copia física completa del proyecto: `/Users/martin/Documents/RawLite_Backup_20261005/`
   - Copia física del prototipo de diseño: `/Users/martin/RawLite/design/rawlite-ui-language-backup-20261006/` y `/Users/martin/Documents/RawLite_UI_Backup_20261006/`
2. **Ramas Git:**
   - `backup-pre-fix-20261005`: Estado funcional inicial congelado.
   - `fix/hlg10-and-stability`: Corrección de debayering 2D bilineal, cierre de ejecutores en `MainActivity`, desacoplamiento de bloqueos en audio y eliminación de `audio.wav` duplicado (versión `0.34.4-alpha.78`).
   - `feature/ui-mesa-de-luz-redesign`: Rama dedicada para las modificaciones del prototipo de diseño.

---

## 2. Problemas Críticos Actuales y Soluciones

### A. Problema Crítico 1: Aberración Cromática en Grabación HLG 10

#### 1. Diagnóstico Técnico
* **Desfase físico del sensor Bayer (GBRG):**  
  En el sensor Samsung GN1 del Pixel 6, los fotodiodos no comparten el mismo centro geométrico dentro de la celda 2×2:
  - $G_0$ en $(0, 0)$ y $G_1$ en $(1, 1)$ (su centro medio ya coincide con $(0.5, 0.5)$).
  - Azul ($B$) en $(1, 0)$ (esquina superior derecha).
  - Rojo ($R$) en $(0, 1)$ (esquina inferior izquierda).  
  La separación física diagonal entre $R$ y $B$ es de $\sqrt{2} \approx 1.41$ píxeles.
* **Bypass del ISP de Google:**  
  RawLite accede directamente al flujo `RAW10` del HAL de Camera2, evitando el procesador de imagen comercial de Google. Por tanto, no se aplica la corrección automática por hardware de aberración cromática (*CAC*), dejando visible tanto el desfase de la cuadrícula de color como la dispersión refractiva física de la lente de 6.8 mm en bordes de alto contraste.

#### 2. Solución Aprobada
* **Para flujos RAW (CinemaDNG / RAW10):**  
  Se mantiene el flujo de datos crudos intacto para corregir con precisión de 32 bits flotantes en DaVinci Resolve (*Camera RAW $\rightarrow$ Chromatic Aberration Correction*).
* **Para HLG 10 (Vídeo directo en el móvil):**  
  **Implementar el Método B (supresión de rebordes por diferencia de color guiada) en C++:**  
  - En [`app/src/main/cpp/rawlite_writer.cpp`](file:///Users/martin/RawLite/app/src/main/cpp/rawlite_writer.cpp) (`raw10ToP010Hlg`), procesar las señales de diferencia de color respecto al verde de referencia:
    $$\Delta_{R-G} = R - G \quad \text{y} \quad \Delta_{B-G} = B - G$$
  - Como el canal verde ($G$) posee el doble de densidad de muestreo y define la luminancia principal (~68%), se utiliza como guía de gradiente para detectar transiciones bruscas.
  - El filtro evalúa el gradiente local; si detecta una variación cromática espuria en un borde donde el verde se mantiene homogéneo, atenúa adaptativamente el exceso de saturación marginal.
  - **Escalabilidad:** Al ser un filtro adaptativo basado puramente en gradientes espaciales y diferencias relativas de color, **funcionará de manera idéntica y automática en un Pixel 6, en un Pixel 7 o en cualquier smartphone Android que se agregue en el futuro**, sin depender de calibraciones fijas por lente.

#### 3. Estado de la Implementación (Rama `feat/hlg10-chromatic-filter`)
* **Implementado (8 de octubre de 2026):** Filtro adaptativo de supresión de rebordes guiado por verde (`Pass 1c`) incorporado en [`app/src/main/cpp/rawlite_writer.cpp`](file:///Users/martin/RawLite/app/src/main/cpp/rawlite_writer.cpp).
* **Validación de Compilación:** Compilado exitosamente sin errores con el compilador oficial Clang++ de Android NDK 27 (`aarch64-linux-android31`, C++20).
* **Próximo Paso:** Validación en hardware físico (Pixel 6) evaluando tomas en contraluz en DaVinci Resolve.

---

### B. Problema Crítico 2: Gestión de Audio Duplicado en Tomas HLG 10

#### 1. Diagnóstico Técnico
Durante la grabación de HLG 10, la app registra en paralelo vídeo HEVC por hardware (`pure10.mp4`) y audio PCM sin compresión (`audio.wav`). Al pulsar detener, `Pure10AudioMuxer` genera `pure10_final.mp4` con audio AAC a 192 kbps. Anteriormente, el archivo `audio.wav` se conservaba en disco, ocupando entre 15 y 25 MB innecesarios por toma.

#### 2. Estado de la Corrección (Implementada en v0.34.4-alpha.78)
* `Pure10AudioMuxer.kt` ahora elimina automáticamente `audio.wav` una vez verificado que `pure10_final.mp4` tiene peso válido y audio muxed.
* `Pure10Clip.kt` y `ReviewActivity.kt` fueron actualizados para reproducir directamente el audio integrado del contenedor MP4 mediante el reproductor de vídeo nativo, sin requerir `audio.wav`.

---

## 3. Diagnóstico y Especificación de Diseño: Mesa de Luz (PURA UI)

Manteniendo el **layout actual** del prototipo ([`design/rawlite-ui-language`](file:///Users/martin/RawLite/design/rawlite-ui-language)), se aplican los siguientes ajustes de arquitectura de interacción:

### 1. Etiquetado Explícito: `SELECCIONAR`
* Se reemplaza la abreviatura críptica `SEL` por **`SELECCIONAR`** en la barra superior de Tomas.
* Se garantiza un área táctil mínima de **48 dp** con tipografía semibold de 14–16 sp y tracking técnico `0.08em`.

### 2. Filtros de Galería Orientados al Dominio Cinematográfico
* Se eliminan los filtros heredados de explorador de archivos de escritorio (*"Aplicación"*, *"Clase"*, *"Fecha de modificación"*). Las tomas RAW son inmutables por definición.
* Se establecen filtros de cámara interactivos en el menú popover:
  - **Formato:** `TODOS` | `HLG 10` | `RAW 10` | `CINEMADNG`.
  - **Cadencia:** `TODAS` | `24 FPS` | `30 FPS`.
  - **Lente:** `TODAS` | `Principal 6.8 mm` | `Ultra Gran Angular`.
  - **Orden:** `Más recientes primero` | `Más antiguas primero`.

### 3. Integración de "Reproductor" dentro de "Tomas"
* Se elimina la pestaña "REPRODUCTOR" de la barra principal de navegación superior.
* La navegación principal se reduce a tres accesos directos: **`TOMAS`**, **`AJUSTES`** y **`LENGUAJE`**.
* El reproductor funciona como el **visor de inspección profunda de una toma**: al tocar una miniatura en la mesa de luz, se abre la toma con controles de transporte, histograma y herramientas; el botón de retroceso (`#player-back`) retorna de inmediato a la mesa de luz.

### 4. Supresión de Copia Editorial o "Motivacional"
* Se eliminan frases de autojustificación publicitaria en los ajustes (*"La imagen primero. Las ayudas, sólo cuando sirven"*).
* Se sustituyen por títulos sobrios y descriptivos propios de un monitor de producción:
  - Categoría General: **`RETÍCULAS Y ASISTENCIAS DE ENCUADRE`**.
  - Categoría Proyecto: **`PROYECTO / CAPTURA`** (*"Configuración del pipeline de captura, contenedor y perfil de hardware verificado"*).

### 5. Visor de Composición Óptica y Zonas Seguras Superpuestas
* **Eliminación de la retícula de cruz central aislada.**
* **Incorporación de la Cuadrícula de Centro:**  
  Ejes continuos que cruzan todo el encuadre (horizontal al 50% y vertical al 50%) dividiendo el fotograma en **cuatro cuadrantes exactos** para encuadres simétricos.
* **Incorporación de la clásica Espiral Áurea de Fibonacci:**  
  Subdivisión geométrica mediante rectángulos áureos inscritos según la proporción $\Phi$ (38.2% y 61.8%) con el arco de espiral logarítmica continuo que guía la mirada hacia el punto focal.
* **Incorporación de la Cuadrícula Diagonal:**  
  Líneas armónicas desde esquinas opuestas del sensor y vértices para encuadres de acción y líneas de fuga.
* **Botón `INVERTIR ⇄`:**  
  Ubicado inmediatamente sobre la Espiral Áurea y la Diagonal. Permite voltear horizontalmente la orientación (`transform: scaleX(-1)`) para acomodar la composición a la dirección de la mirada del sujeto (*lead room*) o al movimiento de la escena sin rotar la cámara.
* **Zonas Seguras Superpuestas y Sumables (Estilo DaVinci Resolve):**  
  A diferencia de las cuadrículas (que son mutuamente excluyentes), las zonas seguras funcionan como una lista aditiva multi-toggle:
  - `[✓] 9:16 REELS / TIKTOK / SHORTS`: Caja vertical centrada con guías de interfaz (Margen superior 15% para barras/pestañas, margen inferior 20% para descripción/audio y margen derecho 15% para botones de interacción).
  - `[✓] 1:1 FEED INSTAGRAM`: Recorte central cuadrado.
  - `[✓] 4:5 RETRATO`: Caja vertical de retrato para publicaciones de Instagram.
  - `[✓] 90% ACTION SAFE`: Límite de seguridad de acción broadcast.
  - `[✓] 80% TITLE SAFE`: Límite de seguridad de subtítulos y tipografía.

### 6. Relación Técnica entre CinemaDNG y RAW 10
* **En el Selector de Captura de Cámara:**  
  Se unifica el pipeline. La Rueda 1 elige el modo (`RAW` vs. `HLG 10`). Si se elige `RAW`, la Rueda 2 define la resolución y el contenedor físico:
  - `FULL 4K (4080×3072, Secuencia CinemaDNG 12.5 MP)`
  - `2K (2032×1536, Contenedor RAW10C comprimido 3.1 MP)`
* **En la Galería:**  
  Se mantienen como filtros independientes (`RAW 10` y `CinemaDNG`), ya que una toma `RAW 10` dispone de la acción `CONVERTIR A CINEMADNG`, mientras que una toma `CinemaDNG` ya es un entregable final para DaVinci Resolve.

### 7. Affordance y Masa Visual en Botones
* Botón de confirmación primario (`USAR CONFIGURACIÓN`): Masa sólida amarilla (`#FFC100`), texto negro de alto contraste, tipografía de 16 sp bold y altura táctil de **48 dp**.
* Botón secundario (`CANCELAR`): Fondo sutil con contorno delimitado (`border: 1px solid rgba(247, 247, 247, 0.28)`), texto blanco película y altura de **48 dp**.

---

## 4. Sistema Proporcional y Tipográfico Definitivo

### A. Escala Modular: Tercera Mayor (1.25 / 5:4) Redondeada a Par
Para garantizar que todos los elementos encajen con precisión en el rasterizador de pantallas móviles y eliminar el desenfoque de medio píxel (*subpixel blur*), se adopta la siguiente escala de valores estrictamente pares:

$$\mathbf{4 \rightarrow 6 \rightarrow 8 \rightarrow 10 \rightarrow 12 \rightarrow 16 \rightarrow 20 \rightarrow 24 \rightarrow 32 \rightarrow 40 \rightarrow 48 \rightarrow 64}$$

* **Espaciados (Paddings, Margins, Gaps):**
  - `--space-xs`: 4 dp
  - `--space-sm`: 8 dp
  - `--space-md`: 12 dp
  - `--space-base`: 16 dp (Módulo estándar)
  - `--space-lg`: 24 dp
  - `--space-xl`: 32 dp
  - `--space-btn`: **48 dp** (Área táctil mínima de confirmación y selección)
  - `--space-bar`: 64 dp (Barras de herramientas)

### B. Sistema Tipográfico Dual: `Roboto` + `Roboto Mono`
**Confirmación técnica:** La adopción de **Roboto** y **Roboto Mono** es totalmente viable, compatible con el ecosistema de Google/Android y altamente recomendable para un instrumento cinematográfico:

1. **`Roboto` (Fuentes proporcionales de interfaz):**
   - Se utiliza para toda la lectura humana: títulos de pantalla, etiquetas de menú, botones de acción (`SELECCIONAR`, `USAR CONFIGURACIÓN`), diálogos y textos descriptivos.
   - Pesos: Regular (400) y Medium/Semibold (600).
2. **`Roboto Mono` (Fuentes monoespaciadas técnicas):**
   - Se utiliza para todos los **datos numéricos y lecturas críticas**: timecode (`00:18.400`), fotogramas (`FOT. 442/442`), valores de exposición (`ISO 100`, `1/50 s`, `f/1.85`), resoluciones (`2032×1536`), tasas de cuadros (`24 FPS`) y tamaños de archivo (`183.9 MB`).
   - **Beneficio cinematográfico:** Evita que los números "bailen" u oscilen de ancho al cambiar de valor durante la reproducción o monitorización en tiempo real.

| Token Tipográfico | Tamaño Par (`sp`) | Interlineado Par (`dp`) | Familia | Uso |
| :--- | :--- | :--- | :--- | :--- |
| `--type-micro` | **8 sp** | 12 dp | `Roboto Mono` | Subíndices, timecode micro, marcas de sensor |
| `--type-badge` | **10 sp** | 14 dp | `Roboto` / `Roboto Mono` | Badges de formato (`RAW10`, `HLG 10`), pérdidas |
| `--type-meta`  | **12 sp** | 16 dp | `Roboto` | Descripciones secundarias de ajustes |
| `--type-body`  | **14 sp** | 20 dp | `Roboto` | Texto general, opciones de lista |
| `--type-action`| **16 sp** | 20 dp | `Roboto` (Peso 600) | Botones de acción y confirmación |
| `--type-sub`   | **20 sp** | 26 dp | `Roboto` | Encabezados de rueda y secciones |
| `--type-title` | **24 sp** | 30 dp | `Roboto` (Peso 400) | Títulos principales (`TOMAS`, `CONFIGURACIÓN`) |
| `--type-metric`| **32 sp** | 38 dp | `Roboto Mono` | Métricas de visor (`24 FPS`, `ISO 100`, `1/50`) |
| `--type-hero`  | **40 sp** | 48 dp | `Roboto Mono` | Contador de tiempo en grabación |

---

## 5. Instrucciones para Continuar en un Nuevo Chat

Si decides abrir un chat nuevo para tener el contexto limpio:
1. Pega la siguiente instrucción inicial:
   > *"Hola, revisa el archivo `plan_de_implementacion.md` en la raíz de mi proyecto `/Users/martin/RawLite` y continúa con la ejecución de los puntos allí descritos."*
2. El agente leerá este archivo estructurado y procederá de inmediato con la implementación en C++ (`rawlite_writer.cpp`) y la actualización visual en `design/rawlite-ui-language/`.
