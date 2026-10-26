package com.ddownloader.android.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * In-app language. Two fully-authored locales: English and Spanish.
 *
 * The choice is persisted in settings (`language` = "en" | "es") and provided through
 * [LocalStrings] at the root of the composition, so switching recreates nothing and
 * takes effect on the next frame. Strings live here (not in res/values) so every string
 * that the UI uses is in one diffable place with both translations side by side.
 */
enum class Language(val tag: String, val label: String, val nativeLabel: String) {
    EN("en", "English", "English"),
    ES("es", "Spanish", "Español");

    companion object {
        fun fromTag(tag: String?): Language = entries.firstOrNull { it.tag == tag } ?: EN
    }
}

data class Strings(
    // navigation
    val search: String,
    val discover: String,
    val downloads: String,
    val settings: String,
    val logs: String,
    // common
    val save: String,
    val clear: String,
    val link: String,
    val unlink: String,
    val cancel: String,
    val continueLabel: String,
    val later: String,
    val skip: String,
    // settings sections
    val accounts: String,
    val privacy: String,
    val metadata: String,
    val storage: String,
    val backup: String,
    val updates: String,
    val about: String,
    val language: String,
    // settings copy
    val accountsHint: String,
    val accountsNote: String,
    val tmdbHint: String,
    val tmdbLabel: String,
    val destination: String,
    val destinationApp: String,
    val destinationCustom: String,
    val chooseFolder: String,
    val useAppStorage: String,
    val moviesSubfolder: String,
    val seriesSubfolder: String,
    val saveFolders: String,
    val parallelDownloads: String,
    val autoRemove: String,
    val autoRemoveSub: String,
    val wifiOnly: String,
    val wifiOnlySub: String,
    val backupHint: String,
    val exportLabel: String,
    val importLabel: String,
    val updatesHint: String,
    val checkNow: String,
    val updateLabel: String,
    val installLabel: String,
    val upToDate: String,
    val aboutNote: String,
    // account states
    val linked: String,
    val notLinked: String,
    val rejected: String,
    val notVerified: String,
    val checking: String,
    val recheck: String,
    // onboarding
    val setup: String,
    val welcomeBody: String,
    val welcomeNote: String,
    val debridTitle: String,
    val debridBody: String,
    val debridNote: String,
    val tmdbTitle: String,
    val tmdbBody: String,
    val tmdbNote: String,
    val folderTitle: String,
    val folderBody: String,
    val folderReady: String,
    val startUsing: String,
    // linking dialog
    val linkTitle: String,
    val linkHint: String,
    val linkCode: String,
    val linkOpen: String,
    val linkCopy: String,
    val linkWaiting: String,
    val linkDone: String,
    val linkDoneBody: String,
    val linkFailed: String,
    val close: String,
    // search / downloads basics
    val findSomething: String,
    val movieOrSeries: String,
    val tmdbNeeded: String,
    val pasteLink: String,
    val directUrl: String,
    val queueDownload: String,
    val readyToSetup: String,
    val linkAccountHint: String,
    val httpsOnly: String,
    val tokensInKeystore: String,
    val noAccount: String,
    val requiredDebrid: String,
    val requiredTmdb: String,
    val required: String,
)

