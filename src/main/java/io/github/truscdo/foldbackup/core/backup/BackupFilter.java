package io.github.truscdo.foldbackup.core.backup;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 备份目标过滤器：将 glob 通配符规则应用于相对于世界目录的路径。
 * <p>
 * 语义：
 * <ul>
 * <li>仅匹配相对路径（相对于世界目录，使用 {@code /} 分隔，且不含前导 {@code /}），
 * 格式与 manifest 中的 relPath 一致；绝对路径不在支持范围内。</li>
 * <li>匹配文件时不备份该文件；匹配目录时跳过整个子树。</li>
 * <li>glob 支持 {@code *}、{@code **}、{@code ?}、{@code [...]}、{@code {a,b}}；
 * {@code **&#47;} 还支持匹配零个或多个目录层级。</li>
 * </ul>
 * <p>
 * 性能：所有规则在构造时一次性编译并合并为单个正则（热路径每个路径只需进行一次
 * {@code match}，无需重复编译或逐条遍历）。非法 glob 模式会被忽略，不会导致整个过滤器失效。
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
    private static final String GLOB_META = "\\*?[{";
    /** 正则表达式元字符（出现在字面量时必须转义）。 */
    private static final String REGEX_META = ".^$+{[]|()";

    private final List<String> excludePatterns;
    /** 合并后的正则；没有任何有效规则时为 {@code null}（不过滤）。 */
    private final Pattern compiled;

    /**
     * 默认过滤规则：与原硬编码跳过行为一致的默认值。
     */
    public static BackupFilter defaults() {
        return new BackupFilter(List.of(
                "**/session.lock", "**/backups", "**/.backups", "**/*.wip"));
    }

    /**
     * @param excludePatterns glob 通配符规则列表（相对路径，{@code /} 分隔）；
     *                        为 {@code null} 或空列表表示不过滤任何内容
     */
    public BackupFilter(List<String> excludePatterns) {
        this.excludePatterns = List.copyOf(excludePatterns != null ? excludePatterns : List.of());
        this.compiled = compilePatterns(this.excludePatterns);
    }

    /** 返回构造时传入的规则列表（只读）。 */
    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    /**
     * 如果文件相对路径（使用 {@code /} 分隔）匹配任一规则，则不备份该文件。
     */
    public boolean skipFile(String relPath) {
        return matches(relPath);
    }

    /**
     * 如果目录相对路径（使用 {@code /} 分隔）匹配任一规则，则跳过整个子树。
     */
    public boolean skipDirectory(String relPath) {
        return matches(relPath);
    }

    /**
     * 如果文件相对路径匹配任一规则，则不备份该文件。路径会先统一转换为
     * {@code /} 分隔符再进行匹配。
     */
    public boolean skipFile(Path relPath) {
        return matches(normalize(relPath));
    }

    /**
     * 如果目录相对路径匹配任一规则，则跳过整个子树。路径会先统一转换为
     * {@code /} 分隔符再进行匹配。
     */
    public boolean skipDirectory(Path relPath) {
        return matches(normalize(relPath));
    }

    /** 是否存在至少一条成功编译的规则。 */
    public boolean isEmpty() {
        return compiled == null;
    }

    private boolean matches(String relPath) {
        if (compiled == null)
            return false;
        return compiled.matcher(relPath).matches();
    }

    private static String normalize(Path p) {
        return p.toString().replace('\\', '/');
    }

    // ──────────────────────────────────────────────
    // Glob → 正则表达式编译
    // ──────────────────────────────────────────────

    /**
     * 将规则列表合并编译为单个正则表达式；非法模式（PatternSyntaxException）会被忽略。
     */
    private static Pattern compilePatterns(List<String> patterns) {
        StringBuilder sb = new StringBuilder();
        for (String p : patterns) {
            if (p == null || p.isBlank())
                continue;
            try {
                String regex = globToRegex(p.trim());
                if (sb.length() > 0)
                    sb.append('|');
                sb.append("(?:").append(regex).append(')');
            } catch (PatternSyntaxException ignored) {
                // 忽略非法模式，避免导致整条过滤失效
            }
        }
        return sb.length() == 0 ? null : Pattern.compile(sb.toString());
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
        boolean inGroup = false;
        while (i < n) {
            char c = globPattern.charAt(i++);
            switch (c) {
                case '*':
                    if (inGroup) {
                        regex.append(".*");
                    } else if (i < n && globPattern.charAt(i) == '*') {
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
                    regex.append(inGroup ? "." : "[^/]");
                    break;
                case '[':
                    if (inGroup) {
                        regex.append("\\[");
                    } else {
                        i = appendClass(regex, globPattern, i, n);
                    }
                    break;
                case '{':
                    if (inGroup) {
                        regex.append("\\{");
                    } else {
                        // appendGroup 一次性消费整个 {…}（含嵌套），
                        // 因此这里不再置 inGroup=true——否则组内 } 已被消费，
                        // 循环结束时 inGroup 残留 true 会误报 missing '}'。
                        i = appendGroup(regex, globPattern, i, n);
                    }
                    break;
                case '}':
                    if (inGroup) {
                        regex.append(')');
                        inGroup = false;
                    } else {
                        regex.append('}');
                    }
                    break;
                case ',':
                    regex.append(inGroup ? '|' : ',');
                    break;
                case '\\':
                    if (i < n) {
                        char next = globPattern.charAt(i++);
                        if (next == '/' || GLOB_META.indexOf(next) >= 0
                                || REGEX_META.indexOf(next) >= 0) {
                            regex.append('\\');
                        }
                        regex.append(next);
                    }
                    // 末尾孤立 '\'：按字面忽略
                    break;
                default:
                    if (c != '/' && REGEX_META.indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
            }
        }
        if (inGroup) {
            throw new PatternSyntaxException("missing '}'", globPattern, -1);
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
        while (j < n) {
            char c = glob.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0)
                    break;
            }
            j++;
        }
        if (j >= n) {
            throw new PatternSyntaxException("missing '}'", glob, n);
        }
        String inner = glob.substring(start, j);
        regex.append("(?:");
        // 按顶层逗号拆分备选分支（嵌套 {} 内的逗号不算分隔符）
        int segStart = 0;
        int segDepth = 0;
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
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