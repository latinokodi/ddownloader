# DDownloader

Cliente de descargas para Android que usa **TorBox** o **Real-Debrid** para bajar películas y series.
No descarga por P2P: el debrid prepara el archivo en su nube y la app baja el resultado por HTTPS,
con reanudación.

![Descubrir](EVIDENCE/screenshots/parity-catalogue2.png)

## Para qué sirve

Sirve para buscar un título o pegar un enlace/magnet y **descargarlo a tu teléfono** usando tu cuenta
de debrid, sin esperar a un torrent y sin dejar el PC encendido. También ordena lo descargado en
carpetas listas para Jellyfin.

## Características

- **Tres fuentes de streams**: Comet, Cinecalidad y TCL (se consultan en paralelo y se unifican).
- **Descubrir con dos modos**: filas de TMDB o los catálogos de cualquier add-on de Stremio.
- **Descarga desde debrid**: magnet o `.torrent` → TorBox/Real-Debrid → enlace directo → archivo local.
- **Enlaces directos**: pega una URL `https` y descárgala sin cuenta debrid.
- **Reanudable**: continúa desde donde se cortó (HTTP Range), sobrevive al cierre de la app y a la
  pérdida de red.
- **Cola con estado**: progreso, velocidad, tiempo restante, reintentar/eliminar.
- **Carpetas Jellyfin**: `Movies/<título> (año)` y `Series/<título> (año)/Season NN`.
- **Idiomas**: español e inglés, se cambia al instante.
- **Cuentas seguras**: los tokens se guardan cifrados con el Keystore de Android.
- **Actualizaciones dentro de la app** (verifica SHA-256 y firma antes de instalar).

## Instalación

1. Descarga el APK desde [Releases](https://github.com/latinokodi/ddownloader/releases/latest).
2. En el teléfono, permite instalar apps de esa fuente cuando Android lo pregunte
   (*Ajustes → Aplicaciones → Acceso especial → Instalar apps desconocidas*). Aparecerá el aviso de
   Play Protect por no venir de la Play Store: es normal, elige **Instalar de todos modos**.
3. Abre la app. El asistente inicial te pedirá, en este orden:
   - vincular **al menos una cuenta debrid** (te muestra un código corto y el enlace);
   - una **clave de API de TMDB** (gratis en themoviedb.org → Ajustes → API);
   - opcionalmente, la carpeta de descargas.
4. Listo. Busca un título en **Descubrir** o pega un magnet en **Buscar**.

**Requisitos:** Android 8.0 o superior.

## Notas

- Proyecto personal, sin Play Store; por eso el APK se instala manualmente.
- El debrid es obligatorio para torrents; los enlaces directos funcionan sin cuenta.
- Código fuente y compilación: ver `SPEC.md`, `DECISIONS.md` y `RELEASING.md`.
