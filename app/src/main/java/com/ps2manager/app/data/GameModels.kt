package com.ps2manager.app.data

/** Represents one game file found on the USB/HDD drive. */
data class GameFile(
    val documentId: String,
    val displayName: String,
    val gameId: String?,
    val currentTitle: String?,
    val sizeBytes: Long,
    val isUlGame: Boolean = false,
    val ulParts: Int = 0,
    var matchedTitle: String? = null,
    var artSet: ArtSet? = null,
    var coverArtUrl: String? = null,
    var coverArtLocalPath: String? = null,
    var status: GameStatus = GameStatus.PENDING
)

enum class GameStatus {
    PENDING,
    LOOKING_UP,
    MATCHED,
    PREVIEW,
    NO_MATCH,
    RENAMED,
    ERROR
}
