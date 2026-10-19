package yhjmew.nibnya.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import yhjmew.nibnya.R

/**
 * 设备与版本信息收集 —— 日志（[AppLogger]）与崩溃日志（[CrashHandler]）**共用同一份**。
 *
 * 以前只有 CrashHandler 收集这些，普通日志里一个字段都没有，
 * 排查问题时经常需要用户再单独去翻崩溃日志，很麻烦。
 *
 * ⚠️ 标签文案走 string 资源并**按应用内设定的语言**取（见 [localizedContext]），
 * 所以中文用户导出的是中文版、英文用户导出的是英文版。
 */
object DeviceInfo {

    /** 应用语言设置的 prefs 名（与 MainActivity.attachBaseContext 保持一致） */
    private const val PREFS = "nbt_config"
    private const val KEY_LANG = "app_language"

    /**
     * 按应用内设定的语言，取一个**已覆盖 Locale** 的 Context。
     *
     * 注意：`applicationContext` 本身**不带**语言覆盖 ——
     * 覆盖只发生在 `MainActivity.attachBaseContext` 里，那是 Activity 级别的。
     * 直接拿 applicationContext 取字符串会得到系统语言，中文用户切到英文后导出的日志仍是中文。
     */
    fun localizedContext(app: Context): Context {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lang = prefs.getString(KEY_LANG, null)
            ?: if (Locale.getDefault().language.contains("zh")) "zh" else "en"
        val config = Configuration(app.resources.configuration)
        config.setLocale(if ("en" == lang) Locale.ENGLISH else Locale.CHINESE)
        return app.createConfigurationContext(config)
    }

    /**
     * 读取系统属性。
     *
     * ROM 版本（MIUI / ColorOS / EMUI…）只能通过隐藏类 `android.os.SystemProperties` 拿到，
     * 属于非公开 API —— 全程 try/catch，拿不到就返回 null，绝不能因此崩溃。
     */
    private fun prop(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /** 各家魔改系统的版本，如 `MIUI 14.0.3` / `ColorOS 13.1`；识别不出时退回 `Build.DISPLAY` */
    private fun romVersion(): String? {
        val known = listOf(
            "ro.miui.ui.version.name" to "MIUI",       // 小米 / 红米
            "ro.build.version.emui" to "EMUI",          // 华为
            "ro.build.version.opporom" to "ColorOS",    // OPPO / 一加 / realme
            "ro.vivo.os.version" to "OriginOS",         // vivo / iQOO
            "ro.vivo.os.build.display.id" to "FuntouchOS",
            "ro.build.version.oneui" to "One UI",       // 三星
            "ro.build.version.magic" to "MagicOS",      // 荣耀
            "ro.smartisan.version" to "Smartisan OS"
        )
        for ((key, name) in known) {
            val v = prop(key) ?: continue
            return if (v.startsWith(name, ignoreCase = true)) v else "$name $v"
        }
        // 兜底：多数 ROM 会把自家版本塞进 display id（它总是可读，不需要反射）
        return Build.DISPLAY?.takeIf { it.isNotBlank() && it != Build.VERSION.RELEASE }
    }

    /** 应用版本，形如 `1.0.4b (104)` */
    private fun appVersion(ctx: Context): String? = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) {
            pi.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toString()
        }
        "${pi.versionName} ($code)"
    } catch (_: Throwable) {
        null
    }

    /**
     * 生成设备信息块（每行 `  标签: 值`）。
     * @param ctx 建议传 [localizedContext] 的结果，标签才会跟随应用语言
     */
    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        fun row(labelRes: Int, value: String?) {
            if (!value.isNullOrBlank()) {
                sb.append("  ").append(ctx.getString(labelRes)).append(": ").append(value).append('\n')
            }
        }
        row(R.string.log_brand, Build.BRAND)
        row(R.string.log_manufacturer, Build.MANUFACTURER)
        row(R.string.log_model, "${Build.MODEL} (${Build.DEVICE})")
        row(R.string.log_os_version, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        row(R.string.log_rom, romVersion())
        row(R.string.log_abi, Build.SUPPORTED_ABIS?.joinToString(", "))
        row(R.string.log_app_version, appVersion(ctx))
        return sb.toString()
    }
}
