package com.corecoder.tools;

import java.util.List;
import java.util.Map;

public final class TodoWriteTool extends ToolBase {
    @Override
    public String name() { return "TodoWrite"; }

    @Override
    public String description() {
        return """
                使用此工具创建和管理当前编码会话的结构化任务列表。这有助于跟踪进展、组织复杂任务，并向用户展示你的彻底性。
                它也帮助用户理解任务进展和整体请求进度。

                ## 何时使用此工具
                在以下场景中主动使用此工具：

                1. 复杂多步骤任务 - 当任务需要 3 个或更多不同步骤或操作时
                2. 非平凡且复杂的任务 - 需要仔细规划或多项操作的任务
                3. 用户明确要求待办列表 - 当用户直接要求你使用待办列表时
                4. 用户提供多个任务 - 当用户提供编号列表或逗号分隔的多项任务时
                5. 收到新指令后 - 立即把用户需求记录为 todos。可根据新信息随时编辑待办列表。
                6. 完成任务后 - 标记完成并添加任何新的后续任务
                7. 当你开始处理新任务时，将该 todo 标记为 in_progress。理想情况下，同一时间只有一个 todo 处于 in_progress。开始新任务前先完成已有任务。

                ## 何时不使用此工具

                以下情况跳过此工具：
                1. 只有一个简单直接的任务
                2. 任务很琐碎，跟踪它没有组织价值
                3. 任务能在少于 3 个琐碎步骤内完成
                4. 纯对话或信息性任务

                注意：如果只有一个琐碎任务，不应使用此工具。在这种情况下，直接完成任务更好。

                ## 使用待办列表的示例

                <example>
                用户：我想在应用设置中添加深色模式开关。完成后请确保运行测试和构建。
                助手：我会帮你在应用设置中添加深色模式开关。让我创建一个待办列表来跟踪这个实现。
                *创建包含以下条目的待办列表：*
                1. 在设置页创建深色模式开关组件
                2. 添加深色模式状态管理（context/store）
                3. 为深色主题实现 CSS-in-JS 样式
                4. 更新现有组件以支持主题切换
                5. 运行测试和构建流程，并处理出现的失败或错误
                *开始处理第一项任务*

                <reasoning>
                助手使用待办列表是因为：
                1. 添加深色模式是多步骤功能，需要 UI、状态管理和样式修改
                2. 用户明确要求之后运行测试和构建
                3. 助手推断测试和构建需要通过，因此添加“确保测试和构建成功”作为最终任务
                </reasoning>
                </example>

                <example>
                用户：帮我把项目里的函数 getCwd 重命名为 getCurrentWorkingDirectory
                助手：让我先在你的代码库中搜索 'getCwd' 的所有出现位置。
                *使用 grep 或搜索工具定位代码库中所有 getCwd 实例*
                助手：我在 8 个不同文件中找到了 15 个 'getCwd' 实例。让我创建一个待办列表来跟踪这些更改。
                *为每个需要更新的文件创建具体待办项*

                <reasoning>
                助手使用待办列表是因为：
                1. 助手先搜索以理解任务范围
                2. 发现多个文件中有多个出现位置后，判断这是一个有多个步骤的复杂任务
                3. 待办列表有助于确保每个实例都被系统跟踪和更新
                4. 这种方法可以避免遗漏任何出现位置，并保持代码一致性
                </reasoning>
                </example>


                <example>
                用户：我需要为电商网站实现这些功能：用户注册、商品目录、购物车和结账流程。
                助手：我会帮你实现这些功能。首先，让我们把所有功能加入待办列表。
                *创建待办列表，根据项目架构将每个功能拆解为具体任务*
                助手：我们从用户注册开始。这将涉及创建数据库模型、API 端点和前端表单，并与现有认证系统集成。

                <reasoning>
                助手使用待办列表是因为：
                1. 用户提供了多个复杂功能，并用逗号分隔
                2. 待办列表有助于把这些大功能组织为可管理的任务
                3. 这种方法可以跟踪整个实现过程的进展
                </reasoning>
                </example>

                <example>
                用户：你能帮我优化 React 应用吗？它渲染很慢，还有性能问题。
                助手：我会帮你优化 React 应用。首先，让我检查代码库以识别潜在性能瓶颈。
                *审查组件结构、渲染模式、状态管理和数据获取*
                助手：分析代码库后，我识别出几个性能问题。让我创建一个待办列表来跟踪优化工作。
                *创建待办列表，条目如：1) 为 ProductList 中昂贵计算实现 memoization，2) 为 Dashboard 中长列表添加 virtualization，3) 优化 Gallery 组件中的图片加载，4) 修复 ShoppingCart 中的状态更新循环，5) 审查 bundle 大小并实现 code splitting*
                让我们先为 ProductList 组件中的昂贵计算实现 memoization。

                <reasoning>
                助手使用待办列表是因为：
                1. 助手先检查代码库以识别具体性能问题
                2. 基于分析，识别出多个优化机会
                3. 性能优化是需要多个步骤的非平凡任务
                4. 待办列表有助于有条理地跟踪不同组件中的改进
                5. 这种系统化方法确保所有性能瓶颈都被处理
                </reasoning>
                </example>

                ## 何时不使用待办列表的示例

                <example>
                用户：如何在 Python 中打印 'Hello World'？
                助手：在 Python 中，你可以用这段简单代码打印 "Hello World"：

                python
                print("Hello World")

                运行时会在控制台输出这段文本。

                <reasoning>
                助手没有使用待办列表，因为这是一个单一、琐碎的任务，一步即可完成。对于这样直接的问题，不需要跟踪多个任务或步骤。
                </reasoning>
                </example>

                <example>
                用户：git status 命令是做什么的？
                助手：git status 命令显示当前工作目录和暂存区的状态。它会显示哪些更改已暂存、哪些未暂存，以及哪些文件未被 Git 跟踪。这是提交前理解仓库当前状态的基础命令。

                <reasoning>
                助手没有使用待办列表，因为这是一个信息性请求，没有实际编码任务要完成。用户只是要求解释，而不是要求助手执行多个步骤或任务。
                </reasoning>
                </example>

                <example>
                用户：你能给 calculateTotal 函数加一段注释来解释它的作用吗？
                助手：可以，我会给 calculateTotal 函数添加注释，解释它的作用。
                * 使用 Edit 工具给 calculateTotal 函数添加注释 *

                <reasoning>
                助手没有使用待办列表，因为这是局限于单个位置的简单直接任务。添加注释不需要跟踪多个步骤或系统化组织。
                </reasoning>
                </example>

                <example>
                用户：帮我运行 npm install，然后告诉我发生了什么。
                助手：我会为你运行 npm install 命令。

                *执行：npm install*

                命令已成功完成。输出如下：
                [npm install 命令输出]

                所有依赖项都已根据 package.json 文件安装。

                <reasoning>
                助手没有使用待办列表，因为这是一个会立即得到结果的单命令执行。没有多个步骤需要跟踪或组织，因此待办列表对这种直接任务没有必要。
                </reasoning>
                </example>

                ## 任务状态和管理

                1. **任务状态**：使用这些状态跟踪进展：
                   - pending：任务尚未开始
                   - in_progress：当前正在处理（限制同时只有一个）
                   - completed：任务成功完成
                   - cancelled：任务不再需要

                2. **任务管理**：
                   - 工作时实时更新任务状态
                   - 完成后立即标记 completed（不要批量完成）
                   - 同一时间只保留一个 in_progress
                   - 开始新任务前完成当前任务
                   - 取消已不相关的任务

                3. **任务拆解**：
                   - 创建具体、可执行的条目
                   - 将复杂任务拆解为更小、可管理的步骤
                   - 使用清晰、描述性的任务名称

                拿不准时，就使用此工具。主动进行任务管理体现专注，并确保你成功完成所有要求。
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> todo = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "id", prop("string", "稳定的待办标识"),
                        "content", prop("string", "待办内容"),
                        "status", prop("string", "状态：pending、in_progress 或 completed"),
                        "priority", prop("string", "优先级：high、medium 或 low")
                ),
                "required", List.of("id", "content", "status", "priority")
        );
        return params(Map.of("todos", arrayProp("更新后的待办列表", todo)), "todos");
    }

    @Override
    public String execute(Map<String, Object> args) {
        Tools.replaceTodos(mapList(args.get("todos")));
        return "待办已更新: " + Tools.todoCount();
    }
}
