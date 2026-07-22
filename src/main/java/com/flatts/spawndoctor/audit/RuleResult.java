package com.flatts.spawndoctor.audit;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * One rule's verdict at one position, with the numbers behind it.
 *
 * <p>Three fields rather than one, because a report is read at two distances and
 * they want different text:
 *
 * <ul>
 *   <li>{@code value} is the <b>measurement</b>, scanned in a table column beside
 *       the rule's title: {@code "0.9 blocks"}, {@code "6 / 70"}, {@code "Air"}.
 *       It must never restate the title - the first build rendered
 *       "Inside world border | inside the world border", which spent the whole
 *       column saying nothing and then truncated.</li>
 *   <li>{@code detail} is the <b>sentence</b>, read on hover or in chat, where
 *       there is room to explain.</li>
 *   <li>{@code remedy} overrides the rule's generic advice when the auditor has
 *       worked out something specific. {@link SpawnRule#SPAWN_RULES} can fail for
 *       six different reasons; once the cause is known, listing all six is worse
 *       than useless.</li>
 * </ul>
 */
public record RuleResult(SpawnRule rule, Verdict verdict, String value, String detail, @Nullable String remedy) {

    public static final StreamCodec<ByteBuf, RuleResult> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT.map(i -> SpawnRule.values()[i], SpawnRule::ordinal), RuleResult::rule,
        ByteBufCodecs.VAR_INT.map(i -> Verdict.values()[i], Verdict::ordinal), RuleResult::verdict,
        ByteBufCodecs.STRING_UTF8, RuleResult::value,
        ByteBufCodecs.STRING_UTF8, RuleResult::detail,
        ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs::optional), RuleResult::optionalRemedy,
        RuleResult::fromNetwork);

    private java.util.Optional<String> optionalRemedy() {
        return java.util.Optional.ofNullable(this.remedy);
    }

    private static RuleResult fromNetwork(
        SpawnRule rule, Verdict verdict, String value, String detail, java.util.Optional<String> remedy
    ) {
        return new RuleResult(rule, verdict, value, detail, remedy.orElse(null));
    }

    public static RuleResult pass(SpawnRule rule, String value, String detail) {
        return new RuleResult(rule, Verdict.PASS, value, detail, null);
    }

    public static RuleResult fail(SpawnRule rule, String value, String detail) {
        return new RuleResult(rule, Verdict.FAIL, value, detail, null);
    }

    public static RuleResult fail(SpawnRule rule, String value, String detail, @Nullable String remedy) {
        return new RuleResult(rule, Verdict.FAIL, value, detail, remedy);
    }

    public static RuleResult marginal(SpawnRule rule, String value, String detail, @Nullable String remedy) {
        return new RuleResult(rule, Verdict.MARGINAL, value, detail, remedy);
    }

    public static RuleResult unknown(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.UNKNOWN, "unknown", detail, null);
    }

    public static RuleResult skipped(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.SKIPPED, "n/a", detail, null);
    }

    /** PASS/FAIL from a boolean, with a measurement and sentence for each branch. */
    public static RuleResult of(
        SpawnRule rule, boolean ok, String passValue, String passDetail, String failValue, String failDetail
    ) {
        return ok ? pass(rule, passValue, passDetail) : fail(rule, failValue, failDetail);
    }

    /** The advice to show: the auditor's specific finding, else the rule's generic one. */
    public @Nullable String effectiveRemedy() {
        return this.remedy != null ? this.remedy : this.rule.remedy();
    }

    /** Short form for a table column - the measurement, already written to be terse. */
    public String summary() {
        return this.value;
    }
}
