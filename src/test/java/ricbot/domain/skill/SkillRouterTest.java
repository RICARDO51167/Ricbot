package ricbot.domain.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillRoutingContext;
import ricbot.domain.skill.SkillsLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SkillRouterTest {

    @Test
    void selectAndRender_selectsByChannelToolsKeywords_andRendersVars(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("base"));
        Files.createDirectories(skillsDir.resolve("java"));
        Files.createDirectories(skillsDir.resolve("qq"));

        Files.writeString(skillsDir.resolve("base").resolve("SKILL.md"), """
                ---
                always: true
                priority: 100
                ---
                Base rules. channel={{channel}} tools={{tool_names}}
                """);

        Files.writeString(skillsDir.resolve("java").resolve("SKILL.md"), """
                ---
                priority: 10
                channels: cli
                tools: exec
                keywords: maven, java
                ---
                Java helper. workspace={{workspace}}
                """);

        Files.writeString(skillsDir.resolve("qq").resolve("SKILL.md"), """
                ---
                channels: qq
                ---
                QQ only
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 2, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please use maven to build",
                List.of("exec", "read_file"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertTrue(result.renderedContext().contains("## Skill: base"), result.renderedContext());
        assertTrue(result.renderedContext().contains("## Skill: java"), result.renderedContext());
        assertFalse(result.renderedContext().contains("## Skill: qq"), result.renderedContext());

        assertTrue(result.renderedContext().contains("channel=cli"), result.renderedContext());
        assertTrue(result.renderedContext().contains("tools=exec, read_file"), result.renderedContext());
        assertTrue(result.renderedContext().contains("workspace=" + workspace.toString()), result.renderedContext());
    }

    @Test
    void selectAndRender_onlyAlwaysWhenNoMatch(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("base"));
        Files.createDirectories(skillsDir.resolve("nomatch"));

        Files.writeString(skillsDir.resolve("base").resolve("SKILL.md"), """
                ---
                always: true
                ---
                Base rules.
                """);

        Files.writeString(skillsDir.resolve("nomatch").resolve("SKILL.md"), """
                ---
                channels: qq
                keywords: hello
                ---
                Should not be selected.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 3, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "nothing relevant",
                List.of("read_file"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertTrue(result.renderedContext().contains("## Skill: base"), result.renderedContext());
        assertFalse(result.renderedContext().contains("## Skill: nomatch"), result.renderedContext());
    }

    @Test
    void selectAndRender_prefersHigherPriority(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("base"));
        Files.createDirectories(skillsDir.resolve("low"));
        Files.createDirectories(skillsDir.resolve("high"));

        Files.writeString(skillsDir.resolve("base").resolve("SKILL.md"), """
                ---
                always: true
                ---
                Base rules.
                """);

        Files.writeString(skillsDir.resolve("low").resolve("SKILL.md"), """
                ---
                priority: 1
                keywords: build
                ---
                LOW
                """);

        Files.writeString(skillsDir.resolve("high").resolve("SKILL.md"), """
                ---
                priority: 50
                keywords: build
                ---
                HIGH
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 1, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please build the project",
                List.of("exec"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertTrue(result.renderedContext().contains("HIGH"), result.renderedContext());
        assertFalse(result.renderedContext().contains("LOW"), result.renderedContext());
    }

    @Test
    void selectAndRender_supportsYamlAndJsonFrontmatterLists(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("base"));
        Files.createDirectories(skillsDir.resolve("deploy"));

        Files.writeString(skillsDir.resolve("base").resolve("SKILL.md"), """
                ---
                always: true
                ---
                Base rules.
                """);

        Files.writeString(skillsDir.resolve("deploy").resolve("SKILL.md"), """
                ---
                channels:
                  - cli
                tools:
                  - exec
                keywords: ["deploy", "release"]
                ---
                Deploy helper.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 2, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please deploy this release",
                List.of("exec"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertTrue(result.renderedContext().contains("## Skill: deploy"), result.renderedContext());
    }

    @Test
    void selectAndRender_skipsDisabledSkills(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("disabled-always"));
        Files.createDirectories(skillsDir.resolve("disabled-match"));
        Files.createDirectories(skillsDir.resolve("enabled"));

        Files.writeString(skillsDir.resolve("disabled-always").resolve("SKILL.md"), """
                ---
                always: true
                ---
                Disabled always body.
                """);

        Files.writeString(skillsDir.resolve("disabled-match").resolve("SKILL.md"), """
                ---
                keywords: build
                ---
                Disabled keyword body.
                """);

        Files.writeString(skillsDir.resolve("enabled").resolve("SKILL.md"), """
                ---
                keywords: build
                ---
                Enabled body.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of("DISABLED-ALWAYS", "disabled-match"));
        SkillRouter router = new SkillRouter(loader, 3, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please build",
                List.of("exec"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertFalse(result.renderedContext().contains("Disabled always body"), result.renderedContext());
        assertFalse(result.renderedContext().contains("Disabled keyword body"), result.renderedContext());
        assertTrue(result.renderedContext().contains("Enabled body"), result.renderedContext());
    }

    @Test
    void selectAndRender_selectsBuiltinStyleChineseKeywords(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("weather"));

        Files.writeString(skillsDir.resolve("weather").resolve("SKILL.md"), """
                ---
                name: weather
                description: 获取当前天气与预报。
                keywords: weather, 天气, 预报
                ---
                Weather helper.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 3, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "帮我查一下上海天气",
                List.of("exec"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertTrue(result.renderedContext().contains("Weather helper."), result.renderedContext());
    }

    @Test
    void selectAndRender_skipsSkillsWithMissingRequiredBins(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("missing-bin"));
        Files.createDirectories(skillsDir.resolve("missing-env"));
        Files.createDirectories(skillsDir.resolve("plain"));

        Files.writeString(skillsDir.resolve("missing-bin").resolve("SKILL.md"), """
                ---
                keywords: deploy
                metadata: {"ricbot":{"requires":{"bins":["definitely_missing_ricbot_bin"]}}}
                ---
                Missing bin body.
                """);

        Files.writeString(skillsDir.resolve("missing-env").resolve("SKILL.md"), """
                ---
                keywords: deploy
                metadata: {"ricbot":{"requires":{"env":["DEFINITELY_MISSING_RICBOT_ENV"]}}}
                ---
                Missing env body.
                """);

        Files.writeString(skillsDir.resolve("plain").resolve("SKILL.md"), """
                ---
                keywords: deploy
                ---
                Plain body.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 3, 10000);

        SkillRoutingContext ctx = new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please deploy",
                List.of("exec"),
                Map.of(),
                Map.of()
        );

        SkillRouter.SelectionResult result = router.selectAndRender(ctx);
        assertFalse(result.renderedContext().contains("Missing bin body"), result.renderedContext());
        assertFalse(result.renderedContext().contains("Missing env body"), result.renderedContext());
        assertTrue(result.renderedContext().contains("Plain body."), result.renderedContext());

        List<Map<String, String>> all = loader.listSkills(false);
        Map<String, String> missing = all.stream()
                .filter(row -> "missing-bin".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("true", missing.get("unavailable"));
        assertEquals("definitely_missing_ricbot_bin", missing.get("missing_bins"));
        Map<String, String> missingEnv = all.stream()
                .filter(row -> "missing-env".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("true", missingEnv.get("unavailable"));
        assertEquals("DEFINITELY_MISSING_RICBOT_ENV", missingEnv.get("missing_env"));
        assertFalse(loader.listSkills(true).stream().anyMatch(row -> "missing-bin".equals(row.get("name"))));
        assertFalse(loader.listSkills(true).stream().anyMatch(row -> "missing-env".equals(row.get("name"))));
    }

    @Test
    void selectAndRender_supportsExplicitSkillTrigger(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("demo"));

        Files.writeString(skillsDir.resolve("demo").resolve("SKILL.md"), """
                ---
                keywords: unrelated
                ---
                Demo body.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 1, 10000);

        SkillRouter.SelectionResult result = router.selectAndRender(new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please use $demo",
                List.of(),
                Map.of(),
                Map.of()
        ));

        assertTrue(result.renderedContext().contains("Demo body."), result.renderedContext());
        assertTrue(result.decisions().stream()
                .anyMatch(d -> "demo".equals(d.name()) && d.reasons().stream().anyMatch(r -> r.contains("explicit trigger"))));
    }

    @Test
    void skillsSummary_includesContractFields(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("contract"));
        Files.writeString(skillsDir.resolve("contract").resolve("SKILL.md"), """
                ---
                description: Contract skill.
                metadata: |
                  {"ricbot":{"version":"2.0.0","risk":"high","permissions":["network","write"],"tools":["web_fetch"],"requires":{"bins":["definitely_missing_contract_bin"],"env":["DEFINITELY_MISSING_CONTRACT_ENV"]}}}
                ---
                Contract body.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        String summary = loader.buildSkillsSummary();

        assertTrue(summary.contains("name=\"contract\""), summary);
        assertTrue(summary.contains("version=\"2.0.0\""), summary);
        assertTrue(summary.contains("risk=\"high\""), summary);
        assertTrue(summary.contains("permissions=\"network,write\""), summary);
        assertTrue(summary.contains("tools=\"web_fetch\""), summary);
        assertTrue(summary.contains("missing_bins=\"definitely_missing_contract_bin\""), summary);
        assertTrue(summary.contains("missing_env=\"DEFINITELY_MISSING_CONTRACT_ENV\""), summary);
    }

    @Test
    void selectAndRender_truncatesOversizedSkillInsteadOfDroppingIt(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("large"));

        Files.writeString(skillsDir.resolve("large").resolve("SKILL.md"), """
                ---
                keywords: large
                ---
                %s
                """.formatted("x".repeat(500)));

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        SkillRouter router = new SkillRouter(loader, 1, 160);

        SkillRouter.SelectionResult result = router.selectAndRender(new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "large",
                List.of(),
                Map.of(),
                Map.of()
        ));

        assertTrue(result.renderedContext().contains("## Skill: large"), result.renderedContext());
        assertTrue(result.renderedContext().contains("[skill truncated]"), result.renderedContext());
        assertTrue(result.selectedSkills().contains("large"));
    }

    @Test
    void generatedMarkdownSkillsAreLoadedAndRoutedByKeywords(@TempDir Path workspace) throws Exception {
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("base"));
        Files.createDirectories(skillsDir.resolve("generated"));

        Files.writeString(skillsDir.resolve("base").resolve("SKILL.md"), """
                ---
                always: true
                ---
                Base rules.
                """);

        Files.writeString(skillsDir.resolve("generated").resolve("filesystem-safety-rule.md"), """
                ---
                name: filesystem-safety-rule
                source: manual
                priority: 70
                keywords:
                  - filesystem-safety
                  - writefile
                channels:
                  - cli
                ---
                Generated filesystem safety body.
                """);

        SkillsLoader loader = new SkillsLoader(workspace, skillsDir, Set.of());
        assertTrue(loader.listSkills(false).stream()
                .anyMatch(row -> "filesystem-safety-rule".equals(row.get("name")) && "generated".equals(row.get("source"))));

        SkillRouter router = new SkillRouter(loader, 2, 10000);
        SkillRouter.SelectionResult result = router.selectAndRender(new SkillRoutingContext(
                workspace,
                "cli",
                "c1",
                "please apply filesystem-safety before writefile changes",
                List.of("read_file"),
                Map.of(),
                Map.of()
        ));

        assertTrue(result.renderedContext().contains("Generated filesystem safety body."), result.renderedContext());
        assertTrue(result.selectedSkills().contains("filesystem-safety-rule"), result.selectedSkills().toString());
    }
}
