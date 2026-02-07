package com.odtheking.odinaddon.features.impl.render

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import net.fabricmc.loader.api.FabricLoader
import java.lang.reflect.Method

object Patcher : Module(
    name = "Patcher",
    description = "Patches runtime variables from Devonian."
) {
    private val boxStarMobPhase by BooleanSetting(
        "BoxStarMob Phase",
        false,
        desc = "Patches Devonian BoxStarMob.SETTING_PHASE."
    )
    private val dungeonMapRenderHiddenRooms by BooleanSetting(
        "Render Hidden Rooms",
        false,
        desc = "Patches Devonian DungeonMap.SETTING_RENDER_HIDDEN_ROOMS."
    )

    private data class RuntimePatch(
        val targetName: String,
        val className: String,
        val setterName: String,
        val valueProvider: () -> Boolean,
        var targetInstance: Any? = null,
        var setter: Method? = null,
    )

    private val patches = listOf(
        RuntimePatch(
            targetName = "BoxStarMob.SETTING_PHASE",
            className = "com.github.synnerz.devonian.features.dungeons.clear.BoxStarMob",
            setterName = "setSETTING_PHASE",
            valueProvider = { boxStarMobPhase },
        ),
        RuntimePatch(
            targetName = "DungeonMap.SETTING_RENDER_HIDDEN_ROOMS",
            className = "com.github.synnerz.devonian.features.dungeons.map.DungeonMap",
            setterName = "setSETTING_RENDER_HIDDEN_ROOMS",
            valueProvider = { dungeonMapRenderHiddenRooms },
        ),
    )

    private var hasDevonian = false
    private var patchResolved = false
    private var patchResolveFailed = false
    private var patchErrorNotified = false

    init {
        on<TickEvent.End> {
            if (!enabled || !hasDevonian || patchResolveFailed) return@on
            if (!patchResolved) patchResolved = resolvePatches()
            if (patchResolved) applyPatches()
        }
    }

    override fun onEnable() {
        super.onEnable()
        hasDevonian = FabricLoader.getInstance().isModLoaded("devonian")
        patchResolved = false
        patchResolveFailed = false
        patchErrorNotified = false

        if (!hasDevonian) {
            modMessage("§e[Patcher] Devonian not installed, skipping runtime patch.")
            return
        }

        patchResolved = resolvePatches()
        if (patchResolved) applyPatches()
    }

    private fun resolvePatches(): Boolean {
        return runCatching {
            patches.forEach { patch ->
                val clazz = Class.forName(patch.className)
                patch.targetInstance = clazz.getField("INSTANCE").get(null)
                patch.setter = clazz.getMethod(
                    patch.setterName,
                    Boolean::class.javaPrimitiveType
                )
            }
            true
        }.getOrElse { throwable ->
            patchResolveFailed = true
            if (!patchErrorNotified) {
                patchErrorNotified = true
                modMessage("§c[Patcher] Failed to resolve Devonian patch targets: ${throwable.message}")
            }
            false
        }
    }

    private fun applyPatches() {
        patches.forEach { patch ->
            val setter = patch.setter ?: return@forEach
            val instance = patch.targetInstance ?: return@forEach
            runCatching {
                setter.invoke(instance, patch.valueProvider())
            }.onFailure { throwable ->
                if (!patchErrorNotified) {
                    patchErrorNotified = true
                    modMessage("§c[Patcher] Failed to patch ${patch.targetName}: ${throwable.message}")
                }
            }
        }
    }
}
