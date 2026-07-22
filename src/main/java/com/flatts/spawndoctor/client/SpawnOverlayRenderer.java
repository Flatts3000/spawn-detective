package com.flatts.spawndoctor.client;

import com.flatts.spawndoctor.audit.SpawnGrade;
import com.flatts.spawndoctor.network.ScanResultPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Paints the scan grid into the world: one flat translucent quad per graded block,
 * floating just above the floor it applies to.
 *
 * <p>A flat quad rather than a full box on purpose. Boxes stack into an opaque
 * soup in any real cave, and the thing a player needs to see through the overlay
 * is the terrain they are about to light up. A thin sheet at the mob's foot level
 * is also literally where the spawn happens, so the marker sits where the answer
 * does.
 *
 * <p>{@link SpawnGrade#SAFE} is not drawn. An overlay that paints every safe block
 * green is a wall of green - the signal is the exceptions, and the absence of a
 * marker already reads as "fine".
 */
@OnlyIn(Dist.CLIENT)
public final class SpawnOverlayRenderer {

    /** Height above the block's base to float the quad, so it does not z-fight the floor. */
    private static final float HOVER = 0.02F;

    /** Inset from the block edges, so adjacent markers read as separate cells. */
    private static final float INSET = 0.03F;

    private SpawnOverlayRenderer() {
    }

    public static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
        if (!ClientScanState.enabled()) {
            return;
        }
        ScanResultPayload scan = ClientScanState.current();
        if (scan == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();

        PoseStack poseStack = event.getPoseStack();
        event.getSubmitNodeCollector().submitCustomGeometry(
            poseStack, RenderTypes.debugQuads(), (pose, buffer) -> emit(buffer, pose, scan, camera));
    }

    private static void emit(VertexConsumer buffer, PoseStack.Pose pose, ScanResultPayload scan, Vec3 camera) {
        int originX = scan.center().getX() - scan.radiusXZ();
        int originY = scan.center().getY() - scan.radiusY();
        int originZ = scan.center().getZ() - scan.radiusXZ();

        int spanXZ = scan.spanXZ();
        int spanY = scan.spanY();
        byte[] grid = scan.grid();

        for (int dy = 0; dy < spanY; dy++) {
            for (int dx = 0; dx < spanXZ; dx++) {
                for (int dz = 0; dz < spanXZ; dz++) {
                    SpawnGrade grade = SpawnGrade.byId(
                        grid[com.flatts.spawndoctor.audit.AreaScanner.index(dx, dy, dz, spanXZ, spanY)]);
                    if (grade == SpawnGrade.NONE || grade == SpawnGrade.SAFE) {
                        continue;
                    }
                    quad(buffer, pose,
                        (float) (originX + dx - camera.x),
                        (float) (originY + dy - camera.y),
                        (float) (originZ + dz - camera.z),
                        grade.argb());
                }
            }
        }
    }

    /** One upward-facing quad at the base of the block whose corner is (x, y, z). */
    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        float x0 = x + INSET;
        float x1 = x + 1.0F - INSET;
        float z0 = z + INSET;
        float z1 = z + 1.0F - INSET;
        float yy = y + HOVER;

        // Counter-clockwise seen from above, so the quad faces up.
        buffer.addVertex(pose, x0, yy, z0).setColor(r, g, b, a);
        buffer.addVertex(pose, x0, yy, z1).setColor(r, g, b, a);
        buffer.addVertex(pose, x1, yy, z1).setColor(r, g, b, a);
        buffer.addVertex(pose, x1, yy, z0).setColor(r, g, b, a);
    }
}
