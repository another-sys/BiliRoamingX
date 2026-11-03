package app.revanced.patches.bilibili.misc.protobuf.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object MossOkHttpInterceptorFingerprint : MethodFingerprint(
    returnType = "Lokhttp3/Response;",
    parameters = listOf("Lokhttp3/Interceptor\$Chain;"),
    strings = listOf("x-bili-metadata-bin", "x-bili-network-bin")
)
