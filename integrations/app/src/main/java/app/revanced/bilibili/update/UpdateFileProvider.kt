package app.revanced.bilibili.update

import androidx.annotation.Keep
import androidx.core.content.FileProvider

/** A distinct provider component keeps the host's existing provider registration intact. */
@Keep
class UpdateFileProvider : FileProvider()
