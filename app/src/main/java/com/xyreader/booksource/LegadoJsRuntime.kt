package com.xyreader.booksource

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.floor
import org.jsoup.nodes.Element
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 脚本执行结果。
 *
 * - [Ok]：普通返回值，按 JS 习惯转成字符串（数组会被连接）；
 * - [OkElements]：脚本返回的是「句柄数组」——即由 `java.getElementList` 产生的节点句柄，
 *   经 [LegadoJsRuntime] 还原出的节点集合（`bookList`/`chapterList` 这类需要节点序列的规则用）；
 * - [Failed]：脚本报错/越权/超时，附可读原因。
 */
sealed interface JsResult {
    data class Ok(val value: String) : JsResult
    data class OkElements(val elements: List<Element>) : JsResult
    data class Failed(val message: String) : JsResult
}

/** 规则求值里可注入的 JS 执行器（测试可打桩） */
fun interface JsEvaluator {
    fun eval(script: String, initial: String): JsResult
}

/**
 * 暴露给脚本的 `java.*` 子集（Legado 兼容的最小面，按真实源的高频用法选）。
 *
 * 只给函数，不给任何 Java 类——脚本无法通过它触达宿主。
 */
interface JsBridge {
    /** `java.getString(rule)`：按 Legado 规则在当前页面上取值 */
    fun getString(rule: String): String

    /** `java.getStringList(rule)`：同上，返回多条 */
    fun getStringList(rule: String): List<String>

    /**
     * `java.getElementList(rule)`：按 Legado 规则在当前页面上取**节点集合**。
     *
     * 桥只返回节点本身；脚本侧拿到的是句柄序号数组（由 [LegadoJsRuntime] 维护注册表），
     * 整条脚本返回值若全是合法句柄，会被还原成 [JsResult.OkElements]，供 `bookList`/`chapterList` 使用。
     */
    fun getElementList(rule: String): List<Element>

    /** `java.get(key)`：读脚本变量 */
    fun get(key: String): String

    /** `java.put(key, value)`：写脚本变量 */
    fun put(key: String, value: String)

    /** `java.ajax(url)`：取页面（受来源白名单与超时约束由调用方保证） */
    fun ajax(url: String): String

    val baseUrl: String
    val sourceName: String

    /** `java.log(msg)`：诊断输出，不做任何网络/文件写入 */
    fun log(message: String)
}

/**
 * Legado JS 规则沙箱。
 *
 * 安全边界（都落在测试里）：
 * - **解释模式**（`optimizationLevel = -1`）：既能装指令计数器，也避开 Android 上的字节码生成；
 * - **ClassShutter 拒绝一切 Java 类可见**：`java.lang.System`、`new java.io.File(...)`、`Packages.*`
 *   全部取不到；`java` 全局被替换成只含桥函数的对象；
 * - **墙钟超时 + 超时毒化**：脚本在独立线程跑，超时即失败并停用 JS 规则直到重启
 *   （Rhino 1.7.15 的 `observeInstructionCount` 在解释模式下实测不触发，指令上限只能当辅助）；
 * - 不提供 `load/loadClass/readFile/exit/quit/spawn` 等宿主能力（Rhino 裸上下文里本就没有，且类不可见）。
 *
 * **节点集合（`java.getElementList`）**：节点本身**不跨进脚本**——桥把节点留在本次求值的注册表里，
 * 只把**序号（句柄）**放进 JS 数组。脚本返回整条句柄数组时再还原成节点集合 [JsResult.OkElements]。
 * 因此这条路径同样不引入任何 Java 类型可见性。
 *
 * 未做（后续增量）：`java.ajax` 的来源白名单与限速、脚本级超时墙钟、内存上限。
 */
