package ricbot.domain.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * SkillsLoader：发现、读取、汇总技能。
 */
public class SkillsLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);

    // 开发环境下的内置技能目录路径，默认为 src/main/resources/skills
    private static final Path DEV_BUILTIN_SKILLS_DIR =
            Path.of("src", "main", "resources", "skills").toAbsolutePath().normalize();

    private static final long SCAN_CACHE_TTL_MS = 2_000;

    // 工作空间内的技能目录路径 (workspace/skills)
    private final Path workspaceSkills;
    // 内置技能目录路径
    private final Path builtinSkills;
    // 被禁用的技能名称集合
    private final Set<String> disabledSkills;

    private final Object scanLock = new Object();
    private volatile long lastScanAtMs = 0;
    private volatile List<SkillEntry> cachedEntries = List.of();
    private volatile Map<String, SkillEntry> cachedByName = Map.of();

    private final Map<String, CachedDoc> docCache = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param workspace       工作空间根路径
     * @param builtinSkillsDir 内置技能目录路径，如果为 null 则自动解析
     * @param disabledSkills  被禁用的技能名称集合，如果为 null 则初始化为空集合
     */
    public SkillsLoader(Path workspace, Path builtinSkillsDir, Set<String> disabledSkills) {
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkills = builtinSkillsDir != null ? builtinSkillsDir : resolveBuiltinSkillsDir();
        this.disabledSkills = disabledSkills != null ? disabledSkills : new HashSet<>();
    }

    /**
     * 列出所有可用的技能
     *
     * @param filterUnavailable 是否过滤不可用的技能（当前实现中未直接使用此参数进行额外过滤，主要依赖 disabledSkills）
     * @return 技能信息列表，每个元素是一个包含 name, path, source 的 Map
     */
    public List<Map<String, String>> listSkills(boolean filterUnavailable) {
        List<Map<String, String>> out = new ArrayList<>();
        for (SkillEntry e : listSkillEntries()) {
            if (filterUnavailable && disabledSkills.contains(e.name())) {
                continue;
            }
            Map<String, String> row = new HashMap<>();
            row.put("name", e.name());
            row.put("path", e.path().toString());
            row.put("source", e.source());
            if (!filterUnavailable && disabledSkills.contains(e.name())) {
                row.put("disabled", "true");
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 从指定目录加载技能条目
     *
     * @param base      基础目录路径
     * @param source    来源标识（如 "workspace" 或 "builtin"）
     * @param skipNames 需要跳过的技能名称集合
     * @return 技能条目列表
     */
    public List<Map<String, String>> skillEntriesFromDir(
            Path base,
            String source,
            Set<String> skipNames
    ) {
        // 如果目录不存在，返回空列表
        if (!Files.exists(base)) {
            return new ArrayList<>();
        }

        List<Map<String, String>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path skillDir : stream) {
                // 跳过非目录项
                if (!Files.isDirectory(skillDir)) {
                    continue;
                }
                // 检查是否存在 SKILL.md 文件
                Path skillFile = skillDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    continue;
                }
                // 获取技能名称（即目录名）
                String name = skillDir.getFileName().toString();
                // 如果该名称在跳过列表中，则跳过
                if (skipNames != null && skipNames.contains(name)) {
                    continue;
                }
                // 构建技能信息 Map
                Map<String, String> row = new HashMap<>();
                row.put("name", name);
                row.put("path", skillFile.toString());
                row.put("source", source);
                entries.add(row);
            }
        } catch (IOException ignored) {
            log.debug("扫描技能目录失败: {}", base, ignored);
        }

        return entries;
    }

    /**
     * 根据名称加载技能内容
     *
     * @param name 技能名称
     * @return 技能文件的原始内容，如果未找到则返回 null
     */
    public String loadSkill(String name) {
        SkillEntry entry = findEntry(name);
        if (entry == null) {
            return null;
        }
        try {
            if (entry.resourcePath() != null) {
                return readResourceUtf8(entry.resourcePath());
            }
            return Files.readString(entry.path());
        } catch (IOException e) {
            log.debug("读取技能失败: {}", entry.path(), e);
            return null;
        }
    }

    /**
     * 技能条目记录
     *
     * @param name   技能名称
     * @param path   技能文件路径
     * @param source 来源
     */
    public record SkillEntry(String name, Path path, String source, String resourcePath) {
    }

    /**
     * 技能文档记录，包含解析后的前缀信息和正文
     *
     * @param entry      技能条目
     * @param frontmatter 前缀元数据 Map
     * @param body       去除前缀后的正文内容
     * @param raw        原始文件内容
     */
    public record SkillDocument(SkillEntry entry, Map<String, String> frontmatter, String body, String raw) {
    }

    /**
     * 列出所有技能条目对象
     *
     * @return SkillEntry 列表
     */
    public List<SkillEntry> listSkillEntries() {
        long now = System.currentTimeMillis();
        if (now - lastScanAtMs <= SCAN_CACHE_TTL_MS && !cachedEntries.isEmpty()) {
            return cachedEntries;
        }
        synchronized (scanLock) {
            long now2 = System.currentTimeMillis();
            if (now2 - lastScanAtMs <= SCAN_CACHE_TTL_MS && !cachedEntries.isEmpty()) {
                return cachedEntries;
            }

            Map<String, SkillEntry> byName = new LinkedHashMap<>();
            scanDir(workspaceSkills, "workspace", byName, null);

            Set<String> workspaceNames = new HashSet<>(byName.keySet());
            if (Files.exists(builtinSkills)) {
                scanDir(builtinSkills, "builtin", byName, workspaceNames);
            } else {
                scanBuiltinResources(byName, workspaceNames);
            }

            List<SkillEntry> out = new ArrayList<>(byName.values());
            cachedEntries = List.copyOf(out);
            cachedByName = Map.copyOf(byName);
            lastScanAtMs = now2;
            return cachedEntries;
        }
    }

    /**
     * 根据名称加载完整的技能文档
     *
     * @param name 技能名称
     * @return SkillDocument 对象，如果未找到则返回 null
     */
    public SkillDocument loadSkillDocument(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        SkillEntry entry = findEntry(name);
        return entry != null ? loadSkillDocument(entry) : null;
    }

    /**
     * 根据技能条目加载完整的技能文档
     *
     * @param entry 技能条目
     * @return SkillDocument 对象，如果加载失败则返回 null
     */
    public SkillDocument loadSkillDocument(SkillEntry entry) {
        if (entry == null || entry.path() == null) {
            return null;
        }
        try {
            String cacheKey;
            long mtime;
            String raw;
            if (entry.resourcePath() != null) {
                cacheKey = "resource:" + entry.resourcePath();
                CachedDoc cached = docCache.get(cacheKey);
                if (cached != null) {
                    return cached.doc;
                }
                mtime = 0;
                raw = readResourceUtf8(entry.resourcePath());
            } else {
                if (!Files.exists(entry.path())) {
                    return null;
                }
                mtime = Files.getLastModifiedTime(entry.path()).toMillis();
                cacheKey = "file:" + entry.path().toAbsolutePath().normalize();
                CachedDoc cached = docCache.get(cacheKey);
                if (cached != null && cached.lastModifiedMs == mtime) {
                    return cached.doc;
                }
                raw = Files.readString(entry.path());
            }
            Map<String, String> fm = parseFrontmatter(raw);
            String body = stripFrontmatter(raw);
            SkillDocument doc = new SkillDocument(entry, fm, body, raw);
            docCache.put(cacheKey, new CachedDoc(mtime, doc));
            return doc;
        } catch (Exception e) {
            log.debug("加载技能文档失败: {}", entry.path(), e);
            return null;
        }
    }

    private void scanBuiltinResources(Map<String, SkillEntry> byName, Set<String> skipNames) {
        try {
            var codeSource = SkillsLoader.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return;
            }
            Path location = Path.of(codeSource.getLocation().toURI());
            if (!Files.isRegularFile(location) || !location.getFileName().toString().endsWith(".jar")) {
                return;
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    String name = e.getName();
                    if (!name.startsWith("skills/") || !name.endsWith("/SKILL.md")) {
                        continue;
                    }
                    String[] parts = name.split("/");
                    if (parts.length != 3) {
                        continue;
                    }
                    String skillName = parts[1];
                    if (skillName == null || skillName.isBlank()) {
                        continue;
                    }
                    if (skipNames != null && skipNames.contains(skillName)) {
                        continue;
                    }
                    byName.putIfAbsent(skillName, new SkillEntry(
                            skillName,
                            Path.of("classpath:skills/" + skillName + "/SKILL.md"),
                            "builtin",
                            name
                    ));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String readResourceUtf8(String resourcePath) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        ClassLoader cl = SkillsLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 为上下文加载指定名称的技能内容
     *
     * @param names 技能名称列表
     * @return 格式化后的技能内容字符串
     */
    public String loadSkillsForContext(List<String> names) {
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            String content = loadSkill(name);
            if (content != null && !content.isBlank()) {
                // 添加标题并去除前缀
                parts.add("## Skill: " + name + "\n\n" + stripFrontmatter(content));
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 获取标记为 "always" 的技能名称列表
     *
     * @return 始终启用的技能名称列表
     */
    public List<String> getAlwaysSkills() {
        List<String> alwaysSkills = new ArrayList<>();
        for (SkillEntry entry : listSkillEntries()) {
            SkillDocument doc = loadSkillDocument(entry);
            if (doc == null) {
                continue;
            }
            if (disabledSkills.contains(entry.name())) {
                continue;
            }
            String always = doc.frontmatter() != null ? doc.frontmatter().get("always") : null;
            if ("true".equalsIgnoreCase(always)) {
                alwaysSkills.add(entry.name());
            }
        }
        return alwaysSkills;
    }

    /**
     * 构建技能摘要字符串
     *
     * @return 技能摘要，每行一个技能，包含名称、描述和来源
     */
    public String buildSkillsSummary() {
        List<String> lines = new ArrayList<>();
        for (SkillEntry e : listSkillEntries()) {
            if (disabledSkills.contains(e.name())) {
                continue;
            }
            SkillDocument doc = loadSkillDocument(e);
            Map<String, String> fm = doc != null && doc.frontmatter() != null ? doc.frontmatter() : Map.of();
            // 优先获取 description，其次获取 desc
            String desc = fm.get("description");
            if (desc == null || desc.isBlank()) {
                desc = fm.get("desc");
            }
            // 根据是否有描述构建不同的格式
            if (desc != null && !desc.isBlank()) {
                lines.add("- " + e.name() + " — " + desc + " (" + e.source() + ")");
            } else {
                lines.add("- " + e.name() + " (" + e.source() + ")");
            }
        }
        return String.join("\n", lines);
    }

    /**
     * 获取用于上下文的技能列表字符串
     *
     * @return 格式化的技能列表字符串
     */
    public String getSkillsContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available Skills:\n");
        for (SkillEntry e : listSkillEntries()) {
            if (disabledSkills.contains(e.name())) {
                continue;
            }
            SkillDocument doc = loadSkillDocument(e);
            Map<String, String> fm = doc != null && doc.frontmatter() != null ? doc.frontmatter() : Map.of();
            // 优先获取 description，其次获取 desc
            String desc = fm.get("description");
            if (desc == null || desc.isBlank()) {
                desc = fm.get("desc");
            }
            // 根据是否有描述构建不同的格式
            if (desc != null && !desc.isBlank()) {
                sb.append("- ").append(e.name()).append(" — ").append(desc).append("\n");
            } else {
                sb.append("- ").append(e.name()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 去除内容中的 Frontmatter 部分
     *
     * @param content 原始内容
     * @return 去除 Frontmatter 后的内容
     */
    private String stripFrontmatter(String content) {
        FrontmatterBlock block = extractFrontmatterBlock(content);
        if (block == null) {
            return content;
        }
        return content.substring(block.bodyStartIndex());
    }

    /**
     * 解析内容中的 Frontmatter 部分为 Map
     *
     * @param content 原始内容
     * @return 包含键值对的 Map
     */
    private Map<String, String> parseFrontmatter(String content) {
        FrontmatterBlock block = extractFrontmatterBlock(content);
        if (block == null) {
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        String fm = block.frontmatter();
        String[] lines = fm.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            if (key.isEmpty()) {
                continue;
            }
            String value = line.substring(idx + 1).trim();

            if ("|".equals(value) || ">".equals(value)) {
                boolean fold = ">".equals(value);
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < lines.length) {
                    String next = lines[j];
                    if (next == null) {
                        j++;
                        continue;
                    }
                    if (!next.startsWith(" ") && !next.startsWith("\t")) {
                        break;
                    }
                    String v = next.stripLeading();
                    if (fold) {
                        if (sb.length() > 0) {
                            sb.append(" ");
                        }
                        sb.append(v);
                    } else {
                        sb.append(v).append("\n");
                    }
                    j++;
                }
                i = j - 1;
                out.put(key, sb.toString().trim());
                continue;
            }

            if (value.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < lines.length) {
                    String next = lines[j];
                    if (next == null) {
                        j++;
                        continue;
                    }
                    String nextTrim = next.trim();
                    if (nextTrim.isEmpty()) {
                        j++;
                        continue;
                    }
                    if (!next.startsWith(" ") && !next.startsWith("\t")) {
                        break;
                    }
                    if (nextTrim.startsWith("-")) {
                        sb.append(nextTrim).append("\n");
                    } else {
                        break;
                    }
                    j++;
                }
                if (sb.length() > 0) {
                    i = j - 1;
                    out.put(key, sb.toString().trim());
                    continue;
                }
            }

            out.put(key, value);
        }
        return out;
    }

    private SkillEntry findEntry(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, SkillEntry> byName = cachedByName;
        SkillEntry hit = byName.get(name);
        if (hit != null) {
            return hit;
        }
        for (SkillEntry e : listSkillEntries()) {
            if (name.equals(e.name())) {
                return e;
            }
        }
        return null;
    }

    private void scanDir(Path base, String source, Map<String, SkillEntry> out, Set<String> skipNames) {
        if (!Files.exists(base)) {
            return;
        }
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
                out.putIfAbsent(name, new SkillEntry(name, skillFile, source, null));
            }
        } catch (Exception e) {
            log.debug("扫描技能目录失败: {}", base, e);
        }
    }

    private static FrontmatterBlock extractFrontmatterBlock(String content) {
        if (content == null) {
            return null;
        }
        if (!content.startsWith("---")) {
            return null;
        }
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0) {
            return null;
        }
        String firstLine = content.substring(0, firstNewline).trim();
        if (!"---".equals(firstLine)) {
            return null;
        }

        int pos = firstNewline + 1;
        int fmStart = pos;
        while (pos < content.length()) {
            int nl = content.indexOf('\n', pos);
            String line;
            int lineEnd;
            if (nl < 0) {
                line = content.substring(pos);
                lineEnd = content.length();
            } else {
                line = content.substring(pos, nl);
                lineEnd = nl + 1;
            }

            if ("---".equals(line.trim())) {
                String fm = content.substring(fmStart, pos);
                return new FrontmatterBlock(fm, lineEnd);
            }

            pos = lineEnd;
        }
        return null;
    }

    private record FrontmatterBlock(String frontmatter, int bodyStartIndex) {
    }

    private record CachedDoc(long lastModifiedMs, SkillDocument doc) {
    }

    /**
     * 解析内置技能目录路径
     *
     * @return 内置技能目录的 Path
     */
    private static Path resolveBuiltinSkillsDir() {
        // 检查系统属性是否覆盖了内置技能目录
        String override = System.getProperty("ricbot.skills.builtinDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        try {
            // 尝试从类路径资源中获取 skills 目录
            var url = SkillsLoader.class.getClassLoader().getResource("skills");
            if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                return Path.of(url.toURI()).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // 忽略异常
        }

        // 如果开发环境目录存在，则使用它
        if (Files.exists(DEV_BUILTIN_SKILLS_DIR)) {
            return DEV_BUILTIN_SKILLS_DIR;
        }

        // 默认返回当前工作目录
        return Path.of("").toAbsolutePath().normalize();
    }
}
