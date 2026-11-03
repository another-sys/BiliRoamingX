package app.revanced.bilibili.settings

import app.revanced.bilibili.utils.Versions

/** Settings whose bytecode hooks are not included in the 9.17 release profile. */
object ReleaseCompatibility {
    val unavailableKeys = setOf(
        "external_downloader", "fragment_external_downloader",
        "comment_copy", "comment_copy_enhance", "custom_theme", "biliroaming_custom_color",
        "switch_dark_dialog", "long_press_playback_speed", "long_press_speed",
        "disable_player_long_press", "scale_to_switch_ratio", "delay_download_modules",
        "remember_playback_speed", "subtitle_import_save", "trial_vip_quality",
        // The upstream prebuilt update service is retired. Use this fork's Releases.
        "custom_update"
    )

    fun unavailable(key: String) = key in unavailableKeys && Versions.ge9_17_0()
}