private val EN = Strings(
    search = "Search", discover = "Discover", downloads = "Downloads", settings = "Settings", logs = "Logs",
    save = "Save", clear = "Clear", link = "Link", unlink = "Unlink", cancel = "Cancel",
    continueLabel = "Continue", later = "Later", skip = "Skip",
    accounts = "Debrid accounts", privacy = "Privacy & security", metadata = "Metadata",
    storage = "Storage", backup = "Backup & restore", updates = "Updates", about = "About",
    language = "Language",
    accountsHint = "Link an account with a code: open the provider's page, type the code, done. " +
        "There is no API token to copy, and the credential never leaves the device.",
    accountsNote = "Linked credentials are encrypted with an Android Keystore key and excluded from backups.",
    tmdbHint = "Optional: a free TMDB key unlocks Discover and search. Direct links work without it.",
    tmdbLabel = "TMDB API key",
    destination = "Destination",
    destinationApp = "App storage → Download/",
    destinationCustom = "Custom folder (SAF)",
    chooseFolder = "Choose folder", useAppStorage = "Use app storage",
    moviesSubfolder = "Movies subfolder", seriesSubfolder = "Series subfolder",
    saveFolders = "Save folders", parallelDownloads = "Parallel downloads",
    autoRemove = "Auto-remove completed", autoRemoveSub = "Delete the queue entry once the file is published",
    wifiOnly = "Wi-Fi only", wifiOnlySub = "Do not start transfers on metered connections",
    backupHint = "Updates keep your data. For a reinstall or a new device, export the configuration " +
        "(credentials are never included) and import it later.",
    exportLabel = "Export", importLabel = "Import",
    updatesHint = "Checked against the project's GitHub Releases. The APK is checksum- and " +
        "signer-verified before Android installs it; settings and accounts are preserved.",
    checkNow = "Check now", updateLabel = "Update", installLabel = "Install", upToDate = "up to date",
    aboutNote = "Credentials are encrypted with a hardware-backed Android Keystore key and are " +
        "excluded from cloud and device-transfer backups.",
    linked = "LINKED", notLinked = "NOT LINKED", rejected = "REJECTED",
    notVerified = "NOT VERIFIED", checking = "CHECKING…", recheck = "Re-check",
    setup = "SETUP", welcomeBody = "TorBox + Real-Debrid client with resumable downloads, " +
        "Jellyfin-ready folders and a foreground service that keeps transfers alive.",
    welcomeNote = "Your accounts stay encrypted on this device, and every request is HTTPS only.",
    debridTitle = "Link\nyour debrid", debridBody = "Pick a provider, open the link it gives you and " +
        "type the code. One account is enough; both works too. Nothing to copy or paste.",
    debridNote = "The code screen finishes on its own once you authorize. Credentials stay " +
        "encrypted on this device and are never shown.",
    tmdbTitle = "Optional:\nTMDB key", tmdbBody = "A free TMDB API key unlocks Discover and title search. " +
        "The app asks TMDB for metadata only; streams come from the embedded Comet add-on.",
    tmdbNote = "You can add this later in Settings — direct links in the Search tab work without it.",
    folderTitle = "Where should\nfiles go?", folderBody = "Pick a folder once and Android remembers the " +
        "permission. Without a choice, files land in this app's own Download folder.",
    folderReady = "Ready. You can change everything later in Settings.",
    startUsing = "Start using DDownloader",
    linkTitle = "Link", linkHint = "Open the link below and enter this code. This screen finishes on " +
        "its own once the account is authorized.",
    linkCode = "CODE", linkOpen = "Open link", linkCopy = "Copy",
    linkWaiting = "Waiting for you to authorize…",
    linkDone = "linked", linkDoneBody = "Credential received and stored encrypted on this device. " +
        "It is never displayed.",
    linkFailed = "Could not link", close = "Close",
    findSomething = "FIND SOMETHING", movieOrSeries = "Movie or series", tmdbNeeded = "TMDB key needed",
    pasteLink = "PASTE A LINK", directUrl = "Direct URL", queueDownload = "Queue download",
    readyToSetup = "READY TO SET UP", linkAccountHint = "Link a debrid account in Settings",
    httpsOnly = "HTTPS only", tokensInKeystore = "tokens in Keystore", noAccount = "NO ACCOUNT",
    requiredDebrid = "Required: link at least one debrid account to continue.",
    requiredTmdb = "Required: a valid TMDB API key is needed to continue.",
    required = "REQUIRED",
)

