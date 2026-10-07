package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Adds a small player button that copies the current video's subtitle text. */
internal class SubtitleExportFeatureInstaller : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> get() = listOf(CAPABILITY)

    private val client = SubtitleExportClient()
    private val lifecycleInstalled = AtomicBoolean(false)
    private val stateLock = Any()
    private val buttons = WeakHashMap<Activity, TextView>()
    private var resumedActivity: WeakReference<Activity>? = null
    private var sessionActivity: WeakReference<Activity>? = null
    private var sessionVideoId: String? = null
    private var sessionCid: Long? = null
    private val hookedListeners = hashSetOf<Class<*>>()
    private val hooksLock = Any()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != TARGET_PACKAGE) {
            environment.logInfo(ID, "skipped: process=${environment.processName}")
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val context = environment.hostContext
            ?: return FeatureInstallResult.Skipped("missing-host-context")
        val loader = environment.classLoader
            ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val point = PlayerSpeedSessionLocator.prepared(loader)
            ?: return FeatureInstallResult.Skipped("missing-prepared-player")
        installLifecycle(context, environment)
        val installed = AtomicBoolean(false)
        val register = point.register
        environment.registrar.exact(ID + ".prepared_registration", register.declaringClass,
            register.name, *register.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val listener = argOrNull(0) ?: return@after
                runCatching {
                    val ownerField = Lookup.declaredFields(listener.javaClass, true) {
                        !it.isStatic && it.type isSubclassOf point.core
                    }.firstOrNull() ?: return@runCatching
                    val callback = Lookup.methodOrNull(listener.javaClass, "onPrepared", point.player)
                        ?.takeIf { !it.isStatic && it.returnType == Void.TYPE }
                        ?: return@runCatching
                    synchronized(hooksLock) {
                        if (hookedListeners.add(callback.declaringClass)) {
                            installPreparedCallback(environment, point, ownerField, callback)
                        }
                    }
                    installed.set(true)
                    environment.logInfo(ID, "prepared callback installed: ${callback.declaringClass.name}")
                }.onFailure { environment.logError(ID, "[BIL] 字幕导出准备回调安装失败: $it") }
            }
        }
        environment.reportCapability(CAPABILITY, FeatureInstallResult.Installed(1, installed.get()))
        environment.reportStatus(CHANNEL, "awaiting-prepared")
        return FeatureInstallResult.Installed(1, complete = installed.get())
    }

    private fun installLifecycle(context: android.content.Context, environment: HookEnvironment) {
        val app = (context.applicationContext as? Application) ?: return
        if (!lifecycleInstalled.compareAndSet(false, true)) return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                synchronized(stateLock) {
                    resumedActivity = WeakReference(activity)
                    if (sessionVideoId != null && sessionActivity == null) {
                        sessionActivity = WeakReference(activity)
                    }
                }
                val shouldAttach = synchronized(stateLock) {
                    sessionVideoId != null && sessionActivity?.get() === activity &&
                        activity !in buttons
                }
                if (shouldAttach) {
                    val identity = synchronized(stateLock) {
                        sessionVideoId to sessionCid
                    }
                    val videoId = identity.first ?: return
                    attachButton(activity, videoId, identity.second, environment)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                synchronized(stateLock) {
                    if (resumedActivity?.get() === activity) resumedActivity = null
                }
                removeButton(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(stateLock) {
                    if (sessionActivity?.get() === activity) {
                        sessionActivity = null
                        sessionVideoId = null
                        sessionCid = null
                    }
                    if (resumedActivity?.get() === activity) resumedActivity = null
                }
                removeButton(activity)
            }

            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        environment.logInfo(ID, "activity lifecycle registered")
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
                val core = instance?.let { ownerField.get(it) } ?: return@after
                val item = runCatching { point.item.invoke(core) }.getOrNull() ?: return@after
                val rawId = runCatching { point.itemId.invoke(item) as? String }.getOrNull()
                    ?: return@after
                val identity = extractVideoIdentity(rawId) ?: return@after
                val post = environment.postToMain ?: return@after
                post {
                    synchronized(stateLock) {
                        buttons.keys.toList().forEach { removeButton(it) }
                        sessionVideoId = identity.first
                        sessionCid = identity.second
                        sessionActivity = resumedActivity
                    }
                    val activity = synchronized(stateLock) { resumedActivity?.get() }
                    if (activity != null) attachButton(activity, identity.first, identity.second, environment)
                    environment.logInfo(ID, "video prepared: bvid=${identity.first} cid=${identity.second ?: "unknown"}")
                }
            }
        }
    }

    private fun attachButton(activity: Activity, videoId: String, cid: Long?, environment: HookEnvironment) {
        if (activity.isFinishing) return
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        if (buttons[activity]?.parent === root) return
        removeButton(activity)
        val button = TextView(activity).apply {
            text = "导出字幕"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 18).toFloat()
                setColor(Color.argb(190, 20, 20, 20))
                setStroke(dp(activity, 1), Color.argb(90, 255, 255, 255))
            }
        }
        button.setOnClickListener { export(activity, button, videoId, cid, environment) }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, 36),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(activity, 48)
            marginEnd = dp(activity, 12)
        }
        root.addView(button, params)
        buttons[activity] = button
        environment.logInfo(ID, "button attached: bvid=$videoId")
    }

    private fun export(activity: Activity, button: TextView, videoId: String, cid: Long?, environment: HookEnvironment) {
        if (!button.isEnabled) return
        button.isEnabled = false
        button.text = "获取中…"
        client.loadAsync(videoId, cid = cid, callback = { result ->
            val post = environment.postToMain ?: return@loadAsync
            post {
                if (activity.isFinishing || button.parent == null) return@post
                button.isEnabled = true
                button.text = "导出字幕"
                result.fold(
                    onSuccess = { text ->
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        if (clipboard == null) {
                            Toast.makeText(activity, "无法访问剪切板", Toast.LENGTH_SHORT).show()
                            return@fold
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bilibili 字幕", text))
                        Toast.makeText(activity, "字幕已复制到剪切板", Toast.LENGTH_SHORT).show()
                        environment.logInfo(ID, "clipboard copied: bvid=$videoId chars=${text.length}")
                    },
                    onFailure = { error ->
                        Toast.makeText(activity, error.message ?: "获取字幕失败", Toast.LENGTH_SHORT).show()
                        environment.logInfo(ID, "export failed: bvid=$videoId reason=${error.message ?: "unknown"}")
                    }
                )
            }
        }, log = { message -> environment.logInfo(ID, message) })
    }

    private fun removeButton(activity: Activity) {
        val button = buttons.remove(activity) ?: return
        (button.parent as? ViewGroup)?.removeView(button)
    }

    private fun extractVideoIdentity(raw: String): Pair<String, Long?>? {
        val value = raw.trim()
        Regex("BV1[a-zA-Z0-9]{9}").find(value)?.let { match ->
            val bvid = match.value
            val suffix = value.substring(match.range.last + 1)
            val cid = Regex("[+:](\\d+)").find(suffix)?.groupValues?.getOrNull(1)
                ?.toLongOrNull()?.takeIf { it > 0L }
            return bvid to cid
        }
        Regex("(?:^|:)(?:av)?(\\d+)(?:[+:](\\d+))?$").find(value)?.let { match ->
            val bvid = BilibiliVideoIdCodec.toBvid(match.groupValues[1]) ?: return null
            return bvid to match.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
        }
        return BilibiliVideoIdCodec.toBvid(value)?.let { it to null }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        const val ID = "subtitle_export"
        const val CAPABILITY = "player_subtitle_export"
        private const val CHANNEL = "subtitle_export_status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
