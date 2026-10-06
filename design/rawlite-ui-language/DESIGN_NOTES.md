# PURA UI Lab — dirección 02

Este prototipo no sustituye la interfaz Android ni modifica su geometría. Su objetivo es permitir
evaluar el lenguaje de galería, reproductor, ajustes, selección y herramientas antes de trasladar
decisiones a la aplicación.

## Arquitectura de configuración

La configuración utiliza profundidad progresiva, no una lista plana:

1. **General** reúne comportamiento del visor y de operación: cuadrícula, horizonte, mano de
   control, rotación de etiquetas, respuesta háptica y zona segura para accesorios.
2. **Proyecto** describe el archivo que se va a generar: perfil de captura, cadencia, bitrate,
   audio, color, almacenamiento y perfil verificado del dispositivo.
3. **Formato y resolución** es un instrumento de dos ruedas. La primera elige el formato y la
   segunda sólo habilita ventanas de captura verificadas para ese formato y cámara.

La relación de aspecto se deriva de la resolución elegida; no se introduce una tercera rueda que
complique la navegación. Una combinación no soportada permanece visible, bloqueada y con una
explicación. El usuario nunca puede confirmar una configuración que el perfil del dispositivo no
haya validado.

El selector conserva la gramática del D-pad: izquierda/derecha cambia de rueda, arriba/abajo
cambia el valor, pulsación aplica y retroceso cancela. La cadencia continúa en el visor porque es
un parámetro operativo de exposición; el menú de proyecto muestra su valor sólo como contexto.

## Idea rectora

**Mesa de luz.** La cámara ya habla con precisión técnica: negro, blanco, amarillo, reglas y
metadatos. Las pantallas secundarias deben sentirse como la mesa donde se revisa y organiza ese
material, no como una aplicación móvil genérica.

La única decisión visual deliberadamente nueva es el **cristal de contacto**: un fondo mate muy
tenue construido a partir del color de miniaturas ya disponibles. En Android podría generarse una
vez al entrar a la galería; nunca requiere mantener la cámara ni un vídeo vivo detrás de la UI.

## Tokens compactos

- `NEGRO ÓPTICO` — `#000000`: fondo absoluto y escenario de imagen.
- `VIDRIO` — `#0A0A0A` / `rgba(10,10,10,.78)`: barras y superficies técnicas.
- `BLANCO PELÍCULA` — `#F7F7F7`: controles y lectura primaria.
- `GRIS EMULSIÓN` — `#A8A8A8`: metadatos y estados secundarios.
- `AMARILLO SEÑAL` — `#FFC100`: selección, herramienta activa y confirmación.
- `ROJO SEGURIDAD` — `#FF2B2B`: grabación, pérdida real y destrucción.

El verde `#35D66B` conserva un único significado funcional: un parámetro está en automático o una
medición ha quedado confirmada.

## Tipografía

Open Sans es la única familia. La jerarquía se consigue con peso, caja y espaciado:

- **Datos:** 300–400, números tabulares, poco espaciado.
- **Acciones:** 600, mayúsculas y `0.08em` de tracking.
- **Metadatos:** 400, gris y tamaño contenido.

## Geometría

```text
GALERÍA / HORIZONTAL
┌──────────────────────────────────────────────────────────────┐
│ ‹  TOMAS                     resumen        SEL   ···        │
├──────────────────────────────────────────────────────────────┤
│ ┌──────────────────────┐  ┌──────────────────────┐           │
│ │ imagen               │  │ imagen               │           │
│ │ ─ marca de registro  │  │ ─ marca de registro  │           │
│ ├──────────────────────┤  ├──────────────────────┤           │
│ │ nombre / formato / estado                     │           │
│ └──────────────────────┘  └──────────────────────┘           │
└──────────────────────────────────────────────────────────────┘

REPRODUCTOR / HORIZONTAL
┌──────────────────────────────────────────────┬───────────────┐
│                                              │ metadatos     │
│                 imagen                       │ ───────────── │
│                                              │ transporte    │
│                                              │ herramientas  │
└──────────────────────────────────────────────┴───────────────┘
```

En vertical, la galería usa una columna y el transporte pasa debajo del visor, respetando el
comportamiento actual.

## Separación de acciones

- **Universales:** compartir, información y borrar.
- **Herramientas de formato:** convertir RAW10 a CinemaDNG, validar integridad y acciones futuras
  que sólo tengan sentido para un tipo de toma.
- PURE 10 y CinemaDNG nunca se presentan como material convertible a CinemaDNG.

## Movimiento

- Estado y selección: `160 ms`.
- Paneles y cambio de pantalla: `260 ms`.
- Curva: `cubic-bezier(.4, 0, .2, 1)`.
- El prototipo respeta `prefers-reduced-motion`.
- Seleccionar no reconstruye la cuadrícula ni cambia su posición de scroll.
