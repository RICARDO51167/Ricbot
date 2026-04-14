package ricbot.core.skill;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SkillsLoader：发现、读取、汇总技能。
 */
public class SkillsLoader {

    private static final Path DEV_BUILTIN_SKILLS_DIR =
            Path.of("src", "main", "resources", "skills").toAbsolutePath().normalize();

    private static final Pattern STRIP_SKILL_FRONTMATTER =
            Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?", Pattern.DOTALL);

    private final Path workspace;
    private final Path workspaceSkills;
    private final Path builtinSkills;
    private final Set<String> disabledSkills;

    public SkillsLoader(Path workspace, Path builtinSkillsDir, Set<String> disabledSkills) {
        this.workspace = workspace;
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkills = builtinSkillsDir != null ? builtinSkillsDir : resolveBuiltinSkillsDir();
        this.disabledSkills = disabledSkills != null ? disabledSkills : new HashSet<>();
    }

    public static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public List<Map<String, String>> listSkills(boolean filterUnavailable) {
        List<Map<String, String>> skills = skillEntriesFromDir(workspaceSkills, "workspace", null);

        Set<String> workspaceNames = new HashSet<>();
        for (Map<String, String> entry : skills) {
            workspaceNames.add(entry.get("name"));
        }

        if (Files.exists(builtinSkills)) {
            skills.addAll(skillEntriesFromDir(builtinSkills, "builtin", workspaceNames));
        }

        skills.removeIf(s -> disabledSkills.contains(s.get("name")));
        return skills;
    }

    public List<Map<String, String>> skillEntriesFromDir(
            Path base,
            String source,
            Set<String> skipNames
    ) {
        if (!Files.exists(base)) {
            return new ArrayList<>();
        }

        List<Map<String, String>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path skillDir : stream) {
                if (!Files.isDirectory(skillDir)) {
                    continue;
                }
                Path skillFile = skillDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    continue;
                }
                String name = skillDir.getFileName().toString();
                if (skipNames != null && skipNames.contains(name)) {
                    continue;
                }
                Map<String, String> row = new HashMap<>();
                row.put("name", name);
                row.put("path", skillFile.toString());
                row.put("source", source);
                entries.add(row);
            }
        } catch (IOException ignored) {
        }

        return entries;
    }

    public String loadSkill(String name) {
        for (Path root : List.of(workspaceSkills, builtinSkills)) {
            Path path = root.resolve(name).resolve("SKILL.md");
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    public String loadSkillsForContext(List<String> names) {
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            String content = loadSkill(name);
            if (content != null && !content.isBlank()) {
                parts.add("## Skill: " + name + "\n\n" + stripFrontmatter(content));
            }
        }
        return String.join("\n\n", parts);
    }

    public List<String> getAlwaysSkills() {
        // 这里先保留空列表占位；后面你可以加 frontmatter 解析
        return List.of();
    }

    public String buildSkillsSummary() {
        List<Map<String, String>> skills = listSkills(true);
        if (skills.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, String> skill : skills) {
            lines.add("- " + skill.get("name") + " (" + skill.get("source") + ")");
        }
        return String.join("\n", lines);
    }

    private String stripFrontmatter(String content) {
        return STRIP_SKILL_FRONTMATTER.matcher(content).replaceFirst("");
    }

    private static Path resolveBuiltinSkillsDir() {
        String override = System.getProperty("ricbot.skills.builtinDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        try {
            var url = SkillsLoader.class.getClassLoader().getResource("skills");
            if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                return Path.of(url.toURI()).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }

        if (Files.exists(DEV_BUILTIN_SKILLS_DIR)) {
            return DEV_BUILTIN_SKILLS_DIR;
        }

        return Path.of("").toAbsolutePath().normalize();
    }
}
