package app.term.one

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object TermIcons {

    class Variant(val id: String, val label: String, val alias: String)

    val VARIANTS = listOf(
        Variant("default", "Default", "LauncherDefault"),
        Variant("sky", "Sky", "LauncherSky"),
        Variant("emerald", "Emerald", "LauncherEmerald"),
        Variant("amber", "Amber", "LauncherAmber"),
        Variant("rose", "Rose", "LauncherRose"),
        Variant("violet", "Violet", "LauncherViolet")
    )

    // The aliases are declared relative to the namespace, not to the application id.
    private const val NAMESPACE = "app.term.one"

    private fun component(context: Context, variant: Variant) =
        ComponentName(context.packageName, "$NAMESPACE.${variant.alias}")

    fun current(context: Context): String {
        val pm = context.packageManager
        for (variant in VARIANTS) {
            val state = try {
                pm.getComponentEnabledSetting(component(context, variant))
            } catch (e: IllegalArgumentException) {
                continue
            }
            val enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && variant.id == "default")
            if (enabled) {
                return variant.id
            }
        }
        return "default"
    }

    // Enables the chosen icon first, so there is always a launcher entry.
    fun apply(context: Context, id: String) {
        val pm = context.packageManager
        val chosen = VARIANTS.firstOrNull { it.id == id } ?: return
        try {
            pm.setComponentEnabledSetting(
                component(context, chosen),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            for (variant in VARIANTS) {
                if (variant === chosen) {
                    continue
                }
                pm.setComponentEnabledSetting(
                    component(context, variant),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: IllegalArgumentException) {
            return
        }
    }
}