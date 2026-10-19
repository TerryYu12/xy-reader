package com.xyreader.booksource

import org.jsoup.nodes.Element

/**
 * 把「当前页面 + 规则求值 + 取页面」包装成脚本可用的 `java.*` 桥。
 *
 * 约定：
 * - `java.getString(rule)` / `java.getStringList(rule)` 在当前页面的**根节点**上求值（Legado 的默认语义）；
 * - `java.getElementList(rule)` 同样在根节点上求值，返回节点集合（脚本侧看到的是句柄数组）；
 * - 变量（`java.put/get`）只在**一次求值内**有效（每次调用新建），不跨页面、不落盘；
 * - `java.ajax(url)` 复用来源的请求实现与 header；失败返回空串（脚本可自行判断）。
 *
 * 不含任何凭据：本阶段不支持登录源，header 来自来源配置本身。
 */
class PageJsBridge(
    private val root: RuleValue,
    private val base: LegadoRuleEvaluator,
    override val baseUrl: String,
    override val sourceName: String,
    private val fetch: (String) -> String,
    private val logger: (String) -> Unit = {},
) : JsBridge {

    private val variables = HashMap<String, String>()

    override fun getString(rule: String): String =
        base.firstString(LegadoRule.parse(rule), root).orEmpty()

    override fun getStringList(rule: String): List<String> =
        base.strings(LegadoRule.parse(rule), root)

    override fun getElementList(rule: String): List<Element> =
        base.elements(LegadoRule.parse(rule), root)

    override fun get(key: String): String = variables[key].orEmpty()

    override fun put(key: String, value: String) {
        variables[key] = value
    }

    override fun ajax(url: String): String = fetch(url).orEmpty()

    override fun log(message: String) {
        logger(message)
    }
}
