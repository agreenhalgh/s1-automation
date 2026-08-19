package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A submitted bot definition may spell out {@code null} for scalars it does not set —
 * hand-written and generated JSON both do. Jackson 3 rejects null for a primitive by
 * default, so {@code spring.jackson.deserialization.fail-on-null-for-primitives=false}
 * restores the coercion the definition records are written for (their compact
 * constructors normalise the resulting 0/false to sensible defaults).
 *
 * <p>Uses the application's autoconfigured mapper — the same one the REST layer and the
 * worker's job listener use — so this pins the configuration, not just the records.</p>
 */
@SpringBootTest
class BotDefinitionJsonTest {

    @Autowired
    ObjectMapper mapper;

    private static final String NULLS_FOR_OPTIONAL_SCALARS = """
            {
              "botId": "nulls",
              "name": "Explicit nulls",
              "version": 1,
              "s1Version": "2024",
              "globalTimeoutMs": null,
              "defaultRetry": null,
              "steps": [
                {
                  "index": 0,
                  "name": "Click Search",
                  "action": "CLICK",
                  "inputValue": null,
                  "outputKey": null,
                  "timeoutMs": null,
                  "retry": null,
                  "continueOnFailure": null,
                  "clickOffsetX": null,
                  "clickOffsetY": null,
                  "locatorChain": [
                    { "type": "IMAGE", "templateRef": "search-button", "threshold": 0.92 }
                  ]
                }
              ]
            }
            """;

    @Test
    void acceptsExplicitNullsForOptionalScalars() {
        BotDefinition bot = mapper.readValue(NULLS_FOR_OPTIONAL_SCALARS, BotDefinition.class);

        assertEquals("nulls", bot.botId());
        assertEquals(0, bot.globalTimeoutMs(), "null globalTimeoutMs means no ceiling");
        assertEquals(RetryPolicyDef.defaults(), bot.defaultRetry(), "null defaultRetry falls back");

        StepDefinition step = bot.steps().getFirst();
        assertFalse(step.continueOnFailure(), "null continueOnFailure defaults to false");
        assertEquals(10_000, step.timeoutMs(), "null timeoutMs falls back to the 10s default");
        assertEquals(null, step.clickOffsetX(), "boxed offsets stay null when unset");
    }

    static Stream<Path> exampleBots() throws IOException {
        try (var files = Files.list(Path.of("bots"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).toList().stream();
        }
    }

    /**
     * The worked examples in {@code bots/} are the reference authors copy from, and
     * BOT-DEFINITIONS.md claims they all parse. Spring's mapper ignores unknown fields,
     * so a misspelt key in an example would be copied along silently (see the test
     * below) — parse them with FAIL_ON_UNKNOWN_PROPERTIES on to catch that here.
     */
    @ParameterizedTest(name = "{0} parses with no unknown fields")
    @MethodSource("exampleBots")
    void exampleBotsParseStrictly(Path file) throws IOException {
        // The app's mapper, plus unknown-field strictness: absent scalars must still
        // coerce the way application.yaml configures them, or every example "fails" on
        // an omitted continueOnFailure rather than on a real problem.
        tools.jackson.databind.ObjectMapper strict = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        BotDefinition bot = strict.readValue(Files.readString(file), BotDefinition.class);

        assertNotNull(bot.botId(), "botId is required");
        assertFalse(bot.steps().isEmpty(), "a bot with no steps does nothing");
        for (StepDefinition step : bot.steps()) {
            assertNotNull(step.action(), "step '" + step.name() + "' has no action");
            if (step.action() != ActionType.ACTIVATE) {
                // ACTIVATE targets a window, so a chain is optional there; for every
                // other action an empty chain is a step that can never resolve.
                assertFalse(step.locatorChain().isEmpty(),
                        "step '" + step.name() + "' has no locators, so it can never resolve");
            }
        }
    }

    @Test
    void theActivateExampleTargetsAWindowRatherThanAnElement() throws IOException {
        BotDefinition bot = mapper.readValue(
                Files.readString(Path.of("bots/activate-window.json")), BotDefinition.class);

        StepDefinition first = bot.steps().getFirst();
        assertEquals(ActionType.ACTIVATE, first.action());
        assertEquals("activatedWindow", first.outputKey(), "the activated title is recorded");
        assertTrue(first.locatorChain().isEmpty(),
                "ACTIVATE needs no locator; omitting the key must leave an empty chain");

        StepDefinition child = bot.steps().get(2);
        assertTrue(child.inputValue().contains("Appointment Book"),
                "inputValue names the window to activate");

        StepDefinition gated = bot.steps().get(3);
        assertEquals(List.of(LocatorType.JAB),
                gated.locatorChain().stream().map(LocatorDefinition::type).toList(),
                "a chain on an ACTIVATE step is still allowed, as a gate");
    }

    /**
     * Documents a sharp edge rather than endorsing it: Spring Boot disables
     * FAIL_ON_UNKNOWN_PROPERTIES, so a misspelt step field is dropped instead of
     * rejected — the bot registers and the intended offset simply never happens.
     */
    @Test
    void silentlyIgnoresAMisspeltField() {
        String typo = """
                {"botId":"typo","name":"Typo","version":1,"steps":[
                  {"index":0,"name":"Click","action":"CLICK","clickOffserX":40,
                   "locatorChain":[{"type":"IMAGE","templateRef":"t","threshold":0.9}]}]}
                """;

        BotDefinition bot = mapper.readValue(typo, BotDefinition.class);

        assertEquals(null, bot.steps().getFirst().clickOffsetX(),
                "the typo'd offset is not applied — and nothing warns about it");
    }
}
