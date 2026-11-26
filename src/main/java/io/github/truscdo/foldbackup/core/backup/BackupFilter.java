package io.github.truscdo.foldbackup.core.backup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 备份目标过滤器：将 glob 通配符规则应用于相对于世界目录的路径。
 * <p>
 * 语义：
 * <ul>
 * <li>仅匹配相对路径（相对于世界目录，使用 {@code /} 分隔，且不含前导 {@code /}），
 * 格式与 manifest 中的 relPath 一致；绝对路径或前导 {@code /} 路径不在支持范围内。</li>
 * <li>匹配文件时不备份该文件；匹配目录时跳过整个子树。</li>
 * <li>规则以未转义的 {@code /} 结尾时（如 {@code backups/}）仅匹配目录，不匹配同名文件。</li>
 * <li>glob 支持 {@code *}、{@code **}、{@code ?}、{@code [...]}、{@code {a,b}}；
 * {@code **&#47;} 还支持匹配零个或多个目录层级。</li>
 * </ul>
 * <p>
 * 性能：所有规则在构造时一次性编译并合并为文件/目录两条正则（热路径每个路径只需进行一次
 * {@code match}，无需重复编译或逐条遍历）。{@link #BackupFilter(List)} 构造器对配置文件中
 * 的非法规则保持宽容忽略；CLI/TUI 等用户输入入口应使用 {@link #ofStrict(List)}、
 * {@link #defaultsWith(List)} 或 {@link #validatePattern(String)} 在执行前 fail-fast 报错。
 * <p>
 * 默认规则与原 {@code RegionProcessor} 中的 {@code SKIP_NAMES} 及
 * {@code .wip}/{@code .lrh.wip}
 * 跳过逻辑一致：
 *
 * <pre>
 * ["**&#47;session.lock", "**&#47;backups", "**&#47;.backups", "**&#47;*.wip"]
 * </pre>
 *
 * 注：0 字节文件由 {@code RegionProcessor} 在过滤之前无条件跳过（与参考工具
 * {@code LinearRegionFileFormatTools} 一致），不经过本过滤器。
 */
public final class BackupFilter {

    /** glob 自身需要转义的元字符（在 {@code \\} 转义分支中使用）。 */
    private static final String GLOB_META = "\\*?[{}";
    /** 正则表达式元字符（出现在字面量时必须转义）。 */
    private static final String REGEX_META = ".^$+{[]|()";

    /** 离线与核心层默认过滤规则（4 条核心保护规则）。 */
    public static final List<String> DEFAULT_PATTERNS = List.of(
            "**/session.lock", "**/backups", "**/.backups", "**/*.wip");

    private static final BackupFilter DEFAULTS = ofStrict(DEFAULT_PATTERNS);

    private final List<String> excludePatterns;
    /** 仅作用于文件的合并正则（排除尾 `/` 仅目录规则）；没有匹配文件规则时为 {@code null}。 */
    private final Pattern filePattern;
    /** 作用于目录的合并正则（包含所有规则并剥离尾 `/`）；没有规则时为 {@code null}。 */
    private final Pattern directoryPattern;

    /**
     * 离线与核心层默认过滤规则：仅包含 4 条核心保护规则（{@code session.lock}、{@code backups}、
     * {@code .backups}、{@code *.wip}），故意不包含 Mod 在线默认排除的
     * {@code **&#47;DistantHorizons.sqlite}——保持更保守的 4 条默认值避免静默漏备第三方模组数据；
     * 如需追加额外规则请使用 {@link #defaultsWith(List)}。
     */
    public static BackupFilter defaults() {
        return DEFAULTS;
    }

    /**
     * 在默认 4 条核心保护规则基础上追加额外排除规则，并执行严格语法校验。
     *
     * @param extraPatterns 额外 glob 规则（相对路径，{@code /} 分隔）；{@code null} 或空列表返回 {@link #defaults()}
     * @throws IllegalArgumentException 若任一额外规则为空白、含前导 {@code /} 或 glob 语法非法
     */
    public static BackupFilter defaultsWith(List<String> extraPatterns) {
        if (extraPatterns == null || extraPatterns.isEmpty()) {
            return defaults();
        }
        List<String> combined = new ArrayList<>(DEFAULT_PATTERNS.size() + extraPatterns.size());
        combined.addAll(DEFAULT_PATTERNS);
        combined.addAll(extraPatterns);
        return ofStrict(combined);
    }

    /**
     * 严格构造过滤器：任一规则为空白、含前导 {@code /} 或 glob 语法非法时立即抛出 {@link IllegalArgumentException}。
     */
    public static BackupFilter ofStrict(List<String> excludePatterns) {
        Objects.requireNonNull(excludePatterns, "excludePatterns");
        List<String> normalized = new ArrayList<>(excludePatterns.size());
        CompiledBuilders builders = new CompiledBuilders();
        for (String raw : excludePatterns) {
            normalized.add(validateAndCompile(raw, builders));
        }
        return new BackupFilter(List.copyOf(normalized), builders.compileFile(), builders.compileDir());
    }

    /**
     * 校验并规范化单条排除规则，失败时抛出包含修复提示的 {@link IllegalArgumentException}。
     */
    public static String validatePattern(String rawPattern) {
        return validateAndCompile(rawPattern, new CompiledBuilders());
    }

    /**
     * 按括号深度感知的顶层逗号拆分单行多规则输入（兼容 {@code {a,b}} 备选分支与 {@code [a,b]} 字符类），
     * 并逐条执行严格校验；空白输入返回空列表。
     */
    public static List<String> parseExcludeList(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        int segStart = 0;
        int braceDepth = 0;
        boolean inBracket = false;
        int n = rawLine.length();
        for (int i = 0; i < n; i++) {
            char c = rawLine.charAt(i);
            if (inBracket) {
                if (c == ']') {
                    inBracket = false;
                }
                continue;
            }
            if (c == '\\') {
                if (i + 1 < n) {
                    i++;
                }
                continue;
            }
            if (c == '[') {
                inBracket = true;
                if (i + 1 < n && rawLine.charAt(i + 1) == '!') {
                    i++;
                }
                if (i + 1 < n && rawLine.charAt(i + 1) == ']') {
                    i++;
                }
                continue;
            }
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                }
            } else if (c == ',' && braceDepth == 0) {
                result.add(validatePattern(rawLine.substring(segStart, i)));
                segStart = i + 1;
            }
        }
        result.add(validatePattern(rawLine.substring(segStart)));
        return List.copyOf(result);
    }

    /**
     * 宽容构造器（供配置文件加载使用）：忽略空白、含前导 {@code /} 或语法非法的条目。
     *
     * @param excludePatterns glob 通配符规则列表（相对路径，{@code /} 分隔）；
     *                        为 {@code null} 或空列表表示不过滤任何内容
     */
    public BackupFilter(List<String> excludePatterns) {
        this.excludePatterns = List.copyOf(excludePatterns != null ? excludePatterns : List.of());
        CompiledBuilders builders = new CompiledBuilders();
        for (String p : this.excludePatterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String trimmed = p.trim();
            if (trimmed.startsWith("/")) {
                continue;
            }
            try {
                builders.append(trimmed);
            } catch (PatternSyntaxException ignored) {
                // 忽略非法模式，避免导致整条过滤失效
            }
        }
        this.filePattern = builders.compileFile();
        this.directoryPattern = builders.compileDir();
    }

    private BackupFilter(List<String> excludePatterns, Pattern filePattern, Pattern directoryPattern) {
        this.excludePatterns = excludePatterns;
        this.filePattern = filePattern;
        this.directoryPattern = directoryPattern;
    }

    /** 返回构造时传入的规则列表（只读）。 */
    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    /**
     * 如果文件相对路径（使用 {@code /} 分隔）匹配任一规则，则不备份该文件。
     */
    public boolean skipFile(String relPath) {
        return matches(filePattern, relPath);
    }

    /**
     * 如果目录相对路径（使用 {@code /} 分隔）匹配任一规则，则跳过整个子树。
     */
    public boolean skipDirectory(String relPath) {
        return matches(directoryPattern, relPath);
    }

    /**
     * 如果文件相对路径匹配任一规则，则不备份该文件。路径会先统一转换为
     * {@code /} 分隔符再进行匹配。
     */
    public boolean skipFile(Path relPath) {
        return matches(filePattern, normalize(relPath));
    }

    /**
     * 如果目录相对路径匹配任一规则，则跳过整个子树。路径会先统一转换为
     * {@code /} 分隔符再进行匹配。
     */
    public boolean skipDirectory(Path relPath) {
        return matches(directoryPattern, normalize(relPath));
    }

    /** 是否存在至少一条成功编译的规则。 */
    public boolean isEmpty() {
        return filePattern == null && directoryPattern == null;
    }

    private static boolean matches(Pattern pattern, String relPath) {
        if (pattern == null || relPath == null || relPath.isEmpty()) {
            return false;
        }
        return pattern.matcher(relPath).matches();
    }

    private static String normalize(Path p) {
        return p == null ? null : p.toString().replace('\\', '/');
    }

    // ──────────────────────────────────────────────
    // Glob → 正则表达式编译
    // ──────────────────────────────────────────────

    private static String validateAndCompile(String rawPattern, CompiledBuilders builders) {
        if (rawPattern == null || rawPattern.isBlank()) {
            throw new IllegalArgumentException("Exclude pattern must not be blank");
        }
        String pattern = rawPattern.trim();
        if (pattern.startsWith("/")) {
            String stripped = pattern.replaceFirst("^/+", "");
            if (!stripped.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid exclude pattern '" + pattern + "': leading '/' is not allowed (use relative path '"
                                + stripped + "')");
            }
            throw new IllegalArgumentException(
                    "Invalid exclude pattern '" + pattern
                            + "': leading '/' is not allowed (patterns must be relative to the world directory)");
        }
        try {
            builders.append(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid exclude pattern '" + pattern + "': " + e.getDescription(), e);
        }
        return pattern;
    }

    private static boolean isEscaped(String s, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static final class CompiledBuilders {
        final StringBuilder file = new StringBuilder();
        final StringBuilder dir = new StringBuilder();

        void append(String pattern) {
            int end = pattern.length();
            while (end > 0 && pattern.charAt(end - 1) == '/' && !isEscaped(pattern, end - 1)) {
                end--;
            }
            boolean directoryOnly = end < pattern.length();
            String base = directoryOnly ? pattern.substring(0, end) : pattern;
            String regex = globToRegex(base);
            Pattern.compile(regex);
            appendBranch(dir, regex);
            if (!directoryOnly) {
                appendBranch(file, regex);
            }
        }

        private static void appendBranch(StringBuilder sb, String regex) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append("(?:").append(regex).append(')');
        }

        Pattern compileFile() {
            return file.length() == 0 ? null : Pattern.compile(file.toString());
        }

        Pattern compileDir() {
            return dir.length() == 0 ? null : Pattern.compile(dir.toString());
        }
    }

    /**
     * 将单个 glob 模式编译为带锚点（^…$）的正则表达式。支持以下语法：
     * <ul>
     * <li>{@code *} → 单层内任意字符（不跨 {@code /}）</li>
     * <li>{@code **} → 任意（跨 {@code /}）；{@code **&#47;} 额外匹配零个目录</li>
     * <li>{@code ?} → 单个非 {@code /} 字符</li>
     * <li>{@code [...]} → 字符类（支持 {@code [!...]} 取反）</li>
     * <li>{@code {a,b}} → 备选分支</li>
     * <li>{@code \\} → 转义下一个字符</li>
     * </ul>
     */
    static String globToRegex(String globPattern) {
        StringBuilder regex = new StringBuilder("^");
        appendGlob(regex, globPattern);
        return regex.append('$').toString();
    }

    /** 追加 glob 模式正文（不含锚点），供完整模式和 {@code {...}} 分支共用。 */
    private static void appendGlob(StringBuilder regex, String globPattern) {
        int i = 0;
        int n = globPattern.length();
        while (i < n) {
            char c = globPattern.charAt(i++);
            switch (c) {
                case '*':
                    if (i < n && globPattern.charAt(i) == '*') {
                        // "**"：跨目录匹配；"**/" 特化为零个或多个目录
                        if (i + 1 < n && globPattern.charAt(i + 1) == '/') {
                            i += 2;
                            if (i == n) {
                                regex.append(".*");
                            } else {
                                regex.append("(?:.*/)?");
                            }
                        } else {
                            regex.append(".*");
                            i++;
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '[':
                    i = appendClass(regex, globPattern, i, n);
                    break;
                case '{':
                    i = appendGroup(regex, globPattern, i, n);
                    break;
                case '}':
                    throw new PatternSyntaxException("unexpected '}'", globPattern, i - 1);
                case ',':
                    regex.append(',');
                    break;
                case '\\':
                    if (i >= n) {
                        throw new PatternSyntaxException("trailing backslash", globPattern, i - 1);
                    }
                    char next = globPattern.charAt(i++);
                    if (next == '/' || GLOB_META.indexOf(next) >= 0
                            || REGEX_META.indexOf(next) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(next);
                    break;
                default:
                    if (c != '/' && REGEX_META.indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
            }
        }
    }

    /** 解析 {@code [...]} 字符类，返回结束 {@code ]} 之后的下标。 */
    private static int appendClass(StringBuilder regex, String glob, int start, int n) {
        regex.append('[');
        int i = start;
        if (i < n && glob.charAt(i) == '!') {
            regex.append('^');
            i++;
        }
        if (i < n && glob.charAt(i) == ']') {
            regex.append("\\]");
            i++;
        }
        while (i < n && glob.charAt(i) != ']') {
            char c = glob.charAt(i);
            if (c == '\\' || c == ']' || c == '[' || c == '^' || c == '-' || c == '&') {
                regex.append('\\');
            }
            regex.append(c);
            i++;
        }
        if (i >= n) {
            throw new PatternSyntaxException("missing ']'", glob, n);
        }
        regex.append(']');
        return i + 1;
    }

    /** 解析 {@code {a,b,c}} 备选分支，返回结束 {@code }} 之后的下标。 */
    private static int appendGroup(StringBuilder regex, String glob, int start, int n) {
        int j = start;
        int depth = 1;
        boolean inClass = false;
        while (j < n) {
            char c = glob.charAt(j);
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                j++;
                continue;
            }
            if (c == '\\') {
                if (j + 1 >= n) {
                    throw new PatternSyntaxException("trailing backslash", glob, j);
                }
                j += 2;
                continue;
            }
            if (c == '[') {
                inClass = true;
                j++;
                if (j < n && glob.charAt(j) == '!') {
                    j++;
                }
                if (j < n && glob.charAt(j) == ']') {
                    j++;
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            j++;
        }
        if (inClass) {
            throw new PatternSyntaxException("missing ']'", glob, n);
        }
        if (j >= n) {
            throw new PatternSyntaxException("missing '}'", glob, n);
        }
        String inner = glob.substring(start, j);
        regex.append("(?:");
        // 按顶层逗号拆分备选分支（嵌套 {}、[] 及转义字符内的逗号不算分隔符）
        int segStart = 0;
        int segDepth = 0;
        boolean segInClass = false;
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
            if (segInClass) {
                if (c == ']') {
                    segInClass = false;
                }
                continue;
            }
            if (c == '\\') {
                k++;
                continue;
            }
            if (c == '[') {
                segInClass = true;
                if (k + 1 < inner.length() && inner.charAt(k + 1) == '!') {
                    k++;
                }
                if (k + 1 < inner.length() && inner.charAt(k + 1) == ']') {
                    k++;
                }
                continue;
            }
            if (c == '{') {
                segDepth++;
            } else if (c == '}') {
                segDepth--;
            } else if (c == ',' && segDepth == 0) {
                appendGlob(regex, inner.substring(segStart, k));
                regex.append('|');
                segStart = k + 1;
            }
        }
        appendGlob(regex, inner.substring(segStart));
        regex.append(')');
        return j + 1;
    }
}