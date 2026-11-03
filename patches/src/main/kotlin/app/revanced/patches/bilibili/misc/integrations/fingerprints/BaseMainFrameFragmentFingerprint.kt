package app.revanced.patches.bilibili.misc.integrations.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object BaseMainFrameFragmentFingerprint : MethodFingerprint(
    strings = listOf("home.menu_update_enable"),
    // The menu config lookup moved into a Callable in current clients.
    customFingerprint = { _, clazz -> clazz.type.startsWith("Ltv/danmaku/bili/ui/main2/") }
)
