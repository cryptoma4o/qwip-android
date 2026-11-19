package com.qwipvpn.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class WidgetTheme(
    val id: String = UUID.randomUUID().toString(), val name: String = "QWIP", val background: String = "system",
    val solid: String = "#111713", val gradientStart: String = "#0E8A47", val gradientEnd: String = "#0A3324", val angle: Int = 160,
    val photo: String? = null, val photoDim: Float = 0.25f, val photoLuminance: Float = 0.4f,
    val accent: String = "#0E8A47", val inactive: String = "#8E8E93", val textTone: String = "auto", val textColor: String = "#FFFFFF", val font: String = "system",
) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl))
        require(background in setOf("system", "solid", "gradient", "photo") && textTone in setOf("auto", "light", "dark", "custom"))
        require(font in setOf("system", "rounded", "serif", "mono") && angle in 0..360 && photoDim in 0f..0.8f && photoLuminance in 0f..1f)
        require(listOf(solid, gradientStart, gradientEnd, accent, inactive, textColor).all(::validColor))
        require(photo == null || photo.matches(Regex("widget-bg-[a-f0-9-]{36}\\.jpg")))
    }
    companion object {
        fun validColor(value: String) = value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
        val presets = listOf(
            WidgetTheme(id="qwip", name="QWIP"),
            WidgetTheme(id="light", name="Light", background="solid", solid="#F4F7F4", textTone="dark"),
            WidgetTheme(id="dark", name="Graphite", background="solid", solid="#191C20", textTone="light", accent="#32C45E"),
            WidgetTheme(id="ocean", name="Ocean", background="gradient", gradientStart="#1777D8", gradientEnd="#123B70", accent="#70C7FF"),
            WidgetTheme(id="purple", name="Lilac", background="gradient", gradientStart="#8064D9", gradientEnd="#30234F", accent="#D0B6FF"),
            WidgetTheme(id="sunset", name="Sunset", background="gradient", gradientStart="#F38A55", gradientEnd="#9C3F69", accent="#FFE2AA"),
            WidgetTheme(id="mint", name="Mint", background="gradient", gradientStart="#BFECD9", gradientEnd="#76B99B", textTone="dark", accent="#12683A"),
        )
    }
}
@Serializable data class WidgetLibrary(val defaultId: String = "qwip", val custom: List<WidgetTheme> = emptyList()) {
    val all get() = WidgetTheme.presets + custom
    fun theme(id: String?) = all.firstOrNull { it.id == (id ?: defaultId) } ?: WidgetTheme.presets.first()
    fun save(theme: WidgetTheme): WidgetLibrary {
        theme.validate(); require(WidgetTheme.presets.none { it.id == theme.id })
        val next = custom.filterNot { it.id == theme.id } + theme; require(next.size <= 12)
        return copy(custom = next)
    }
    fun delete(id: String) = copy(custom = custom.filterNot { it.id == id }, defaultId = if (defaultId == id) "qwip" else defaultId)
}
@Serializable data class WidgetPreferences(val themeId: String? = null, val layout: String = "standard", val accent: String? = null,
    val showStatus: Boolean = true, val showServer: Boolean = true, val showFlag: Boolean = true, val showTimer: Boolean = true, val showButton: Boolean = true) {
    fun validate() { require(layout in setOf("standard", "compact", "button", "ring", "icon") && (accent == null || WidgetTheme.validColor(accent))) }
}
