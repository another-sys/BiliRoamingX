package app.revanced.patches.bilibili.misc.settings.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object PreferenceManagerFingerprint : MethodFingerprint(
    strings = listOf("_has_set_default_values"),
    // R8 in 9.17 specializes setDefaultValues to (int, Context).
    customFingerprint = { _, clazz -> clazz.type.startsWith("Landroidx/preference/") },
    returnType = "V"
)
