# Changelog

Cambios notables del proyecto. Formato inspirado en Keep a Changelog y versionado semántico
(ver `VERSIONING.md`). Las entradas se escriben para el usuario, no para el historial de commits.

## [Unreleased]

## [1.8.2] — 2026-10-27

### Cambiado
- La **carpeta de destino por defecto** es la carpeta nativa de Descargas del sistema, en
  `Descargas/DDownloader`, con subcarpetas de películas y series. En versiones modernas se escribe a
  través del proveedor de medios del sistema, de modo que un archivo a medio escribir nunca aparece
  en la galería ni en el explorador; en versiones antiguas se escribe directamente en la carpeta
  pública.
- Etiqueta de destino y acción de restablecer actualizadas: «Descargas → DDownloader» y
  «Usar la carpeta Descargas».
- Documentación sin menciones a terceros: la descripción del proyecto, las funcionalidades y la
  instalación son genéricas.

### Verificado
- Transferencia real en dispositivo: el archivo terminó en `Descargas/DDownloader` y la carpeta
  privada de la app dejó de existir.

## [1.8.0] — 2026-10-27

### Añadido
- **Tres fuentes de streams** consultadas en paralelo, unificadas y sin duplicados por hash; cada
  resultado indica su origen.
- **Descubrir con dos modos**: filas de metadatos por categorías o los catálogos de un add-on de
  catálogos. La URL del add-on se configura en Ajustes → Metadatos; el add-on integrado por defecto
  no declara catálogos, así que ese modo muestra un aviso explicativo hasta configurar uno.
- Títulos abiertos desde un catálogo usan su identificador externo directamente, sin consulta de
  metadatos adicional.

### Corregido
- **El año se cortaba** en las tarjetas de Descubrir: la sinopsis reserva dos líneas y el año se
  limita a una, así que todas las tarjetas comparten línea base y el año nunca queda fuera.

## [1.7.2] — 2026-10-26

### Cambiado
- El APK se firma con los esquemas **v1, v2 y v3**, para que cualquier instalador o analizador vea
  una firma completa.

## [1.7.0] — 2026-10-26

### Cambiado
- **Diseño adaptativo, primero móvil**: en teléfonos una sola columna con barra inferior plana; en
  ventanas anchas, navegación lateral y contenido centrado con ancho máximo.

## [1.6.1] — 2026-10-26

### Cambiado
- **Nueva estructura de interfaz**: navegación lateral permanente (identidad, estado, destinos,
  registro y versión) en lugar de barra inferior; paleta casi negra con un único acento cálido, tipografía
  grande y geometría casi recta (radios de 3 a 10 dp).

## [1.5.0] — 2026-10-26

### Cambiado
- Sistema visual reconstruido: superficies grafito, un solo acento para acciones, tipografía única
  para todo el texto, radios de 8 a 16 dp, botones planos y un fondo ambiente tenue.
- Pantalla de inicio con encabezado de página y accesos directos con contadores, en lugar del
  logotipo gigante.

## [1.4.4] — 2026-10-26

### Corregido
- La acción «Comprobar» de la tarjeta de cuentas era un botón grande en la línea del título y
  desalineaba la cabecera; ahora es una acción discreta alineada con la etiqueta.

## [1.4.3] — 2026-10-26

### Corregido
- La etiqueta «Status» seguía en inglés en la interfaz en español.

## [1.4.2] — 2026-10-26

### Cambiado
- El resultado de validar la clave de metadatos es **texto en la tarjeta** (verde si se acepta, rojo
  con el motivo si no, cian mientras comprueba) en lugar de un aviso efímero.

## [1.4.1] — 2026-10-26

### Corregido
- Últimos textos en inglés en Ajustes y en diálogos.
- La clave de metadatos se recorta antes de validarse (un salto de línea pegado provocaba rechazo) y
  el mensaje indica el motivo: código de error frente a falta de red.

## [1.4.0] — 2026-10-26

### Cambiado
- Revamp y traducción completados: secciones de Ajustes con icono, progreso animado en el asistente
  inicial y **registro de actividad traducido** (los avisos internos ya no aparecen en inglés).

## [1.3.3] — 2026-10-26

### Corregido
- La barra superior mostraba el nombre interno del texto en lugar de «SESIÓN SEGURA».

## [1.3.2] — 2026-10-26

### Cambiado
- **Toda la interfaz sigue el idioma elegido**: inicio, búsqueda, resultados, hojas de streams,
  Descargas, Registro, Descubrir (incluidos nombres de filas y géneros), diálogos de actualización y
  barra superior. Avisos del sistema también traducidos.
- Eliminado el texto heredado que hablaba de una red que esta app no usa.

## [1.3.0] — 2026-10-26

### Cambiado
- Pantalla de inicio rediseñada: encabezado claro, accesos directos con contadores en vivo y una
  franja de estado, en lugar de tarjetas genéricas.

## [1.2.2] — 2026-10-26

### Corregido
- «Eliminar» no hacía nada en transferencias terminadas o fallidas: solo cambiaba el estado. Ahora
  detiene la transferencia, borra el archivo parcial y elimina la entrada.

## [1.2.1] — 2026-10-26

### Corregido
- La tarjeta base perdió el contenedor y el relleno al sustituir el componente anterior, lo que
  rompía Ajustes y las pantallas ya migradas.

## [1.2.0] — 2026-10-26

### Cambiado
- Sistema de movimiento propio: duraciones y curvas únicas, escala al pulsar, esqueletos con brillo
  y fondo ambiente.
- Pantalla de Descargas convertida en panel de control: métricas, anillo animado por transferencia,
  etiquetas de proveedor y estado, velocidad y tiempo restante.
- Pantalla de Registro convertida en terminal: filtros animados, tipografía monoespaciada y copia.
- Transiciones entre pantallas y barra de navegación animada.

## [1.1.3] — 2026-10-26

### Cambiado
- Los resultados de búsqueda se muestran en **cuadrícula de carátulas** con columnas adaptativas.
- Contador en vivo sobre Descargas: sube al encolar y baja al terminar.

## [1.1.2] — 2026-10-26

### Corregido
- La resolución del enlace de descarga fallaba con error del servicio: faltaba un parámetro
  obligatorio en la petición.
- La lista de hosts permitidos no cubría los dominios de reparto del servicio, así que se rechazaba
  un enlace válido.
- Se registra el motivo devuelto por el servicio cuando falla una resolución.

## [1.1.0] — 2026-10-26

### Añadido
- **Descubrir por filas**: 12 categorías por tipo, carga en paralelo, aislamiento de fallos por fila y
  esqueletos de carga.
- Se pueden pegar **enlaces magnet**.
- Los torrents con varios archivos encolan todos los vídeos (packs de temporada) en lugar de quedarse
  con el mayor.

## [1.0.10] — y anteriores

- Motor de descargas con reanudación por rangos, validación de respuestas parciales y reintentos.
- Servicio en primer plano para que las transferencias sobrevivan al cierre de la app, y reanudación
  tras reinicio del dispositivo.
- Enlaces de cuenta por código de dispositivo, con credenciales cifradas en el almacén de claves del
  sistema.
- Carpetas y nombres compatibles con bibliotecas de medios, guardado de ajustes y actualización
  dentro de la app con verificación de integridad.
