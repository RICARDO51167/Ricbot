package ricbot.tool.api;

import java.util.*;

/**
 * 所有工具的抽象基类
 *
 * 主要目标：
 * 1. 提供统一 schema 导出
 * 2. 提供统一参数校验入口
 * 3. 让 ToolRegistry 能按名称执行工具
 *
 * 这相当于 Python 里 Tool 基类的 Java 版。
 */
public abstract class Tool {

    /**
     * 工具名，必须唯一
     */
    public abstract String getName();

    /**
     * 工具描述
     */
    public abstract String getDescription();

    /**
     * 工具参数定义
     *
     * 子类按需覆盖；默认无参。
     */
    public List<ToolParam> getParams() {
        // 返回一个不可变的空列表，表示默认没有参数
        return List.of();
    }

    /**
     * 是否只读工具
     */
    public boolean isReadOnly() {
        // 默认不是只读工具，子类可重写
        return false;
    }

    /**
     * 是否独占执行
     *
     * 例如 exec 这类工具通常更适合串行执行。
     */
    public boolean isExclusive() {
        // 默认不独占执行，子类可重写
        return false;
    }

    /**
     * 将参数做基础类型转换。
     *
     * 默认原样返回。
     * 你后续如果需要更复杂的 cast，可以在子类重写。
     */
    public Map<String, Object> castParams(Map<String, Object> params) {
        // 如果传入参数为 null，则返回一个新的空的 LinkedHashMap，否则原样返回
        return params != null ? params : new LinkedHashMap<>();
    }

    /**
     * 参数校验。
     *
     * 返回错误列表；为空表示通过。
     */
    public List<String> validateParams(Map<String, Object> params) {
        // 初始化错误列表
        List<String> errors = new ArrayList<>();
        // 如果传入参数为 null，则使用空 Map，否则使用传入的参数
        Map<String, Object> actual = params != null ? params : Collections.emptyMap();

        // 遍历所有定义的参数
        for (ToolParam param : getParams()) {
            // 检查必填参数是否存在
            if (param.isRequired() && !actual.containsKey(param.getName())) {
                // 如果必填参数缺失，添加错误信息并跳过当前参数的后续检查
                errors.add("缺少必填参数 '" + param.getName() + "'");
                continue;
            }

            // 如果参数不存在且非必填，则跳过
            if (!actual.containsKey(param.getName())) {
                continue;
            }

            // 获取参数的实际值
            Object value = actual.get(param.getName());
            // 获取参数的预期类型
            String expectedType = param.getType();

            // 检查实际值的类型是否与预期类型匹配
            if (!ToolParam.typeMatches(expectedType, value)) {
                // 如果类型不匹配，添加错误信息
                errors.add("参数 '" + param.getName() + "' 的类型应为 " + expectedType);
            }
        }

        // 返回错误列表，如果没有错误则列表为空
        return errors;
    }

    /**
     * 导出 OpenAI function-calling 风格 schema
     *
     * 对应 Python: to_schema()
     */
    public Map<String, Object> toSchema() {
        // 初始化属性映射
        Map<String, Object> properties = new LinkedHashMap<>();
        // 初始化必填参数列表
        List<String> required = new ArrayList<>();

        // 遍历所有参数，构建属性和必填列表
        for (ToolParam param : getParams()) {
            // 将参数的 schema 添加到属性映射中
            properties.put(param.getName(), param.toSchema());
            // 如果参数是必填的，添加到必填列表
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        // 构建 parameters 对象
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object"); // 参数类型为对象
        parameters.put("properties", properties); // 设置属性定义
        // 如果有必填参数，则添加 required 字段
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        // 构建 function 对象
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", getName()); // 设置函数名
        function.put("description", getDescription()); // 设置函数描述
        function.put("parameters", parameters); // 设置参数定义

        // 构建最终的 schema 对象
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function"); // 类型为 function
        schema.put("function", function); // 设置 function 详情

        // 返回生成的 schema
        return schema;
    }

    /**
     * 执行工具的具体逻辑
     *
     * @param params 执行参数
     * @return 执行结果
     * @throws Exception 执行异常
     */
    public Object execute(Map<String, Object> params) throws Exception {
        // 默认抛出异常，强制子类实现具体的执行逻辑
        throw new UnsupportedOperationException("工具 '" + getName() + "' 未实现 execute(Map) 方法。");
    }
}
