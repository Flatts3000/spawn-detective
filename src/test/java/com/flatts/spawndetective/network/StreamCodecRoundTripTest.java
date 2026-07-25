package com.flatts.spawndetective.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.PositionReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.SpawnRule;
import com.flatts.spawndetective.audit.Verdict;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole report crosses the network, and a serialization bug there is silent:
 * fields do not vanish loudly, they arrive shifted, and the screen renders a
 * confident answer assembled from the wrong bytes.
 *
 * <p>Round-tripping is also the only check on codec field <i>order</i>. Two
 * adjacent strings written in one order and read in another still decode, still
 * validate, and put the biome name in the dimension slot.
 */
class StreamCodecRoundTripTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
            Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    @Test
    @DisplayName("a rule result survives the wire intact")
    void ruleResultRoundTrips() {
        RuleResult original = RuleResult.fail(SpawnRule.SPAWN_RULES,
            "light 14", "fails every roll - light. block light 14, sky light 0",
            "A light source gives this block light level 14. Remove it to allow spawns.");

        ByteBuf buf = Unpooled.buffer();
        RuleResult.STREAM_CODEC.encode(buf, original);
        RuleResult decoded = RuleResult.STREAM_CODEC.decode(buf);

        assertEquals(original, decoded);
        assertEquals(0, buf.readableBytes(), "the codec left bytes behind; a later field would read garbage");
    }

    @Test
    @DisplayName("an absent remedy stays absent rather than becoming empty")
    void nullRemedySurvives() {
        // An empty remedy would render as a blank yellow line suggesting nothing.
        RuleResult original = RuleResult.pass(SpawnRule.NO_COLLISION, "fits", "the hitbox fits here");

        ByteBuf buf = Unpooled.buffer();
        RuleResult.STREAM_CODEC.encode(buf, original);
        RuleResult decoded = RuleResult.STREAM_CODEC.decode(buf);

        assertNull(decoded.remedy());
        assertEquals(SpawnRule.NO_COLLISION.remedy(), decoded.effectiveRemedy());
    }

    @Test
    @DisplayName("every rule and verdict survives its ordinal encoding")
    void everyEnumValueRoundTrips() {
        // Ordinals are safe only because both sides run the same build. Reordering
        // either enum without noticing would silently relabel every report.
        for (SpawnRule rule : SpawnRule.values()) {
            for (Verdict verdict : Verdict.values()) {
                RuleResult original = new RuleResult(rule, verdict, "v", "d", null);
                ByteBuf buf = Unpooled.buffer();
                RuleResult.STREAM_CODEC.encode(buf, original);
                RuleResult decoded = RuleResult.STREAM_CODEC.decode(buf);

                assertSame(rule, decoded.rule());
                assertSame(verdict, decoded.verdict());
            }
        }
    }

    @Test
    @DisplayName("a candidate survives, entity type included")
    void candidateRoundTrips() {
        AuditReport.Candidate original = new AuditReport.Candidate(
            EntityType.ZOMBIE, 95, 400,
            List.of(RuleResult.pass(SpawnRule.PLACEMENT, "on Stone", "standing on Stone"),
                RuleResult.fail(SpawnRule.SPAWN_RULES, "light 14", "too bright")));

        RegistryFriendlyByteBuf buf = buffer();
        AuditReport.Candidate.STREAM_CODEC.encode(buf, original);
        AuditReport.Candidate decoded = AuditReport.Candidate.STREAM_CODEC.decode(buf);

        assertSame(EntityType.ZOMBIE, decoded.type());
        assertEquals(95, decoded.weight());
        assertEquals(400, decoded.totalWeight());
        assertEquals(original.rules(), decoded.rules());
    }

    @Test
    @DisplayName("rule order survives, because order is what names the first cause")
    void candidateRuleOrderSurvives() {
        // The screen reads blocker() off the decoded list, and blocker() returns the
        // FIRST blocking rule. A codec that preserved the set but not the sequence
        // would decode cleanly and then report the second reason a spawn failed as
        // though it were the first - the caps, which now lead this list, would end up
        // behind the per-mob gates they are checked before.
        AuditReport.Candidate original = new AuditReport.Candidate(
            EntityType.ZOMBIE, 0, 0,
            List.of(RuleResult.skipped(SpawnRule.CATEGORY_GLOBAL_CAP, "no spawnable chunks"),
                RuleResult.pass(SpawnRule.CATEGORY_LOCAL_CAP, "under 70", "a player is under their cap"),
                RuleResult.pass(SpawnRule.TYPE_SUMMONABLE, "yes", "summonable"),
                RuleResult.fail(SpawnRule.PLACEMENT, "floor: Air", "the floor is Air"),
                RuleResult.marginal(SpawnRule.SPAWN_RULES, "light 7 (14% of rolls)", "9 of 64 pass", null)));

        RegistryFriendlyByteBuf buf = buffer();
        AuditReport.Candidate.STREAM_CODEC.encode(buf, original);
        AuditReport.Candidate decoded = AuditReport.Candidate.STREAM_CODEC.decode(buf);

        assertEquals(original.rules(), decoded.rules());
        assertEquals(original.rules().stream().map(RuleResult::rule).toList(),
            decoded.rules().stream().map(RuleResult::rule).toList());
        assertSame(SpawnRule.PLACEMENT, decoded.blocker().orElseThrow().rule(),
            "the first blocking rule must survive as the first blocking rule");
    }

    @Test
    @DisplayName("a position report survives, fields in their own slots")
    void positionReportRoundTrips() {
        PositionReport original = new PositionReport(
            "minecraft:the_nether", new BlockPos(-1, 39, -97), "minecraft:sparse_jungle",
            List.of(RuleResult.pass(SpawnRule.WORLD_BORDER, "inside", "inside the world border")),
            List.of(EntityType.ZOMBIE, EntityType.SKELETON));

        RegistryFriendlyByteBuf buf = buffer();
        PositionReport.STREAM_CODEC.encode(buf, original);
        PositionReport decoded = PositionReport.STREAM_CODEC.decode(buf);

        // Asserted individually: the two strings are adjacent and swapping them would
        // still decode cleanly, just with the biome shown as the dimension.
        assertEquals("minecraft:the_nether", decoded.dimension());
        assertEquals("minecraft:sparse_jungle", decoded.biome());
        assertEquals(original.pos(), decoded.pos());
        assertEquals(original.world(), decoded.world());
        assertEquals(original.suggestions(), decoded.suggestions());
    }

    @Test
    @DisplayName("an empty report round-trips without special-casing")
    void emptyReportRoundTrips() {
        PositionReport original = new PositionReport("d", BlockPos.ZERO, "b", List.of(), List.of());

        RegistryFriendlyByteBuf buf = buffer();
        PositionReport.STREAM_CODEC.encode(buf, original);

        assertEquals(original, PositionReport.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("a full aggregate report survives nesting")
    void auditReportRoundTrips() {
        AuditReport original = new AuditReport("minecraft:overworld", new BlockPos(8, 70, -3), "minecraft:plains",
            List.of(RuleResult.fail(SpawnRule.PLAYER_DISTANCE, "1 block / 24", "too close")),
            List.of(new AuditReport.Category(MobCategory.MONSTER,
                List.of(RuleResult.pass(SpawnRule.CATEGORY_GLOBAL_CAP, "6 / 70", "under the cap")),
                List.of(new AuditReport.Candidate(EntityType.CREEPER, 100, 400, List.of())))));

        RegistryFriendlyByteBuf buf = buffer();
        AuditReport.STREAM_CODEC.encode(buf, original);
        AuditReport decoded = AuditReport.STREAM_CODEC.decode(buf);

        assertEquals(original, decoded);
        assertTrue(buf.readableBytes() == 0);
    }

    @Test
    @DisplayName("a long detail string is not truncated on the wire")
    void longDetailSurvives() {
        // Details carry full sentences with measurements; a default string cap would
        // clip exactly the part that makes an answer actionable.
        String detail = "fails every roll - light. block light 14 (monster limit 0), sky light 15, "
            + "effective light 15, which is well above anything this mob will accept";
        RuleResult original = RuleResult.fail(SpawnRule.SPAWN_RULES, "light 15", detail);

        ByteBuf buf = Unpooled.buffer();
        RuleResult.STREAM_CODEC.encode(buf, original);

        assertEquals(detail, RuleResult.STREAM_CODEC.decode(buf).detail());
    }
}
