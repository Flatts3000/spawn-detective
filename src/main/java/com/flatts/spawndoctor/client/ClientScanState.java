package com.flatts.spawndoctor.client;

import com.flatts.spawndoctor.audit.AreaScanner;
import com.flatts.spawndoctor.audit.SpawnGrade;
import com.flatts.spawndoctor.network.ScanResultPayload;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of the last scan, plus the overlay on/off switch.
 *
 * <p>Deliberately a plain static holder: there is exactly one local player and one
 * overlay, and the render and tick paths both run on the client thread, so anything
 * more elaborate would be ceremony. {@code volatile} covers the one cross-thread
 * hop - the payload handler enqueues onto the client thread, but the field is read
 * from the render path.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientScanState {

    private static volatile @Nullable ScanResultPayload current;
    private static boolean enabled;

    private ClientScanState() {
    }

    public static void accept(ScanResultPayload payload) {
        current = payload;
    }

    public static @Nullable ScanResultPayload current() {
        return current;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Toggle the overlay. Turning it off drops the grid so stale data cannot be drawn. */
    public static boolean toggle() {
        enabled = !enabled;
        if (!enabled) {
            current = null;
        }
        return enabled;
    }

    public static void clear() {
        enabled = false;
        current = null;
    }

    /** The grade at a world position, or {@link SpawnGrade#NONE} if it is outside the scan. */
    public static SpawnGrade gradeAt(ScanResultPayload scan, BlockPos pos) {
        int dx = pos.getX() - (scan.center().getX() - scan.radiusXZ());
        int dy = pos.getY() - (scan.center().getY() - scan.radiusY());
        int dz = pos.getZ() - (scan.center().getZ() - scan.radiusXZ());
        if (dx < 0 || dz < 0 || dy < 0 || dx >= scan.spanXZ() || dz >= scan.spanXZ() || dy >= scan.spanY()) {
            return SpawnGrade.NONE;
        }
        return SpawnGrade.byId(scan.grid()[AreaScanner.index(dx, dy, dz, scan.spanXZ(), scan.spanY())]);
    }
}
