package com.gallr.app.share

/** One encoded export, retained by the preview and handed unchanged to the native sheet. */
class StoryCardImage(
    val pngBytes: ByteArray,
    val shareDescriptor: String,
    internal val filePath: String? = null,
)

/** ARGB values from DESIGN.md, shared by both native renderers. */
data class ExhibitionStoryCardPalette(
    val background: Int,
    val title: Int,
    val secondary: Int,
    val divider: Int,
    val frame: Int,
    val placeholder: Int,
    val transparent: Int = 0,
) {
    companion object {
        val LIGHT =
            ExhibitionStoryCardPalette(
                background = 0xFFFFFFFF.toInt(),
                title = 0xFF000000.toInt(),
                secondary = 0xFF525252.toInt(),
                divider = 0xFFE5E5E5.toInt(),
                frame = 0xFFE5E5E5.toInt(),
                placeholder = 0xFFF5F5F5.toInt(),
            )
        val DARK =
            ExhibitionStoryCardPalette(
                background = 0xFF121212.toInt(),
                title = 0xFFE0E0E0.toInt(),
                secondary = 0xFFA0A0A0.toInt(),
                divider = 0xFF333333.toInt(),
                frame = 0xFF404040.toInt(),
                placeholder = 0xFF2C2C2C.toInt(),
            )
    }
}
