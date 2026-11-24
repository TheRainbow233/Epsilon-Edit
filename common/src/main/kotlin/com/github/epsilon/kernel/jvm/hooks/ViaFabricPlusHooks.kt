package com.github.epsilon.kernel.jvm.hooks

import com.github.epsilon.Constants
import com.github.epsilon.kernel.state.MutableState
import com.github.epsilon.kernel.state.StateTracking
import com.github.epsilon.kernel.state.mutableStateOf
import net.minecraft.SharedConstants
import net.minecraft.network.protocol.PacketType
import java.lang.reflect.InvocationTargetException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object ViaFabricPlusHooks {
    private val classes = OptionalClassCache()
    private val warnings = ConcurrentHashMap.newKeySet<String>()
    private val protocolPattern = Regex("[0-9_]+to[0-9_]+")

    val isViaEnabled: Boolean
        get() = query { backend != null } ?: false

    val currentVersion: SupportVersion
        get() = viaVersion ?: SupportVersion.parse(SharedConstants.getCurrentVersion().name())

    /**
     * 严格的版本限制使用此入口；未加载或查询失败时返回 null。
     */
    private val uiVersion: MutableState<SupportVersion?> = mutableStateOf(null)

    fun refreshUiVersion() {
        uiVersion.value = queryVersion()
    }

    val viaVersion: SupportVersion?
        get() {
            val published = uiVersion.value
            return if (StateTracking.isBuildingUi) published else queryVersion()
        }

    private fun queryVersion(): SupportVersion? = query {
        val target = backend?.targetVersion ?: return@query null
        val versions = type("com.viaversion.viaversion.api.protocol.version.ProtocolVersion")
            .getMethod("getIncludedVersions").invoke(target) as Collection<*>
        // 某些协议会带 snapshot/pre 名称；跳过无法解析的项，避免整个查询失败后回退。
        versions.mapNotNull { value ->
            (value as? String)?.let { name -> runCatching { SupportVersion.parse(name) }.getOrNull() }
        }.minOrNull()
    }

    val supportsEndTick: Boolean
        get() = currentVersion >= SupportVersion(21, 2)

    val supportsDupRot: Boolean
        get() = viaVersion?.let { it <= SupportVersion(20, 7) } ?: false

    val supportsInstaSneak: Boolean
        get() = currentVersion >= SupportVersion(21, 6)

    fun createViaPacket(): ViaPacketWrapper {
        val current = backend ?: throw UnsupportedOperationException("ViaFabricPlus is not available")
        return ViaPacketWrapper(current.connection)
    }

    data class SupportVersion(val major: Int, val minor: Int) : Comparable<SupportVersion> {
        override fun compareTo(other: SupportVersion): Int =
            compareValuesBy(this, other, SupportVersion::major, SupportVersion::minor)

        override fun toString(): String = if (major >= 26) "$major.$minor" else "1.$major.$minor"

        companion object {
            private val releasePattern = Regex("(?:1\\.)?\\d+\\.\\d+|1\\.\\d+")

            fun parse(value: String): SupportVersion {
                require(releasePattern.matches(value)) { "Unsupported release version: $value" }
                val parts = value.split('.').map(String::toInt)
                return when (parts.first()) {
                    1 -> SupportVersion(parts[1], parts.getOrElse(2) { 0 })
                    else -> SupportVersion(parts[0], parts[1])
                }
            }
        }
    }

    class ViaPacketWrapper internal constructor(private val connection: Any) {
        private val wrapperType = type("com.viaversion.viaversion.api.protocol.packet.PacketWrapper")
        private val packetType = type("com.viaversion.viaversion.api.protocol.packet.PacketType")
        private var delegate: Any? = null
        private val packet: Any
            get() = checkNotNull(delegate) { "Set packet type before writing or sending" }

        fun writePacketType(protocolVersion: String, packet: PacketType<*>): ViaPacketWrapper =
            writePacketType(protocolVersion, packet.id().path)

        fun writePacketType(protocolVersion: String, packetName: String): ViaPacketWrapper = apply {
            val version = normalizeProtocol(protocolVersion)
            val higher = version.substringAfter("to")
            val name = packetName.uppercase(Locale.ROOT)
            // 只选服务端接收的包，避免客户端包的同名枚举覆盖它。
            val enumType = type("com.viaversion.viaversion.protocols.v$version.packet.ServerboundPackets$higher")
            val selected = enumType.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
                ?: throw IllegalArgumentException("Unknown serverbound packet $packetName for $version")
            delegate = delegate?.also {
                wrapperType.getMethod("setPacketType", packetType).invoke(it, selected)
            } ?: wrapperType.getMethod(
                "create", packetType, type("com.viaversion.viaversion.api.connection.UserConnection")
            ).invoke(null, selected, connection)
        }

        fun write(typeName: String, value: Any?): ViaPacketWrapper = apply {
            val packet = packet
            val field = type("com.viaversion.viaversion.api.type.Types").getField(typeName.uppercase(Locale.ROOT))
            wrapperType.getMethod("write", type("com.viaversion.viaversion.api.type.Type"), Any::class.java)
                .invoke(packet, field.get(null), value)
        }

        fun scheduleSendToServer(protocol: String, skipPipeline: Boolean) =
            send("scheduleSendToServer", protocol, skipPipeline)

        fun sendToServer(protocol: String, skipPipeline: Boolean) =
            send("sendToServer", protocol, skipPipeline)

        fun sendRaw(currentThread: Boolean) {
            checkConnection()
            invokeSend(if (currentThread) "sendToServerRaw" else "scheduleSendToServerRaw")
        }

        private fun send(method: String, protocol: String, skipPipeline: Boolean) {
            val version = normalizeProtocol(protocol)
            val protocolClass =
                type("com.viaversion.viaversion.protocols.v$version.Protocol${version.replace("to", "To")}")
            checkConnection()
            invokeSend(
                method,
                arrayOf(Class::class.java, Boolean::class.javaPrimitiveType!!),
                protocolClass, skipPipeline
            )
        }

        private fun checkConnection() {
            check(backend?.connection === connection) { "Via play connection changed; create a new packet" }
        }

        private fun invokeSend(method: String, parameters: Array<Class<*>> = emptyArray(), vararg arguments: Any) {
            try {
                wrapperType.getMethod(method, *parameters).invoke(packet, *arguments)
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("Via packet $method failed", e.targetException)
            }
        }
    }

    private class Backend(
        private val targetVersionProvider: () -> Any,
        private val connectionProvider: () -> Any?,
    ) {
        val targetVersion: Any
            get() = targetVersionProvider()

        val connection: Any
            get() = checkNotNull(connectionProvider()) {
                "Via has no active play connection"
            }
    }

    // 每次获取当前实例，避免在 Via 尚未初始化时永久缓存为不可用。
    private val backend: Backend?
        get() {
            // ViaFabricPlus 5.x：ViaFabricPlus.api() -> ViaFabricPlusAPI.targetVersion() / userConnection()
            val viaFabricPlus = optionalType("com.viaversion.viafabricplus.ViaFabricPlus")
            if (viaFabricPlus != null) {
                val api = runCatching {
                    viaFabricPlus.getMethod("api").invoke(null)
                }.getOrNull()
                if (api != null) {
                    return Backend(
                        targetVersionProvider = {
                            api.javaClass.getMethod("targetVersion").invoke(api)
                        },
                        connectionProvider = {
                            api.javaClass.getMethod("userConnection").invoke(api)
                        }
                    )
                }
            }

            // 旧版 ViaFabricPlus：ViaFabricPlus.getImpl() -> ViaFabricPlusBase.getTargetVersion()
            val impl = viaFabricPlus?.let { type ->
                runCatching { type.getMethod("getImpl").invoke(null) }.getOrNull()
            }
            if (impl != null) {
                return Backend(
                    targetVersionProvider = { impl.javaClass.getMethod("getTargetVersion").invoke(impl) },
                    connectionProvider = { impl.javaClass.getMethod("getPlayNetworkUserConnection").invoke(impl) }
                )
            }

            // 更老的 ProtocolTranslator 入口
            val translator = optionalType("de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator")
                ?: return null
            return Backend(
                targetVersionProvider = { translator.getMethod("getTargetVersion").invoke(null) },
                connectionProvider = { translator.getMethod("getPlayNetworkUserConnection").invoke(null) }
            )
        }

    private fun type(name: String): Class<*> = classes.find(name) ?: throw ClassNotFoundException(name)

    private fun optionalType(name: String): Class<*>? = classes.find(name)

    private inline fun <T> query(action: () -> T): T? = try {
        action()
    } catch (e: ReflectiveOperationException) {
        queryFailed(e)
    } catch (e: LinkageError) {
        queryFailed(e)
    } catch (e: IllegalArgumentException) {
        queryFailed(e)
    } catch (e: IllegalStateException) {
        queryFailed(e)
    }

    private fun queryFailed(error: Throwable): Nothing? {
        if (warnings.add("${error.javaClass.name}:${error.message}")) {
            Constants.LOGGER.warn("Via compatibility query failed", error)
        }
        return null
    }

    private fun normalizeProtocol(protocol: String): String =
        protocol.lowercase(Locale.ROOT).removePrefix("v").also {
            require(protocolPattern.matches(it)) { "Invalid Via protocol: $protocol" }
        }
}