class LegadoJsRuntime(
    private val bridge: JsBridge,
    private val timeoutMs: Long = 3_000L,
    private val maxInstructions: Int = 5_000_000,
) : JsEvaluator {

    /** 单线程执行器：Rhino 的 Context 是线程本地的，必须在同一线程里 enter/exit */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "legado-js").apply { isDaemon = true }
    }

    @Volatile
    private var poisonedReason: String? = null

    /**
     * 执行脚本：在独立线程上跑，超过 [timeoutMs] 直接失败。
     *
     * 超时线程无法被杀（Rhino 不响应中断），因此超时后**停用 JS 规则直到重启**，
     * 避免每个源都泄漏一个空转线程。
     */
    override fun eval(script: String, initial: String): JsResult {
        poisonedReason?.let { return JsResult.Failed(it) }
        // 句柄注册表：本次求值内 `java.getElementList` 注册的节点，句柄即下标（求值结束即丢弃）
        val registry = ArrayList<Element>()
        val future = executor.submit(Callable { evalOnWorker(script, initial, registry) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            val message = "脚本超时（>${timeoutMs}ms），已停用 JS 规则（重启应用可恢复）"
            poisonedReason = message
            JsResult.Failed(message)
        } catch (e: Exception) {
            JsResult.Failed(e.message ?: "脚本执行失败")
        }
    }

    private companion object {
        /** 拒绝一切 Java 类对脚本可见（java.lang.System / new java.io.File(...) 全部不可达） */
        val DENY_ALL_CLASSES = ClassShutter { _ -> false }
    }

    private fun evalOnWorker(script: String, initial: String, registry: MutableList<Element>): JsResult {
        val factory = SandboxContextFactory(maxInstructions)
        val context = factory.enterContext()
        return try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            context.instructionObserverThreshold = 10_000
            // 真签名：Context.setClassShutter(ClassShutter)（实例方法、public；getter 非 public，所以不能用属性语法）
            context.setClassShutter(DENY_ALL_CLASSES)

            val scope = context.initStandardObjects()
            // 用桥对象顶掉 Rhino 自带的 java 包对象；Packages 直接拿掉
            ScriptableObject.putProperty(scope, "java", bridgeObject(context, scope, registry))
            ScriptableObject.putProperty(scope, "Packages", Context.getUndefinedValue())
            ScriptableObject.putProperty(scope, "javax", Context.getUndefinedValue())
            ScriptableObject.putProperty(scope, "result", initial)
            ScriptableObject.putProperty(scope, "baseUrl", bridge.baseUrl)
            ScriptableObject.putProperty(scope, "sourceName", bridge.sourceName)

            val value = context.evaluateString(scope, script, "legado-js-rule", 1, null)
            resolveJsOutcome(value, registry)
        } catch (e: RhinoException) {
            JsResult.Failed(e.details() ?: e.message ?: "脚本执行失败")
        } catch (e: Exception) {
            JsResult.Failed(e.message ?: "脚本执行失败")
        } finally {
            Context.exit()
        }
    }

    /** 把桥函数装成一个纯 JS 对象：脚本只能看到这些函数，看不到任何 Java 类型 */
    private fun bridgeObject(
        context: Context,
        scope: Scriptable,
        registry: MutableList<Element>,
    ): ScriptableObject {
        val obj: ScriptableObject = context.newObject(scope) as ScriptableObject
        fun define(name: String, arity: Int, body: (Array<out Any?>) -> Any?) {
            val fn = object : BaseFunction() {
                override fun getArity(): Int = arity
                override fun getFunctionName(): String = name
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>,
                ): Any = body(args) ?: Context.getUndefinedValue()
            }
            ScriptableObject.putProperty(obj, name, fn)
        }

        define("getString", 1) { args -> bridge.getString(Context.toString(args.getOrNull(0))) }
        define("getStringList", 1) { args ->
            val list = bridge.getStringList(Context.toString(args.getOrNull(0)))
            // 返回 JS 数组（Rhino 的 NativeArray），脚本里可继续 .map/.join
            val context = Context.getCurrentContext()
            val array = context.newArray(scope, list.size)
            list.forEachIndexed { index, value -> ScriptableObject.putProperty(array, index, value) }
            array
        }
        define("getElementList", 1) { args ->
            val elements = bridge.getElementList(Context.toString(args.getOrNull(0)))
            // 返回「句柄数组」：脚本拿到的是序号（可 .map/.filter/.slice），节点本身留在注册表里；
            // 整条返回值若全是合法句柄，会被 resolveJsOutcome 还原成节点集合。
            val context = Context.getCurrentContext()
            val array = context.newArray(scope, elements.size)
            elements.forEachIndexed { index, element ->
                ScriptableObject.putProperty(array, index, registry.size)
                registry.add(element)
            }
            array
        }
        define("get", 1) { args -> bridge.get(Context.toString(args.getOrNull(0))) }
        define("put", 2) { args ->
            bridge.put(Context.toString(args.getOrNull(0)), Context.toString(args.getOrNull(1)))
            null
        }
        define("ajax", 1) { args -> bridge.ajax(Context.toString(args.getOrNull(0))) }
        define("log", 1) { args ->
            bridge.log(Context.toString(args.getOrNull(0)))
            null
        }
        return obj
    }

    /**
     * 把脚本返回值落成结果。
     *
     * 若返回的是「句柄数组」（非空、且每一项都是注册表内的合法整数句柄），还原成节点集合 [JsResult.OkElements]；
     * 否则按文本处理（数组仍按 JS 习惯连接）——这样**文本级 JS 的既有行为完全不变**。
     */
    private fun resolveJsOutcome(value: Any?, registry: List<Element>): JsResult {
        val elements = resolveHandles(value, registry)
        if (elements != null) return JsResult.OkElements(elements)
        return JsResult.Ok(Context.toString(value).orEmpty())
    }

    /**
     * 只有「全部为合法句柄的数组」才还原；任一越界 / 非整数 / 空数组 → null（退回文本）。
     *
     * 只会误判到一种脚本：它既调用了 `java.getElementList`，又主动返回一个纯数字数组。
     * 未调用 `getElementList` 时注册表为空，任何返回值都不会被当成句柄。
     */
    private fun resolveHandles(value: Any?, registry: List<Element>): List<Element>? {
        if (value !is NativeArray || registry.isEmpty()) return null
        val length = value.length.toInt()
        if (length <= 0) return null
        val resolved = ArrayList<Element>(length)
        for (index in 0 until length) {
            val number = (value.get(index, value) as? Number)?.toDouble() ?: return null
            if (number != floor(number) || number < 0 || number >= registry.size) return null
            resolved.add(registry[number.toInt()])
        }
        return resolved
    }

    /** 超指令数直接抛异常中止脚本（Rhino 在解释模式下会周期性回调） */
    private class SandboxContextFactory(private val maxInstructions: Int) : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().also {
            it.optimizationLevel = -1
            it.languageVersion = Context.VERSION_ES6
            it.instructionObserverThreshold = 10_000
        }

        override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
            if (instructionCount > maxInstructions) {
                throw Error("脚本执行超出上限（${maxInstructions} 条指令），已中止")
            }
        }

    }

}
