package com.elonzh.dshjk.configuration

import kotlinx.serialization.json.JsonArray
import java.nio.file.Path

/** Arguments to one dsh profile launch. Inline patches precede patch files. */
class ProfileInvocation @JvmOverloads constructor(
    val profile: String,
    arguments: List<String> = emptyList(),
    patches: List<HarnessPatch> = emptyList(),
    patchFiles: List<Path> = emptyList(),
) {
    val arguments: List<String> = java.util.List.copyOf(arguments)
    val patches: List<HarnessPatch> = java.util.List.copyOf(patches)
    val patchFiles: List<Path> = java.util.List.copyOf(patchFiles.map { it.toAbsolutePath().normalize() })
    internal val patchDocument = patchDocument(this.patches)

    init {
        require(profile.isNotBlank() && !profile.startsWith("-")) { "Invalid profile" }
    }
}

internal fun patchDocument(patches: List<HarnessPatch>): String = JsonArray(patches.map(HarnessPatch::toJson)).toString()
