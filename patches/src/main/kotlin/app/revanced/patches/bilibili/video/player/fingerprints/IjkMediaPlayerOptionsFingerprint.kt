package app.revanced.patches.bilibili.video.player.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object IjkMediaPlayerOptionsFingerprint : MethodFingerprint(
    customFingerprint = { method, clazz ->
        clazz.type == "Ltv/danmaku/ijk/media/player/IjkMediaPlayerItem;" && method.name == "setItemOptions"
    },
    parameters = listOf(),
    returnType = "V"
)
