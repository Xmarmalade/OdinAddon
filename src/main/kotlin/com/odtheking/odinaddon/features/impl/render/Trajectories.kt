package com.odtheking.odinaddon.features.impl.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.*
import com.odtheking.odin.utils.Color.Companion.multiplyAlpha
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Trajectories : Module(
    name = "Trajectories",
    description = "Displays the trajectory of pearls and bows."
) {
    private val bows by BooleanSetting("Bows", true, desc = "Render trajectories of bow arrows.")
    private val pearls by BooleanSetting("Pearls", true, desc = "Render trajectories of ender pearls.")
    private val plane by BooleanSetting("Show Plane", false, desc = "Shows a flat square rotated relative to the predicted block that will be hit.")
    private val boxes by BooleanSetting("Show Boxes", true, desc = "Shows boxes displaying where arrows or pearls will hit.")
    private val lines by BooleanSetting("Show Lines", true, desc = "Shows the trajectory as a line.")
    private val range by NumberSetting("Solver Range", 30, 1, 120, 1, desc = "How many ticks are simulated, performance impact scales with this.")
    private val width by NumberSetting("Line Width", 1f, 0.1f, 5.0, 0.1f, desc = "The width of the line.")
    private val planeSize by NumberSetting("Plane Size", 2f, 0.1f, 5.0, 0.1f, desc = "The size of the plane.").withDependency { plane }
    private val boxSize by NumberSetting("Box Size", 0.5f, 0.5f, 3.0f, 0.1f, desc = "The size of the box.").withDependency { boxes }
    private val color by ColorSetting("Color", Colors.MINECRAFT_DARK_AQUA, true, desc = "The color of the trajectory.")
    //private val depth by BooleanSetting("Depth Check", true, desc = "Whether or not to depth check the trajectory.")
    private val legacyTerm by BooleanSetting("Legacy Terminator", false, desc = "Displays old terminator arrow trajectories")

    private var charge = 0f
    private var lastCharge = 0f

    private val shortbowIds = setOf(
        "TERMINATOR",
        "JUJU_SHORTBOW",
        "SPIRIT_BOW",
        "ARTISANAL_SHORTBOW"
    )

    private val boxEdges = intArrayOf(
        0, 1, 1, 5, 5, 4, 4, 0,
        3, 2, 2, 6, 6, 7, 7, 3,
        0, 3, 1, 2, 5, 6, 4, 7
    )

    init {
        on<TickEvent.End> {
            val player = mc.player ?: return@on
            lastCharge = charge
            charge = if (player.isUsingItem) {
                min((72000 - player.useItemRemainingTicks) / 20f, 1.0f) * 2f
            } else {
                0f
            }
            if ((lastCharge - charge) > 1f) lastCharge = charge
        }

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(WorldRenderEvents.DebugRender { context ->
            if (!enabled) return@DebugRender
            renderTrajectories(context)
        })
    }

    private fun renderTrajectories(context: WorldRenderContext) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val heldItem = player.mainHandItem ?: return

        val matrices = context.matrices() ?: return
        val consumers = context.consumers() ?: return
        val bufferSource = consumers as? MultiBufferSource.BufferSource
        val camera = context.gameRenderer().mainCamera?.position ?: return

        val renderEntries = mutableListOf<TrajectoryResult>()

        if (bows && isBowItem(heldItem)) {
            if (isShortbow(heldItem)) {
                val isTerminator = heldItem.itemId == "TERMINATOR"
                renderEntries.add(calculateTrajectory(player, level, 0f, isPearl = false, useCharge = false, isTerminator = isTerminator))
                if (isTerminator) {
                    renderEntries.add(calculateTrajectory(player, level, -5f, isPearl = false, useCharge = false, isTerminator = true))
                    renderEntries.add(calculateTrajectory(player, level, 5f, isPearl = false, useCharge = false, isTerminator = true))
                }
            } else {
                if (!player.isUsingItem) return
                renderEntries.add(calculateTrajectory(player, level, 0f, isPearl = false, useCharge = true, isTerminator = false))
            }
        }

        if (pearls && isPearlItem(heldItem)) {
            renderEntries.add(calculateTrajectory(player, level, 0f, isPearl = true, useCharge = false, isTerminator = false))
        }

        if (renderEntries.isEmpty()) return

        matrices.pushPose()
        matrices.translate(-camera.x, -camera.y, -camera.z)

        val pose = matrices.last()
        val lineBuffer = consumers.getBuffer(RenderType.lines())

        RenderSystem.lineWidth(width)


        for (entry in renderEntries) {
            if (lines) drawLineSegments(pose, lineBuffer, entry.points, color)
            if (boxes) entry.impactBox?.let { drawWireBox(pose, lineBuffer, it, color) }
            if (plane) entry.hit?.let { drawPlaneCollision(pose, lineBuffer, it) }

            for (entity in entry.hitEntities) {
                drawWireBox(pose, lineBuffer, entity.renderBoundingBox, color)
            }
        }

        RenderSystem.lineWidth(1f)

        matrices.popPose()
        bufferSource?.endBatch(RenderType.lines())
    }

    private data class TrajectoryResult(
        val points: List<Vec3>,
        val hit: BlockHitResult?,
        val hitEntities: List<Entity>,
        val impactBox: AABB?
    )

    private fun calculateTrajectory(
        player: net.minecraft.world.entity.player.Player,
        level: net.minecraft.world.level.Level,
        yawOffset: Float,
        isPearl: Boolean,
        useCharge: Boolean,
        isTerminator: Boolean
    ): TrajectoryResult {
        val yaw = Math.toRadians(player.yRot.toDouble())
        var x = -cos(yaw) * 0.16
        var y = player.eyeHeight.toDouble() - 0.1
        var z = -sin(yaw) * 0.16
        if (isTerminator && !legacyTerm) {
            x = 0.0
            y = player.eyeHeight.toDouble() - 0.01
            z = 0.0
        }

        var pos = player.renderPos.addVec(x, y, z)
        val velocityMultiplier = if (isPearl) {
            1.5f
        } else {
            val partialTicks = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            val interpolatedCharge = lastCharge + (charge - lastCharge) * partialTicks
            (if (!useCharge) 2f else interpolatedCharge) * 1.5f
        }

        var motion = getLookVector(player.yRot + yawOffset, player.xRot).normalize().multiply(
            velocityMultiplier.toDouble(),
            velocityMultiplier.toDouble(),
            velocityMultiplier.toDouble()
        )

        val points = ArrayList<Vec3>()
        val hitEntities = mutableListOf<Entity>()
        var hit: BlockHitResult? = null
        var impactBox: AABB? = null
        var hitResult = false

        repeat(range + 1) {
            if (hitResult) return@repeat
            points.add(pos)

            if (!isPearl) {
                val nextPos = pos.add(motion)
                val entityBox = AABB(pos, nextPos).inflate(1.01)
                val entities = level.getEntities(player, entityBox) { entity ->
                    entity !is AbstractArrow && entity !is ArmorStand && entity.isAlive
                }
                if (entities.isNotEmpty()) {
                    hitEntities.addAll(entities)
                    hitResult = true
                    return@repeat
                }
            }

            val nextPos = pos.add(motion)
            val blockHit = level.clip(
                ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            )

            if (blockHit.type != HitResult.Type.MISS) {
                hit = blockHit
                points.add(blockHit.location)
                if (boxes) {
                    impactBox = createImpactBox(blockHit.location, boxSize)
                }
                hitResult = true
                return@repeat
            }

            pos = nextPos
            motion = if (isPearl) {
                Vec3(motion.x * 0.99, motion.y * 0.99 - 0.03, motion.z * 0.99)
            } else {
                Vec3(motion.x * 0.99, motion.y * 0.99 - 0.05, motion.z * 0.99)
            }
        }

        return TrajectoryResult(points, hit, hitEntities, impactBox)
    }

    private fun drawPlaneCollision(pose: PoseStack.Pose, buffer: VertexConsumer, hit: BlockHitResult) {
        val hitVec = hit.location
        val (minVec, maxVec) = when (hit.direction) {
            Direction.DOWN, Direction.UP ->
                hitVec.addVec(-0.15 * planeSize, -0.02, -0.15 * planeSize) to
                    hitVec.addVec(0.15 * planeSize, 0.02, 0.15 * planeSize)
            Direction.NORTH, Direction.SOUTH ->
                hitVec.addVec(-0.15 * planeSize, -0.15 * planeSize, -0.02) to
                    hitVec.addVec(0.15 * planeSize, 0.15 * planeSize, 0.02)
            Direction.WEST, Direction.EAST ->
                hitVec.addVec(-0.02, -0.15 * planeSize, -0.15 * planeSize) to
                    hitVec.addVec(0.02, 0.15 * planeSize, 0.15 * planeSize)
            else -> return
        }

        val aabb = AABB(minVec.x, minVec.y, minVec.z, maxVec.x, maxVec.y, maxVec.z)
        drawWireBox(pose, buffer, aabb, color.multiplyAlpha(0.5f))
    }

    private fun drawLineSegments(pose: PoseStack.Pose, buffer: VertexConsumer, points: List<Vec3>, color: Color) {
        if (points.size < 2) return

        val iterator = points.iterator()
        var current = iterator.next()
        while (iterator.hasNext()) {
            val next = iterator.next()
            drawLineSegment(pose, buffer, current, next, color)
            current = next
        }
    }

    private fun drawLineSegment(pose: PoseStack.Pose, buffer: VertexConsumer, from: Vec3, to: Vec3, color: Color) {
        val dx = (to.x - from.x).toFloat()
        val dy = (to.y - from.y).toFloat()
        val dz = (to.z - from.z).toFloat()

        buffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(color.redFloat, color.greenFloat, color.blueFloat, color.alphaFloat)
            .setNormal(pose, dx, dy, dz)

        buffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(color.redFloat, color.greenFloat, color.blueFloat, color.alphaFloat)
            .setNormal(pose, dx, dy, dz)
    }

    private fun drawWireBox(pose: PoseStack.Pose, buffer: VertexConsumer, aabb: AABB, color: Color) {
        val x0 = aabb.minX.toFloat()
        val y0 = aabb.minY.toFloat()
        val z0 = aabb.minZ.toFloat()
        val x1 = aabb.maxX.toFloat()
        val y1 = aabb.maxY.toFloat()
        val z1 = aabb.maxZ.toFloat()

        val corners = floatArrayOf(
            x0, y0, z0,
            x1, y0, z0,
            x1, y1, z0,
            x0, y1, z0,
            x0, y0, z1,
            x1, y0, z1,
            x1, y1, z1,
            x0, y1, z1
        )

        for (i in boxEdges.indices step 2) {
            val i0 = boxEdges[i] * 3
            val i1 = boxEdges[i + 1] * 3

            val from = Vec3(corners[i0].toDouble(), corners[i0 + 1].toDouble(), corners[i0 + 2].toDouble())
            val to = Vec3(corners[i1].toDouble(), corners[i1 + 1].toDouble(), corners[i1 + 2].toDouble())
            drawLineSegment(pose, buffer, from, to, color)
        }
    }

    private fun createImpactBox(location: Vec3, size: Float): AABB {
        val halfSize = 0.15 * size
        return AABB(
            location.x - halfSize,
            location.y - halfSize,
            location.z - halfSize,
            location.x + halfSize,
            location.y + halfSize,
            location.z + halfSize
        )
    }

    private fun getLookVector(yaw: Float, pitch: Float): Vec3 {
        val f2 = -cos(-pitch * 0.017453292f).toDouble()
        return Vec3(
            sin(-yaw * 0.017453292f - 3.1415927f) * f2,
            sin(-pitch * 0.017453292f).toDouble(),
            cos(-yaw * 0.017453292f - 3.1415927f) * f2
        )
    }

    private fun isShortbow(stack: ItemStack): Boolean = stack.itemId in shortbowIds

    private fun isBowItem(stack: ItemStack): Boolean = stack.item == Items.BOW || isShortbow(stack)

    private fun isPearlItem(stack: ItemStack): Boolean = stack.item == Items.ENDER_PEARL
}
