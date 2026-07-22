package com.flatts.spawndetective.gametest;

import com.flatts.spawndetective.SpawnDetective;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * In-world GameTest registrar. Run with {@code ./gradlew runGameTestServer}.
 *
 * <p>This mod's whole value is that its answers are <i>correct</i>, so the tests
 * are not optional polish: each one builds a situation with exactly one known
 * cause and asserts the auditor names that cause. A regression here means the mod
 * is confidently wrong, which is worse than not shipping it.
 *
 * <p>26.1 registration form: a test is its body (a {@code Consumer<GameTestHelper>}
 * in {@link Registries#TEST_FUNCTION}) plus its metadata (a {@link TestData} carried
 * by a {@link FunctionGameTestInstance}). The annotation form no longer exists.
 */
public final class SDGameTests {

    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
        DeferredRegister.create(Registries.TEST_FUNCTION, SpawnDetective.MOD_ID);

    private static final String DEFAULT_STRUCTURE = "empty_5x5x5";

    private record Spec(ResourceKey<Consumer<GameTestHelper>> fn, Identifier structure, int maxTicks) {}

    private static final List<Spec> SPECS = new ArrayList<>();

    private SDGameTests() {
    }

    /** Register one test on the shared empty plot. {@code name} must be lower_snake_case. */
    static void test(String name, int maxTicks, Consumer<GameTestHelper> body) {
        DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> holder =
            FUNCTIONS.register(name, () -> body);
        SPECS.add(new Spec(holder.getKey(),
            Identifier.fromNamespaceAndPath(SpawnDetective.MOD_ID, DEFAULT_STRUCTURE), maxTicks));
    }

    public static void register(IEventBus modEventBus) {
        SpawnAuditTests.register();
        AuditRobustnessTests.register();
        PlacementRuleTests.register();
        WorldRuleTests.register();
        PerformanceTests.register();

        FUNCTIONS.register(modEventBus);
        modEventBus.addListener(SDGameTests::onRegisterGameTests);
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(
            Identifier.fromNamespaceAndPath(SpawnDetective.MOD_ID, "default"));
        for (Spec spec : SPECS) {
            // required = true and manualOnly = false keep every test in the CI batch;
            // flipping either makes the required gameTest job silently skip it.
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                env, spec.structure(), spec.maxTicks(),
                0,              // setupTicks
                true,           // required
                Rotation.NONE,
                false,          // manualOnly
                1,              // maxAttempts
                1,              // requiredSuccesses
                false,          // skyAccess
                1);             // padding
            event.registerTest(spec.fn().identifier(), new FunctionGameTestInstance(spec.fn(), data));
        }
    }
}
