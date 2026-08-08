package com.ps2manager.app.util

/**
 * PS2 Game IDs look like: SLUS_212.42, SLES-500.12, SCUS_971.24, SLPM_650.01, etc.
 * OPL's required on-disk format is: SLUS_212.42.Game Title.iso
 * People's raw ISO dumps are often just: SLUS-21242.iso  (hyphen, no dot)
 *
 * This util recognizes both forms and normalizes to OPL's underscore+dot format.
 */
object GameIdUtil {

    // Matches things like SLUS-21799, SLUS_217.99, SCES-12345, SLPM_650.01
    private val ID_REGEX = Regex(
        "(SLUS|SLES|SCUS|SCES|SLPS|SLPM|SCPS|SCAJ|SLKA|SLAJ)[-_](\\d{3})\\.?(\\d{2})",
        RegexOption.IGNORE_CASE
    )

    /** Returns the normalized Game ID (e.g. "SLUS_217.99") found anywhere in the filename, or null. */
    fun extractGameId(filename: String): String? {
        val match = ID_REGEX.find(filename) ?: return null
        val (prefix, digits, suffix) = match.destructured
        return "${prefix.uppercase()}_$digits.$suffix"
    }

    /**
     * If the filename already has a title baked in, e.g. "SLUS_217.99.Burnout Revenge.iso",
     * pull that title out so we don't overwrite a name the user already fixed up.
     */
    fun extractExistingTitle(filename: String, gameId: String?): String? {
        if (gameId == null) return null
        val withoutExt = filename.substringBeforeLast('.', filename)
        val idPattern = gameId.replace("_", "[-_]").replace(".", "\\.?")
        val regex = Regex("$idPattern\\.?(.+)", RegexOption.IGNORE_CASE)
        val match = regex.find(withoutExt) ?: return null
        val title = match.groupValues.getOrNull(1)?.trim(' ', '.', '-')
        return if (title.isNullOrBlank()) null else title
    }

    /** Builds the OPL-standard filename: GameID.Title.ext */
    fun buildOplFilename(gameId: String, title: String, extension: String): String {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        return "$gameId.$safeTitle.$extension"
    }
}