private val ES = Strings(
    search = "Buscar", discover = "Descubrir", downloads = "Descargas", settings = "Ajustes", logs = "Registro",
    save = "Guardar", clear = "Borrar", link = "Vincular", unlink = "Desvincular", cancel = "Cancelar",
    continueLabel = "Continuar", later = "Más tarde", skip = "Omitir",
    accounts = "Cuentas debrid", privacy = "Privacidad y seguridad", metadata = "Metadatos",
    storage = "Almacenamiento", backup = "Copia y restauración", updates = "Actualizaciones", about = "Acerca de",
    language = "Idioma",
    accountsHint = "Vincula una cuenta con un código: abre la página del proveedor, escribe el código y listo. " +
        "No hay ningún token de API que copiar y la credencial nunca sale del dispositivo.",
    accountsNote = "Las credenciales vinculadas se cifran con una clave del Android Keystore y se excluyen de las copias.",
    tmdbHint = "Opcional: una clave gratuita de TMDB activa Descubrir y la búsqueda. Los enlaces directos funcionan sin ella.",
    tmdbLabel = "Clave de API de TMDB",
    destination = "Destino",
    destinationApp = "Almacenamiento de la app → Download/",
    destinationCustom = "Carpeta personalizada (SAF)",
    chooseFolder = "Elegir carpeta", useAppStorage = "Usar almacenamiento de la app",
    moviesSubfolder = "Subcarpeta de películas", seriesSubfolder = "Subcarpeta de series",
    saveFolders = "Guardar carpetas", parallelDownloads = "Descargas en paralelo",
    autoRemove = "Quitar completadas", autoRemoveSub = "Eliminar la entrada cuando el archivo se publica",
    wifiOnly = "Solo Wi-Fi", wifiOnlySub = "No iniciar descargas en conexiones medidas",
    backupHint = "Las actualizaciones conservan tus datos. Para reinstalar o cambiar de dispositivo, " +
        "exporta la configuración (las credenciales nunca se incluyen) e impórtala después.",
    exportLabel = "Exportar", importLabel = "Importar",
    updatesHint = "Se comprueba en las versiones de GitHub del proyecto. El APK se verifica por checksum " +
        "y firma antes de instalar; los ajustes y las cuentas se conservan.",
    checkNow = "Buscar ahora", updateLabel = "Actualizar", installLabel = "Instalar", upToDate = "al día",
    aboutNote = "Las credenciales se cifran con una clave respaldada por hardware del Android Keystore y " +
        "se excluyen de las copias en la nube y de la transferencia entre dispositivos.",
    linked = "VINCULADA", notLinked = "SIN VINCULAR", rejected = "RECHAZADA",
    notVerified = "SIN VERIFICAR", checking = "COMPROBANDO…", recheck = "Comprobar",
    setup = "AJUSTES", welcomeBody = "Cliente de TorBox + Real-Debrid con descargas reanudables, " +
        "carpetas listas para Jellyfin y un servicio en primer plano que mantiene las transferencias activas.",
    welcomeNote = "Tus cuentas permanecen cifradas en este dispositivo y todas las peticiones usan solo HTTPS.",
    debridTitle = "Vincula\ntu debrid", debridBody = "Elige un proveedor, abre el enlace que te da y " +
        "escribe el código. Con una cuenta basta; también puedes vincular las dos. Nada que copiar ni pegar.",
    debridNote = "La pantalla del código termina sola cuando autorizas. Las credenciales quedan cifradas " +
        "en este dispositivo y nunca se muestran.",
    tmdbTitle = "Opcional:\nclave TMDB", tmdbBody = "Una clave gratuita de TMDB activa Descubrir y la búsqueda " +
        "de títulos. La app solo pide metadatos; los streams vienen del complemento Comet incluido.",
    tmdbNote = "Puedes añadirla luego en Ajustes: los enlaces directos de la pestaña Buscar funcionan sin ella.",
    folderTitle = "¿Dónde van\nlos archivos?", folderBody = "Elige una carpeta una vez y Android recordará el " +
        "permiso. Sin elección, los archivos van a la carpeta Download de la propia app.",
    folderReady = "Listo. Puedes cambiar todo más tarde en Ajustes.",
    startUsing = "Empezar a usar DDownloader",
    linkTitle = "Vincular", linkHint = "Abre el enlace de abajo y escribe este código. Esta pantalla termina " +
        "sola cuando la cuenta esté autorizada.",
    linkCode = "CÓDIGO", linkOpen = "Abrir enlace", linkCopy = "Copiar",
    linkWaiting = "Esperando tu autorización…",
    linkDone = "vinculada", linkDoneBody = "Credencial recibida y guardada cifrada en este dispositivo. " +
        "Nunca se muestra.",
    linkFailed = "No se pudo vincular", close = "Cerrar",
    findSomething = "BUSCAR ALGO", movieOrSeries = "Película o serie", tmdbNeeded = "Falta la clave TMDB",
    pasteLink = "PEGAR UN ENLACE", directUrl = "URL directa", queueDownload = "Añadir descarga",
    readyToSetup = "LISTO PARA CONFIGURAR", linkAccountHint = "Vincula una cuenta debrid en Ajustes",
    httpsOnly = "solo HTTPS", tokensInKeystore = "credenciales en Keystore", noAccount = "SIN CUENTA",
    requiredDebrid = "Obligatorio: vincula al menos una cuenta debrid para continuar.",
    requiredTmdb = "Obligatorio: se necesita una clave TMDB válida para continuar.",
    required = "OBLIGATORIO",
)

val StringsEN: Strings = EN
val StringsES: Strings = ES

fun stringsFor(language: Language): Strings = when (language) {
    Language.EN -> EN
    Language.ES -> ES
}

/** Provided once at the root of the UI from the persisted language setting. */
val LocalStrings = staticCompositionLocalOf { EN }
