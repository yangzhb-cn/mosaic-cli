package com.coder.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// 执行 shell 命令，带超时、危险命令拦截、输出截断
public final class BashTool extends ToolBase {
    private static final String[][] DANGEROUS = {
            {"\\brm\\s+(-\\w*)?-r\\w*\\s+(/|~|\\$HOME)", "递归删除根目录或用户目录"},
            {"\\brm\\s+(-\\w*)?-rf\\s", "强制递归删除"},
            {"\\bmkfs\\b", "格式化文件系统"},
            {"\\bdd\\s+.*of=/dev/", "直接写入原始磁盘"},
            {">\\s*/dev/sd[a-z]", "覆盖块设备"},
            {"\\bchmod\\s+(-R\\s+)?777\\s+/", "对根目录执行 chmod 777"},
            {":\\(\\)\\s*\\{.*:\\|:.*\\}", "fork bomb"},
            {"\\bcurl\\b.*\\|\\s*(sudo\\s+)?bash", "把 curl 输出直接交给 bash"},
            {"\\bwget\\b.*\\|\\s*(sudo\\s+)?bash", "把 wget 输出直接交给 bash"}
    };
    private static Path cwd;

    @Override
    public String name() { return "Bash"; }

    @Override
    public String description() {
        return """
                在 shell 中执行给定 bash 命令，支持可选超时，并确保适当处理和安全措施。

                执行命令前，请遵循以下步骤：

                1. 目录验证：
                   - 如果命令会创建新目录或文件，先使用 LS 工具验证父目录存在且位置正确
                   - 例如，在运行 "mkdir foo/bar" 前，先使用 LS 检查 "foo" 存在且是预期父目录

                2. 命令执行：
                   - 确保正确引用后，执行命令。
                   - 捕获命令输出。

                使用说明：
                  - command 参数必填。
                  - 可以指定可选 timeout，单位秒。如果未指定，命令将在 120 秒后超时。
                  - 如果输出超过 15000 字符，会在返回前截断。
                  - 非常重要：必须避免使用 find 和 grep 这类搜索命令。改用 Grep、Glob 或 Task 搜索。必须避免 cat、head、tail、ls 这类读取工具，改用 Read 和 LS 读取文件。
                  - 如果你仍然需要运行 grep，停止。始终先使用 ripgrep，即 `rg`。
                  - 发出多个命令时，用 ';' 或 '&&' 分隔。不要使用换行（引号内换行可以）。
                  - 尽量在整个会话中保持当前工作目录，使用绝对路径并避免使用 `cd`。如果用户明确要求，可以使用 `cd`。
                    <good-example>
                    pytest /foo/bar/tests
                    </good-example>
                    <bad-example>
                    cd /foo/bar && pytest tests
                    </bad-example>



                # 使用 git 提交更改

                当用户要求你创建新的 git commit 时，仔细遵循以下步骤：

                1. 你可以在一次回复中调用多个工具。当请求多个独立信息时，批量调用工具以获得最佳性能。始终并行运行以下 bash 命令，每个都使用 Bash 工具：
                   - 运行 git status 命令查看所有未跟踪文件。
                   - 运行 git diff 命令查看将被提交的已暂存和未暂存更改。
                   - 运行 git log 命令查看近期提交消息，以便遵循该仓库的提交消息风格。

                2. 分析所有已暂存更改（包括之前已暂存和新添加的），并起草 commit message。用 <commit_analysis> 标签包裹你的分析过程：

                <commit_analysis>
                - 列出已更改或新增的文件
                - 总结更改性质（例如新功能、增强现有功能、bug 修复、重构、测试、文档等）
                - 思考这些更改的目的或动机
                - 评估这些更改对整个项目的影响
                - 检查是否有不应提交的敏感信息
                - 起草简洁（1-2 句）的 commit message，关注“为什么”而不只是“做了什么”
                - 确保语言清晰、简洁、切中要点
                - 确保消息准确反映更改及其目的（即 "add" 表示全新功能，"update" 表示增强现有功能，"fix" 表示 bug 修复等）
                - 确保消息不是泛泛而谈（避免只写 "Update" 或 "Fix" 而没有上下文）
                - 审查草稿消息，确保它准确反映更改及其目的
                </commit_analysis>


                3. 你可以在一次回复中调用多个工具。当请求多个独立信息时，批量调用工具以获得最佳性能。始终并行运行以下命令：
                   - 将相关未跟踪文件添加到暂存区。
                   - 创建 commit。
                   - 运行 git status 确认 commit 成功。

                4. 如果 commit 因 pre-commit hook 修改而失败，重试 commit 一次以包含这些自动更改。如果再次失败，通常表示 pre-commit hook 阻止了提交。如果 commit 成功但你注意到文件被 pre-commit hook 修改，必须 amend commit 以包含它们。

                重要说明：
                - 使用本会话开始时的 git 上下文判断哪些文件与本次提交相关。小心不要暂存和提交无关文件（例如使用 `git add .`）。
                - 绝不要更新 git config
                - 除 git 上下文已有信息外，不要运行额外命令来读取或探索代码
                - 不要推送到远程仓库，除非用户明确要求
                - 重要：绝不要使用带 -i flag 的 git 命令（例如 git rebase -i 或 git add -i），因为它们需要不支持的交互输入。
                - 如果没有要提交的更改（即没有未跟踪文件，也没有修改），不要创建空 commit
                - 确保 commit message 有意义且简洁。它应该说明更改目的，而不只是描述更改内容。
                - 为确保格式良好，始终通过 HEREDOC 传递 commit message，如下例：
                <example>
                git commit -m "$(cat <<'EOF'
                   Commit message here.
                EOF
                )"
                </example>


                # 创建 pull request

                所有 GitHub 相关任务都通过 Bash 工具使用 gh 命令，包括处理 issues、pull requests、checks 和 releases。如果提供了 Github URL，使用 gh 命令获取所需信息。

                重要：当用户要求你创建 pull request 时，仔细遵循以下步骤：

                1. 你可以在一次回复中调用多个工具。当请求多个独立信息时，批量调用工具以获得最佳性能。始终并行运行以下 bash 命令，以了解当前分支自从与 main 分支分叉以来的当前状态：
                   - 运行 git status 命令查看所有未跟踪文件
                   - 运行 git diff 命令查看将被提交的已暂存和未暂存更改
                   - 检查当前分支是否跟踪远程分支且是否与远程同步，以判断是否需要推送到远程
                   - 运行 git log 命令和 `git diff main...HEAD`，了解当前分支自与 `main` 分叉以来的完整提交历史

                2. 分析 pull request 将包含的所有更改，确保查看所有相关 commits（不只是最新 commit，而是 PR 将包含的所有 commits！！！），并起草 pull request summary。用 <pr_analysis> 标签包裹你的分析过程：

                <pr_analysis>
                - 列出自 main 分支分叉以来的 commits
                - 总结更改性质（例如新功能、增强现有功能、bug 修复、重构、测试、文档等）
                - 思考这些更改的目的或动机
                - 评估这些更改对整个项目的影响
                - 除 git 上下文已有信息外，不要使用工具探索代码
                - 检查是否有不应提交的敏感信息
                - 起草简洁（1-2 个 bullet points）的 pull request summary，关注“为什么”而不只是“做了什么”
                - 确保 summary 准确反映自 main 分叉以来的所有更改
                - 确保语言清晰、简洁、切中要点
                - 确保 summary 准确反映更改及其目的（即 "add" 表示全新功能，"update" 表示增强现有功能，"fix" 表示 bug 修复等）
                - 确保 summary 不是泛泛而谈（避免只写 "Update" 或 "Fix" 而没有上下文）
                - 审查草稿 summary，确保它准确反映更改及其目的
                </pr_analysis>

                3. 你可以在一次回复中调用多个工具。当请求多个独立信息时，始终并行运行以下命令：
                   - 如有需要，创建新分支
                   - 如有需要，使用 -u flag 推送到远程
                   - 使用 gh pr create 创建 PR，格式如下。使用 HEREDOC 传递 body，确保格式正确。
                <example>
                gh pr create --title "the pr title" --body "$(cat <<'EOF'
                ## Summary
                <1-3 bullet points>

                ## Test plan
                [Checklist of TODOs for testing the pull request...]
                EOF
                )"
                </example>


                重要：
                - 绝不要更新 git config
                - 完成后返回 PR URL，方便用户查看
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "command", prop("string", "要执行的命令"),
                "timeout", prop("integer", "可选超时时间，单位秒。如果未指定，默认 120 秒。")
        ), "command");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String command = str(args, "command", "");
        String blocked = dangerous(command);
        if (blocked != null) return "已阻止: " + blocked + "\n命令: " + command;
        int timeout = integer(args, "timeout", 120);
        Path runDir = cwd == null ? Path.of("").toAbsolutePath() : cwd;
        List<String> shell = System.getProperty("os.name").toLowerCase().contains("win") ? List.of("cmd", "/c", command) : List.of("/bin/sh", "-c", command);
        try {
            Process p = new ProcessBuilder(shell).directory(runDir.toFile()).start();
            CompletableFuture<String> out = readAsync(p.getInputStream());
            CompletableFuture<String> err = readAsync(p.getErrorStream());
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "错误: " + timeout + " 秒后超时";
            }
            String text = out.get(1, TimeUnit.SECONDS);
            String stderr = err.get(1, TimeUnit.SECONDS);
            if (!stderr.isBlank()) text += "\n[标准错误]\n" + stderr;
            if (p.exitValue() != 0) text += "\n[退出码: " + p.exitValue() + "]";
            if (p.exitValue() == 0) updateCwd(command, runDir);
            if (text.length() > 15000) text = text.substring(0, 6000) + "\n\n... 已截断 (共 " + text.length() + " 个字符) ...\n\n" + text.substring(text.length() - 3000);
            return text.strip().isEmpty() ? "(无输出)" : text.strip();
        } catch (Exception e) {
            return "错误: 命令执行失败: " + e.getMessage();
        }
    }

    private CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try { return new String(in.readAllBytes(), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
        });
    }

    private String dangerous(String command) {
        for (String[] p : DANGEROUS) if (Pattern.compile(p[0]).matcher(command).find()) return p[1];
        return null;
    }

    private void updateCwd(String command, Path base) {
        Path cur = base;
        for (String part : command.split("&&")) {
            String s = part.strip();
            if (!s.startsWith("cd ")) continue;
            String raw = s.substring(3).strip().replaceAll("^['\"]|['\"]$", "");
            Path next = raw.startsWith("~") ? Path.of(System.getProperty("user.home") + raw.substring(1)).toAbsolutePath().normalize() : cur.resolve(raw).normalize();
            if (Files.isDirectory(next)) cur = next;
        }
        cwd = cur;
    }
}
