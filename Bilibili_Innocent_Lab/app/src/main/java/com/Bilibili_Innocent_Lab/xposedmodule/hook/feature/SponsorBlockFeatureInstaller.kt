package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** SponsorBlock-compatible automatic skipping, adapted from the browser extension. */
internal class SponsorBlockFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = listOf(CAPABILITY)

    private val client = SponsorBlockClient()
    private val sessions = WeakHashMap<Any, Session>()
    private val sessionsLock = Any()
    private val hookedPlayers = hashSetOf<Class<*>>()
    private val hookedListeners = hashSetOf<Class<*>>()
    private val hooksLock = Any()
    private val seeking = ThreadLocal<Boolean>().apply { set(false) }

    private data class Session(
        val videoId: String,
        val segments: List<SponsorBlockSegment>,
        val skipped: MutableSet<SponsorBlockSegment> = hashSetOf(),
        var positionObserved: Boolean = false,
        var lastPositionSeconds: Double? = null
    )

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            debug(environment, "install", "skipped: disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            debug(environment, "install", "skipped: process=${environment.processName}")
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: run {
            debug(environment, "install", "skipped: missing class loader")
            return FeatureInstallResult.Skipped("missing-class-loader")
        }
        val point = PlayerSpeedSessionLocator.prepared(loader)
            ?: run {
                debug(environment, "install", "skipped: prepared player point not found")
                return FeatureInstallResult.Skipped("missing-prepared-player")
            }
        debug(environment, "install", "prepared player point found: ${point.register.declaringClass.name}")
        val installed = AtomicBoolean(false)
        val register = point.register
        environment.registrar.exact(ID + ".prepared_registration", register.declaringClass,
            register.name, *register.parameterTypes) {
            after {
                if (hasThrowable) return@after
                instance ?: return@after
                val listener = argOrNull(0) ?: return@after
                debug(environment, "prepared_registration", "listener received: ${listener.javaClass.name}")
                runCatching {
                    val ownerField = Lookup.declaredFields(listener.javaClass, true) {
                        !it.isStatic && it.type isSubclassOf point.core
                    }.firstOrNull() ?: run {
                        debug(environment, "prepared_registration", "core owner field not found")
                        return@runCatching
                    }
                    val callback = Lookup.methodOrNull(listener.javaClass, "onPrepared", point.player)
                        ?.takeIf { !it.isStatic && !it.isAbstract && it.returnType == Void.TYPE }
                        ?: run {
                            debug(environment, "prepared_registration", "onPrepared callback not found on listener")
                            return@runCatching
                        }
                    debug(environment, "prepared_registration", "callback found: ${callback.declaringClass.name}#${callback.name}")
                    synchronized(hooksLock) {
                        if (callback.declaringClass !in hookedListeners) {
                            installPreparedCallback(environment, point, ownerField, callback)
                            hookedListeners += callback.declaringClass
                        }
                    }
                    installed.set(true)
                }.onFailure {
                    environment.logError(ID, "[BIL] [DEBUG-SB] prepared callback installation failed: $it")
                }
            }
        }
        environment.reportCapability(CAPABILITY, FeatureInstallResult.Installed(1))
        environment.reportStatus(CHANNEL, "awaiting-prepared")
        debug(environment, "install", "prepared registration installed; waiting for player callback")
        return FeatureInstallResult.Installed(1, complete = installed.get())
    }

    private fun installPreparedCallback(
        environment: HookEnvironment,
        point: PlayerSpeedSessionLocator.Prepared,
        ownerField: Field,
        callback: Method
    ) {
        environment.registrar.exact(ID + ".on_prepared." + callback.declaringClass.name,
            callback.declaringClass, callback.name, *callback.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val player = argOrNull(0) ?: return@after
                runCatching {
                    // IMediaPlayer is an abstract API. Hook the concrete implementation
                    // returned by the prepared callback; API 102 rejects abstract methods.
                    installPlayerHooks(environment, player.javaClass)
                    val core = instance?.let(ownerField::get) ?: return@runCatching
                    val item = point.item.invoke(core) ?: return@runCatching
                    val rawId = point.itemId.invoke(item) as? String
                    if (rawId.isNullOrBlank()) {
                        debug(environment, "on_prepared", "media id is empty")
                        return@runCatching
                    }
                    val videoId = extractVideoId(rawId)
                    if (videoId == null) {
                        debug(environment, "on_prepared", "media id conversion failed: raw type=${rawId.substring(0, minOf(rawId.length, 32))}")
                        return@runCatching
                    }
                    debug(environment, "on_prepared", "video identified: bvid=$videoId")
                    val old = synchronized(sessionsLock) { sessions[player]?.videoId }
                    if (old == videoId) {
                        debug(environment, "on_prepared", "same video already active: bvid=$videoId")
                        return@runCatching
                    }
                    // Publish the new session before starting the async request. A fast
                    // cache/network callback must not be discarded by a missing session.
                    synchronized(sessionsLock) { sessions[player] = Session(videoId, emptyList()) }
                    debug(environment, "on_prepared", "session initialized: bvid=$videoId")
                    client.loadAsync(videoId, null, { segments ->
                        synchronized(sessionsLock) {
                            if (sessions[player]?.videoId == videoId) {
                                sessions[player] = Session(videoId, segments)
                                debug(environment, "on_prepared", "segments attached: bvid=$videoId count=${segments.size}")
                            }
                        }
                    }, log = { message -> debug(environment, "client", message) })
                    environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
                }.onFailure { environment.logError(ID + ".on_prepared", "[BIL] [DEBUG-SB] reading video identity failed: $it") }
            }
        }
    }

    private fun installPlayerHooks(environment: HookEnvironment, playerClass: Class<*>) {
        synchronized(hooksLock) {
            if (!hookedPlayers.add(playerClass)) return
        }
        val position = Lookup.inheritedMethodOrNull(playerClass, "getCurrentPosition")
            ?.takeIf { !it.isStatic && it.parameterCount == 0 &&
                (it.returnType == classOf<Long>() || it.returnType == classOf<Int>()) }
        val seek = Lookup.inheritedMethodOrNull(playerClass, "seekTo", classOf<Long>())
            ?.takeIf { !it.isStatic && it.parameterCount == 1 && it.returnType == Void.TYPE }
            ?: Lookup.inheritedMethodOrNull(playerClass, "seekTo", classOf<Int>())
                ?.takeIf { !it.isStatic && it.parameterCount == 1 && it.returnType == Void.TYPE }
        if (position == null) {
            debug(environment, "player_hooks", "getCurrentPosition not found: ${playerClass.name}")
        }
        if (seek == null) {
            debug(environment, "player_hooks", "seekTo not found: ${playerClass.name}")
        }
        if (position == null || seek == null) return
        debug(environment, "player_hooks", "position hook installed: ${position.declaringClass.name}#${position.name}; seek=${seek.name}(${seek.parameterTypes[0].simpleName})")
        environment.registrar.exact(ID + ".position", position.declaringClass,
            position.name, *position.parameterTypes) {
            after {
                if (hasThrowable || seeking.get() == true) return@after
                val player = instance ?: return@after
                val seconds = when (val value = result) {
                    is Long -> value / 1000.0
                    is Int -> value / 1000.0
                    else -> return@after
                }
                synchronized(sessionsLock) {
                    val session = sessions[player]
                    if (session != null) {
                        val previousPosition = session.lastPositionSeconds
                        if (SponsorBlockDecision.isRewind(previousPosition, seconds)) {
                            session.skipped.clear()
                            debug(environment, "position", "rewind detected: bvid=${session.videoId} from=${"%.2f".format(previousPosition)} to=${"%.2f".format(seconds)}; skip state reset")
                        }
                        session.lastPositionSeconds = seconds
                    }
                    if (session != null && !session.positionObserved) {
                        session.positionObserved = true
                        debug(environment, "position", "first position observed: bvid=${session.videoId} seconds=${"%.2f".format(seconds)} segments=${session.segments.size}")
                    }
                }
                val target = synchronized(sessionsLock) {
                    val session = sessions[player] ?: return@synchronized null
                    val segment = SponsorBlockDecision.segmentAt(session.segments, seconds)
                        ?: return@synchronized null
                    if (!session.skipped.add(segment)) return@synchronized null
                    segment
                } ?: return@after
                val endMillis = (target.endSeconds * 1000.0).toLong()
                debug(environment, "skip", "segment hit: category=${target.category} start=${target.startSeconds} end=${target.endSeconds}; seeking to ${endMillis}ms")
                runCatching {
                    seeking.set(true)
                    if (seek.parameterTypes[0] == classOf<Long>()) seek.invoke(player, endMillis)
                    else seek.invoke(player, endMillis.toInt())
                    environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
                }.onFailure { environment.logError(ID + ".seek", "[BIL] [DEBUG-SB] SponsorBlock seek failed: $it") }
                    .also { seeking.set(false) }
            }
        }
    }

    private fun debug(environment: HookEnvironment, key: String, message: String) {
        environment.logInfo("$ID.$key", "[DEBUG-SB] $message")
    }

    private fun extractVideoId(raw: String): String? {
        val value = raw.trim()
        Regex("BV1[a-zA-Z0-9]{9}").find(value)?.value?.let { return it }
        return BilibiliVideoIdCodec.toBvid(value)
    }

    companion object {
        const val ID = "sponsor_block"
        const val CAPABILITY = "player_sponsor_block"
        private const val CHANNEL = "sponsor_block_status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
