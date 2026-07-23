package com.flatts.spawndetective.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * In-world GameTest registrar. Run with {@code ./gradlew runGameTestServer}.
 *
 * <p>This mod's whole value is that its answers are <i>correct</i>, so the tests
 * are not optional polish: each one builds a situation with exactly one known
 * cause and asserts the auditor names that cause. A regression here means the mod
 * is confidently wrong, which is worse than not shipping it.
 *
 * <p><b>1.21.1 registration form.</b> A test is a {@code public static void
 * name(GameTestHelper)} carrying {@link net.minecraft.gametest.framework.GameTest},
 * declared in a class tagged {@code @GameTestHolder}. All this file does is hand
 * those classes to the game; the metadata lives on the methods themselves.
 *
 * <p>On {@code main} (26.1) the annotation form no longer exists, and the same
 * tests register as bodies in {@code Registries.TEST_FUNCTION} paired with a
 * {@code TestData}. That is why this file looks nothing like its counterpart
 * there, and why the test <i>bodies</i> are deliberately kept identical between
 * the branches: a fix and its regression test then cherry-pick cleanly, and the
 * registrar is the only thing that has to differ.
 *
 * <p>One consequence worth knowing when running a single test: ids here carry the
 * declaring class, so it is
 * {@code /test run spawndetective:worldruletests.gamerule_off_blocks_everything}
 * rather than main's {@code spawndetective:gamerule_off_blocks_everything}.
 * {@code /test run spawndetective:*} runs all of them on either branch.
 */
public final class SDGameTests {

    private SDGameTests() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(SDGameTests::onRegisterGameTests);
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(SpawnAuditTests.class);
        event.register(AuditRobustnessTests.class);
        event.register(PlacementRuleTests.class);
        event.register(WorldRuleTests.class);
        event.register(PerformanceTests.class);
    }
}
