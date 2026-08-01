# RawLite: captura movil RAW y HLG10

RawLite es una aplicacion de camara para Android orientada a grabacion cinematografica y operacion con una sola mano. Actualmente funciona en un Pixel 6 y puede grabar tanto en RAW como en **PURE 10**, un modo HLG de 10 bits considerablemente mas ligero y eficiente.

## Control con una sola mano

El D-pad es el componente principal de la interfaz. Permite controlar toda la camara sin apartar la otra mano de una luz, un estabilizador u otro accesorio:

- Un eje recorre los parametros de la barra superior.
- El otro modifica el valor del parametro seleccionado.
- Admite pulsaciones para realizar ajustes precisos.
- Mantener presionado incrementa progresivamente la velocidad.
- Los gestos de deslizamiento permiten recorrer rapidamente la escala.
- El bloqueo de eje evita modificar accidentalmente otro parametro.

Desde el D-pad se pueden controlar FPS, obturacion, ISO, balance de blancos, enfoque y resolucion. La aplicacion tambien incluye controles automaticos independientes, bloqueo de parametros durante la grabacion y respuesta haptica.

## Por que 10 bits

La grabacion convencional en smartphones suele utilizar 8 bits por canal, lo que representa aproximadamente **16,7 millones de colores**. Una captura de 10 bits puede representar alrededor de **1.070 millones de colores**.

Esta diferencia resulta especialmente importante al hacer color grading:

- Gradientes mas suaves.
- Menos banding en cielos, paredes y sombras.
- Mayor margen para modificar exposicion y color.
- Mejor conservacion de tonos al trabajar con HLG o perfiles logaritmicos.

## RAW frente a PURE 10

RAW ofrece la mayor flexibilidad porque conserva directamente la informacion Bayer del sensor, sin aplicar el procesamiento fotografico habitual de Google. El inconveniente es su enorme consumo de almacenamiento y el ancho de banda necesario para escribir cada fotograma.

Por este motivo desarrolle **PURE 10**:

1. Camera2 entrega fotogramas RAW10 reducidos directamente desde el sensor.
2. Un conversor nativo en C++ transforma el Bayer RAW10 a P010.
3. Se aplica la transferencia HLG y se conserva una profundidad de 10 bits.
4. El encoder HEVC dedicado del Pixel comprime el video como HEVC Main10.
5. El resultado se guarda en MP4 con señalizacion BT.2020/HLG.

Este proceso evita cargar la GPU y utiliza el encoder de video por hardware del telefono.

En el Pixel 6, PURE 10 funciona a aproximadamente **50 Mb/s**, equivalentes a unos **375-400 MB por minuto** incluyendo audio. Frente al RAW10 reducido, consume aproximadamente entre **9 y 19 veces menos espacio**, dependiendo de los FPS. Comparado con una secuencia CinemaDNG de 12,5 MP, la diferencia puede superar ampliamente las 50 veces.

## Tecnologias implementadas

- Camera2 API con acceso a sensores fisicos.
- Captura RAW_SENSOR y RAW10.
- CinemaDNG secuencial.
- Conversion Bayer RAW10 a P010 mediante C++ y Android NDK.
- HEVC Main10 acelerado por hardware.
- Espacio de color BT.2020 y transferencia HLG de 10 bits.
- Control manual de exposicion, ISO, balance de blancos y enfoque.
- Grabacion a 15, 24, 25 y 30 FPS.
- Audio PCM estereo de 24 bits y 48 kHz.
- Sincronizacion mediante timestamps de camara y audio.
- Orientacion independiente del bloqueo de pantalla.
- Interfaz adaptable para diestros y zurdos.
- Telemetria de temperatura, almacenamiento y fotogramas perdidos.
- Galeria interna compatible con RAW10 y PURE 10.

## Resultados actuales

En pruebas reales, PURE 10 sostuvo **30 FPS sin perdidas del encoder**, produciendo archivos HEVC Main10 validos y reconocidos correctamente por FFmpeg y DaVinci Resolve.

El objetivo de RawLite es equilibrar tres aspectos que normalmente compiten entre si en un smartphone:

- Profundidad de color suficiente para color grading.
- Consumo de almacenamiento razonable.
- Eficiencia termica mediante aceleracion de hardware.

