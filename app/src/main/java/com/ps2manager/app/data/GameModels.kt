package com.ps2manager.app.data

/** Represents one game file found on the USB/HDD drive. */
data class GameFile(
    val documentId: String,      // SAF document id, used to locate/rename the file
    val displayName: String,     // current filename as it is on disk
    val gameId: String?,         // parsed PS2 Game ID, e.g. "SLUS_212.42" (null if not recognized)
    val currentTitle: String?,   // title portion already present in the filename, if any
    val sizeBytes: Long,
    var matchedTitle: String? = null,   // title resolved from the online database
    var coverArtUrl: String? = null,    // resolved cover art URL, once found
    var coverArtLocalPath: String? = null, // cached local copy, once downloaded
    var status: GameStatus = GameStatus.PENDING
)

enum class GameStatus {
    PENDING,        // not looked up yet
    LOOKING_UP,     // querying the title/art database
    MATCHED,        // title + art found
    NO_MATCH,       // no title found for this Game ID
    RENAMED,        // file has been renamed on disk
    ERROR
}
