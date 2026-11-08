package com.github.epsilon.settings.impl

import com.github.epsilon.assets.i18n.EpsilonTranslations
import com.github.epsilon.assets.i18n.TranslateComponent
import com.github.epsilon.settings.Setting
import java.util.Collections
import java.util.EnumSet

/**
 * 多选枚举设置。
 *
 * 值始终按枚举声明顺序保存，并且只包含声明过的常量；外部拿到的是不可变集合，
 * 修改必须走 [toggle] / [setValueFromNames] 等入口，以便触发 [onChanged] 与 UI 监听。
 */
class MultiEnumSetting<E : Enum<E>>(
    name: String,
    enumClass: Class<E>,
    defaultValue: Collection<E>,
    visible: Visibility?,
    onChanged: ((Set<E>) -> Unit)?,
) : Setting<Set<E>>(name, visible, onChanged) {

    private val modeTranslations: MutableMap<E, TranslateComponent> = HashMap()

    private val constants: Array<E> = enumClass.enumConstants
        ?: throw IllegalArgumentException("${enumClass.name} 不是枚举类型")

    /** 全部可选常量，按声明顺序。 */
    val modes: List<E> = constants.asList()

    init {
        initializeValue(immutable(defaultValue))
    }

    override var value: Set<E>
        get() = super.value
        set(newValue) {
            writeValue(immutable(newValue))
        }

    override fun setValueSilently(newValue: Set<E>) {
        writeValueSilently(immutable(newValue))
    }

    override fun initTranslateComponent(component: TranslateComponent) {
        super.initTranslateComponent(component)
        modeTranslations.clear()
        for (mode in constants) {
            modeTranslations[mode] = component.createChild(mode.toString().lowercase())
        }
    }

    fun `is`(mode: E?): Boolean = mode != null && value.contains(mode)

    fun contains(mode: E): Boolean = `is`(mode)

    fun toggle(mode: E?) {
        if (mode == null) return
        val next = toEnumSet(value)
        if (!next.remove(mode)) next.add(mode)
        value = next
    }

    fun selectAll() {
        if (value.size == constants.size) return
        value = EnumSet.allOf(constants.first().declaringJavaClass)
    }

    fun clear() {
        if (value.isEmpty()) return
        value = emptySet()
    }

    /** 供 UI 层在 `MultiEnumSetting<*>` 上使用的不定型查询。 */
    fun containsUnchecked(mode: Enum<*>?): Boolean {
        if (mode == null) return false
        for (candidate in constants) {
            if (candidate === mode) return value.contains(candidate)
        }
        return false
    }

    /** 供 UI 层在 `MultiEnumSetting<*>` 上使用的不定型切换。 */
    fun toggleUnchecked(mode: Enum<*>?) {
        if (mode == null) return
        for (candidate in constants) {
            if (candidate === mode) {
                toggle(candidate)
                return
            }
        }
    }

    fun setValueFromNames(names: Collection<String>?) {
        value = parseNames(names)
    }

    fun setValueFromNamesSilently(names: Collection<String>?) {
        setValueSilently(parseNames(names))
    }

    /** 按枚举声明顺序输出 [Enum.toString]，用于配置持久化。 */
    fun getNames(): List<String> {
        val names = ArrayList<String>(value.size)
        for (mode in constants) {
            if (value.contains(mode)) names.add(mode.toString())
        }
        return names
    }

    fun getTranslatedValue(mode: E): String =
        modeTranslations[mode]?.getTranslatedName() ?: mode.toString()

    fun getTranslatedValueByIndex(index: Int): String {
        if (index !in constants.indices) return ""
        return getTranslatedValue(constants[index])
    }

    fun getTranslatedValueUnchecked(mode: Enum<*>?): String {
        if (mode != null) {
            for (candidate in constants) {
                if (candidate === mode) return getTranslatedValue(candidate)
            }
        }
        return mode?.toString() ?: ""
    }

    /** 摘要：未选择为 "-"，最多两项拼接翻译名，更多显示计数。 */
    fun getTranslatedSummary(): String {
        if (value.isEmpty()) return "-"
        if (value.size > 2) {
            return value.size.toString() + EpsilonTranslations.Gui.LIST_SELECTED.getTranslatedName()
        }
        val summary = StringBuilder()
        for (mode in constants) {
            if (!value.contains(mode)) continue
            if (summary.isNotEmpty()) summary.append(", ")
            summary.append(getTranslatedValue(mode))
        }
        return summary.toString()
    }

    private fun immutable(source: Collection<E>?): Set<E> =
        Collections.unmodifiableSet(toEnumSet(source))

    private fun toEnumSet(source: Collection<E>?): EnumSet<E> {
        val result = EnumSet.noneOf(constants.first().declaringJavaClass)
        if (source != null) {
            for (entry in source) {
                if (entry != null) result.add(entry)
            }
        }
        return result
    }

    private fun parseNames(names: Collection<String>?): Set<E> {
        val result = EnumSet.noneOf(constants.first().declaringJavaClass)
        if (names != null) {
            for (name in names) {
                for (mode in constants) {
                    if (matchesMode(mode, name)) {
                        result.add(mode)
                        break
                    }
                }
            }
        }
        return Collections.unmodifiableSet(result)
    }

    private fun matchesMode(enumValue: E, mode: String): Boolean {
        return enumValue.name == mode ||
                enumValue.toString() == mode ||
                enumValue.name.equals(mode, ignoreCase = true) ||
                enumValue.toString().equals(mode, ignoreCase = true)
    }

    companion object {

        /** 从默认值推断枚举类型；空集合无法推断，此时必须显式传入 `Class<E>`。 */
        fun <E : Enum<E>> enumClassOf(values: Collection<E>): Class<E> {
            for (value in values) {
                if (value != null) return value.declaringJavaClass
            }
            throw IllegalArgumentException("无法从空默认值推断枚举类型")
        }

    }

}
