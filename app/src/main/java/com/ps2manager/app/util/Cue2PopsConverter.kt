package com.ps2manager.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wraps the bundled `cue2pops` native binary (compiled from
 * https://github.com/israpps/cue2pops — a genuine build of CUE2POPS v2.0,
 * which is specifically the version pops2cue's own docs say it can read
 * back; bucanero/pops2cue's own bundled cue2pops.c is a later v2.3 with an
 * incompatible VCD header format), shipped as libcue2pops.so so Android's
 * exec() sandbox allows running it.
 *
 * cue2pops requires:
 *   - a MODE2/2352 raw BIN dump referenced by an ASCII .cue sheet
 *   - single-file dumps (merge multi-track sets first if you have a
 *     multi-track rip; most single-file BIN/CUE rips work as-is)
 *
 * Both cueFile and outputDir must be real filesystem paths (not SAF content://
 * Uris) since running a native binary requires actual files — the repository
 * layer copies files in from SAF first, and copies the result back out after.
 */
class Cue2PopsConverter(private val context: Context) {

    data class ConversionResult(
        val success: Boolean,
        val outputVcd: File?,
        val log: String
    )

    private val binaryPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libcue2pops.so").absolutePath

    suspend fun convert(cueFile: File, outputDir: File): ConversionResult = withContext(Dispatchers.IO) {
        if (!cueFile.exists()) {
            return@withContext ConversionResult(false, null, "CUE file not found: ${cueFile.path}")
        }
        if (!File(binaryPath).exists()) {
            return@withContext ConversionResult(
                false, null,
                "libcue2pops.so not found at $binaryPath — this device's ABI may not be one of the " +
                    "ones built (arm64-v8a, armeabi-v7a); see app/src/main/jniLibs/README.md."
            )
        }
        outputDir.mkdirs()
        val outputVcd = File(outputDir, cueFile.nameWithoutExtension + ".VCD")

        try {
            val process = ProcessBuilder(binaryPath, cueFile.absolutePath, outputVcd.absolutePath)
                .redirectErrorStream(true)
                .directory(cueFile.parentFile)
                .start()

            val log = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, TimeUnit.MINUTES)
            val exitCode = if (finished) process.exitValue() else -1

            // The tool may not always honor our exact requested output filename —
            // fall back to whatever .VCD it actually created in outputDir.
            val actualVcd = if (outputVcd.exists()) {
                outputVcd
            } else {
                outputDir.listFiles()?.firstOrNull { it.extension.equals("vcd", ignoreCase = true) }
            }

            // This reconstructed tool apparently exits with code 1 even on
            // genuine success (its own log confirms the file was written) —
            // so exit code alone isn't a reliable success signal here. Trust
            // the actual output file existing with a sane size instead.
            val success = finished && actualVcd != null && actualVcd.length() > 1024
            val fullLog = "exit code: $exitCode\n$log"

            ConversionResult(success, if (success) actualVcd else null, fullLog)
        } catch (e: Exception) {
            ConversionResult(false, null, "Conversion failed to start: ${e.message}")
        }
    }
}
