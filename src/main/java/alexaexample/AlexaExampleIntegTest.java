package alexaexample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jag.yaml.YamlHelper;
import jag.yaml.jackson.JacksonBasedYamlHelper;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AlexaExampleIntegTest {
    private static final Logger LOGGER = LogManager.getLogger(AlexaExampleIntegTest.class);
    private static final String SKILL_ID = "amzn1.ask.skill.6abf494d-3957-49a4-85f4-9039869fe7d0";
    private static final String STAGE = "development";
    private static final String LOCALE = "en-US";
    private static final String PROFILE = "default";
    private static final YamlHelper yamlHelper = JacksonBasedYamlHelper.instance();

    private final AlexaSkillManagementClient skillManagementClient = AlexaSkillManagementClient.builder()
            .skillId(SKILL_ID)
            .locale(LOCALE)
            .stage(STAGE)
            .profile(PROFILE)
            .build();

    @ParameterizedTest
    @MethodSource("createTestSuites")
    public void shouldRunIntegTest(TestSuite testSuite) {
        LOGGER.info("Preparing to run test suite: " + testSuite.getName());
        testSuite.forEachTestCase(testsCases -> {
            testsCases.forEach(testCase -> {
                AlexaSkillManagementClient.SimulateSkillResponse simulateSkillResponse = skillManagementClient.simulateSkill(testCase.getInput());
                if (simulateSkillResponse.getStatus().equals(AlexaSkillManagementClient.SimulateSkillResponse.SUCCESSFUL)) {
                    assertEquals(testCase.getOutput(), simulateSkillResponse.getResult());
                } else {
                    fail("Test case failed with status: " + simulateSkillResponse.getStatus());
                }
            });
        });
    }

    private static Stream<TestSuite> createTestSuites() {
        try {
            Set<Path> paths = Files.list(Paths.get("src/main/java/alexaexample/data/")).collect(Collectors.toSet());
            Set<TestSuite> testSuites = new HashSet<>();
            for (Path path : paths) {
                System.out.println(System.getProperty("user.dir"));
                testSuites.add(yamlHelper.fromYaml(new String(Files.readAllBytes(path.toAbsolutePath())), TestSuite.class));
            }
            return testSuites.stream();
        } catch (IOException e) {
            throw new RuntimeException("Failed while creating test cases", e);
        }
    }

    @NoArgsConstructor
    @Setter
    private static class TestSuite {
        @Getter
        private String name;
        private List<TestCase> testCases;

        void forEachTestCase(Consumer<Stream<TestCase>> consumer) {
            consumer.accept(testCases.stream());
        }
    }

    @NoArgsConstructor
    @Data
    private static class TestCase {
        private String input;
        private String output;
    }

    /**
     * AlexaSkillManagementClient
     * For now this interface is simply a wrapper around the ask cli
     * TODO: migrate to using SMAPI
     */
    private static class AlexaSkillManagementClient {
        private static final Logger LOG = LogManager.getLogger(AlexaSkillManagementClient.class);
        private static final Pattern GET_STATUS_PATTERN = Pattern.compile("\"status\": \"([A-Z]*)\"");
        private static final Pattern GET_CAPTION_PATTERN = Pattern.compile("\"caption\": \"(.*)\"");

        @NonNull
        private final String locale;
        @NonNull
        private final String stage;
        @NonNull
        private final String  profile;
        @NonNull
        private final String  skillId;

        @Builder
        public AlexaSkillManagementClient(String locale, String stage, String profile, String skillId) {
            this.locale = locale;
            this.stage = stage;
            this.profile = profile;
            this.skillId = skillId;
            LOG.info(String.format("Creating skill management client using locale %s, stage %s, profile %s, and skill-id :%s", locale, stage, profile, skillId));
        }

        public SimulateSkillResponse simulateSkill(String text) {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(Arrays.asList("ask", "simulate", "--locale",  locale,  "--stage",  stage,  "--profile",  profile, "--skill-id", skillId, "--text", String.format( "\"%s\"",
                    text)));
            try {
                Process start = processBuilder.start();
                int exitCode = start.waitFor();
                if (exitCode == 0) {
                    String output = IOUtils.toString(start.getInputStream(), StandardCharsets.UTF_8);
                    Matcher statusMatcher = GET_STATUS_PATTERN.matcher(output);
                    Matcher captionMatcher = GET_CAPTION_PATTERN.matcher(output);
                    if (statusMatcher.find() && captionMatcher.find()) {
                        return SimulateSkillResponse.builder()
                                .status(statusMatcher.group(1))
                                .result(captionMatcher.group(1))
                                .build();
                    }
                    throw new IllegalStateException("Missing required fields in response.  Expected (status, caption). \n" + output);
                }
                throw new RuntimeException("Failed while simulating skill with exit code: " + exitCode + "\n" + IOUtils.toString(start.getErrorStream(), StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException e) {
               throw new RuntimeException("Failed while simulating skill with exception: " + e, e);
            }
        }

        @Getter
        @Builder
        public static class SimulateSkillResponse {
            static String SUCCESSFUL = "SUCCESSFUL";
            private final String status;
            private final String result;
        }
    }
}