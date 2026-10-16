package yhjmew.nibnya.editor.dialogs

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import yhjmew.nibnya.MainActivity

/**
 * 彻底重启应用（**不是** `Activity.recreate()`）。
 *
 * 为什么必须这么狠：语言切换用 `recreate()` 有两个残留问题 ——
 * 1. `ViewModel` 不随 `recreate()` 销毁，`NbtTranslator` 的 `isLoaded` / 缓存过的 `Context`
 *    仍是旧语言，翻译文本继续按旧语言输出；
 * 2. 已渲染的 NBT 列表/树节点文本不会重算，必须整表重建。
 *
 * 做法：设一个「立即重启」闹钟（保证即使本进程立刻退出，系统也会把界面拉起来），
 * 然后杀掉自己的进程，让下次启动走一遍完整初始化（此时新语言已写入 prefs）。
 */
internal fun MainActivity.restartApp() {
    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    val flags = if (Build.VERSION.SDK_INT >= 23) {
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_CANCEL_CURRENT
    }
    val pending = PendingIntent.getActivity(
        this,
        0,
        intent,
        flags
    )
    val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 60, pending)

    // 让界面先消失，再退出进程；下一帧由上面的闹钟把应用重新拉起来
    finish()
    Process.killProcess(Process.myPid())
}
