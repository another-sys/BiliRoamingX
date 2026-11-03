package app.revanced.patches.bilibili.misc.okhttp.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint
import com.android.tools.smali.dexlib2.Opcode

object RealCallFingerprint : MethodFingerprint(
    strings = listOf("Canceled"),
    parameters = listOf(),
    opcodes = listOf(
        Opcode.NEW_INSTANCE,
        Opcode.INVOKE_DIRECT,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
    ),
    customFingerprint = { methodDef, classDef ->
        // The APK also bundles other HTTP implementations with identical strings.
        methodDef.returnType == "Lokhttp3/Response;" &&
                classDef.fields.any { it.type == "Lokhttp3/Request;" }
    }
)
