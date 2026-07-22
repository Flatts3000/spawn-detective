package com.flatts.spawndetective;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every translation key the code asks for must exist in the language file.
 *
 * <p>A missing key does not throw. Minecraft renders the raw key, so the player
 * sees {@code spawndetective.anchor.elsewhere} where a sentence should be - and it only
 * shows up on the code path nobody tested by hand, which for this mod is usually the
 * error path a confused player is already on.
 */
class LangCompletenessTest {

    private static final Pattern TRANSLATABLE =
        Pattern.compile("Component\\.translatable\\(\\s*\"(spawndetective[^\"]*)\"");

    /** Keys built by the game from a registry id rather than written in source. */
    private static final Set<String> GENERATED_PREFIXES = Set.of("item.spawndetective.", "config.jade.");

    @Test
    @DisplayName("every translatable key used in source exists in en_us.json")
    void everyKeyExists() {
        Path sources = Paths.get("src/main/java");
        Assumptions.assumeTrue(Files.isDirectory(sources),
            "sources not on disk (running from a jar); nothing to scan");

        JsonObject lang = lang();
        List<String> missing = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                Matcher matcher = TRANSLATABLE.matcher(read(path));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!lang.has(key)) {
                        missing.add(key + "  (" + path.getFileName() + ")");
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertTrue(missing.isEmpty(),
            "translation keys used in code but absent from en_us.json:\n  " + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("no key is defined and then never used")
    void noOrphanKeys() {
        Path sources = Paths.get("src/main/java");
        Assumptions.assumeTrue(Files.isDirectory(sources), "sources not on disk");

        StringBuilder allSource = new StringBuilder();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> allSource.append(read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Set<String> orphans = new TreeSet<>();
        for (String key : lang().keySet()) {
            boolean generated = GENERATED_PREFIXES.stream().anyMatch(key::startsWith);
            if (!generated && !allSource.toString().contains("\"" + key + "\"")) {
                orphans.add(key);
            }
        }

        // Orphans are usually the residue of a rename: the new key was added, the old
        // one left behind, and now two sentences claim to be the same message.
        assertTrue(orphans.isEmpty(), "lang keys nothing references: " + orphans);
    }

    @Test
    @DisplayName("player-facing copy uses no em-dashes or en-dashes")
    void noFancyDashes() {
        List<String> offenders = new ArrayList<>();
        lang().entrySet().forEach(entry -> {
            String value = entry.getValue().getAsString();
            if (value.indexOf('—') >= 0 || value.indexOf('–') >= 0) {
                offenders.add(entry.getKey());
            }
        });

        assertTrue(offenders.isEmpty(), "em/en-dashes in shipped copy: " + offenders);
    }

    private static JsonObject lang() {
        try {
            Path path = Paths.get(LangCompletenessTest.class
                .getResource("/assets/spawndetective/lang/en_us.json").toURI());
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("could not read en_us.json from the test classpath", e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
