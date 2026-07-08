package com.ps2manager.app.data

/** Represents one game file found on the USB/HDD drive. */
data class GameFile(
    val documentId: String,      // SAF document id for ISO files; a synthetic "ul:<GameID>" key for UL games
    val displayName: String,     // current filename (ISO) or current title (UL) as shown on disk
    val gameId: String?,         // parsed PS2 Game ID, e.g. "SLUS_212.42" (null if not recognized)
    val currentTitle: String?,   // title portion already present in the filename, if any
    val sizeBytes: Long,
    val isUlGame: Boolean = false, // true if this came from ul.cfg (split USBExtreme/UL format) rather than a plain ISO
    val ulParts: Int = 0,          // number of split part files, only meaningful when isUlGame
    val parentDocumentId: String? = null, // SAF uri of the containing folder (ISO files only) — needed for copy-fallback rename
    var matchedTitle: String? = null,   // title resolved from the online database
    var artSet: ArtSet? = null,         // fetched (or manually overridden) art, shown during preview
    var coverArtUrl: String? = null,    // resolved cover art URL, once found
    var coverArtLocalPath: String? = null, // cached local copy, once downloaded
    var status: GameStatus = GameStatus.PENDING,
    var lastError: String? = null       // the real reason an operation failed, for display
)

enum class GameStatus {
    PENDING,        // not looked up yet
    LOOKING_UP,     // querying the title/art database
    MATCHED,        // title found, ready to fetch art for preview
    PREVIEW,        // art fetched, waiting for user to confirm or cancel
    NO_MATCH,       // no title found for this Game ID
    RENAMED,        // file has been renamed on disk
    ERROR
}
