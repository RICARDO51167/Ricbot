package ricbot.core.skill;

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
}
