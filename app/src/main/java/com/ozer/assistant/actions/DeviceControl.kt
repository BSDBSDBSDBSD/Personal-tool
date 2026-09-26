package com.ozer.assistant.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.ozer.assistant.nlu.SettingsPage

/** Result of a device action: what to tell the user, and a permission to ask for if one is missing. */
data class ActionResult(
    val text: String,
    val permissions: List<String> = emptyList(),
    /** Re-run the command once the permissions are granted. */
    val retry: Boolean = true,
)

object DeviceControl {
    private fun start(ctx: Context, intent: Intent) = Apps.tryStart(ctx, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // ---------- flashlight ----------
    fun flashlight(ctx: Context, on: Boolean): ActionResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionResult("לא מצאתי פנס בטלפון הזה.")
            cm.setTorchMode(id, on)
            ActionResult(if (on) "הדלקתי את הפנס." else "כיביתי את הפנס.")
        } catch (e: Exception) {
            ActionResult("לא הצלחתי לשלוט בפנס (אולי המצלמה בשימוש).")
        }
    }

    // ---------- wifi ----------
    @Suppress("DEPRECATION")
    fun wifi(ctx: Context, on: Boolean): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.setWifiEnabled(on)) return ActionResult(if (on) "הדלקתי את ה-Wi-Fi." else "כיביתי את ה-Wi-Fi.")
        }
        // Android 10+ doesn't let apps switch Wi-Fi directly; open the quick panel instead.
        start(ctx, Intent(Settings.Panel.ACTION_WIFI))
        return ActionResult(
            "באנדרואיד החדש אפליקציות לא יכולות ${if (on) "להדליק" else "לכבות"} Wi-Fi לבד — " +
                "פתחתי לך את החלון, רק תלחץ על המתג.",
        )
    }

    // ---------- bluetooth ----------
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun bluetooth(ctx: Context, on: Boolean): ActionResult {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return ActionResult("אין בלוטוס בטלפון הזה.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult("צריך הרשאת 'מכשירים בקרבת מקום' כדי לשלוט בבלוטוס.",
                listOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (adapter.isEnabled == on) return ActionResult(if (on) "הבלוטוס כבר דלוק." else "הבלוטוס כבר כבוי.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val ok = try { if (on) adapter.enable() else adapter.disable() } catch (e: Exception) { false }
            if (ok) return ActionResult(if (on) "מדליק את הבלוטוס." else "מכבה את הבלוטוס.")
        }
        return if (on) {
            start(ctx, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            ActionResult("אנדרואיד מבקש אישור — לחץ 'אישור' בחלון כדי להדליק בלוטוס.")
        } else {
            start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            ActionResult("באנדרואיד החדש אי אפשר לכבות בלוטוס מאפליקציה — פתחתי את ההגדרות, תכבה את המתג.")
        }
    }

    // ---------- volume ----------
    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volumePercent(ctx: Context): Int {
        val am = audio(ctx)
        return 100 * am.getStreamVolume(AudioManager.STREAM_MUSIC) / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    fun volumeStep(ctx: Context, up: Boolean): ActionResult {
        val am = audio(ctx)
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
        return ActionResult("${if (up) "הגברתי" else "הנמכתי"}. העוצמה עכשיו ${volumePercent(ctx)}%.")
    }

    fun volumeSet(ctx: Context, value: Int?, percent: Boolean): ActionResult {
        if (value == null) return ActionResult("לאיזו עוצמה? למשל: 'ווליום על 50'.")
        val pct = (if (!percent && value <= 10) value * 10 else value).coerceIn(0, 100)
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct + 50) / 100, AudioManager.FLAG_SHOW_UI)
        return ActionResult("העוצמה על $pct%.")
    }

    fun mute(ctx: Context): ActionResult {
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return ActionResult("השתקתי את המוזיקה. כדי להחזיר תגיד 'תגביר'.")
    }

    // ---------- brightness ----------
    private fun needWriteSettings(ctx: Context): ActionResult? {
        if (Settings.System.canWrite(ctx)) return null
        start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        return ActionResult("כדי לשנות בהירות צריך לאשר 'שינוי הגדרות מערכת' — פתחתי את המסך, תפעיל ותנסה שוב.")
    }

    private fun currentBrightness(ctx: Context) =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)

    private fun setBrightness(ctx: Context, v: Int) {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
    }

    fun brightnessStep(ctx: Context, up: Boolean): ActionResult {
        needWriteSettings(ctx)?.let { return it }
        val v = currentBrightness(ctx) + if (up) 60 else -60
        setBrightness(ctx, v)
        return ActionResult("${if (up) "הגברתי" else "הנמכתי"} את הבהירות ל-${100 * v.coerceIn(1, 255) / 255}%.")
    }

    fun brightnessSet(ctx: Context, value: Int?): ActionResult {
        needWriteSettings(ctx)?.let { return it }
        if (value == null) return ActionResult("לאיזו בהירות? למשל: 'בהירות 70'.")
        val pct = (if (value <= 10) value * 10 else value).coerceIn(0, 100)
        setBrightness(ctx, 255 * pct / 100)
        return ActionResult("הבהירות על $pct%.")
    }

    fun brightnessAuto(ctx: Context): ActionResult {
        needWriteSettings(ctx)?.let { return it }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        return ActionResult("הפעלתי בהירות אוטומטית.")
    }

    // ---------- silent / do not disturb ----------
    fun doNotDisturb(ctx: Context, on: Boolean): ActionResult {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return ActionResult("כדי לעבור למצב שקט צריך לאשר 'גישה לנא לא להפריע' לעוזר — פתחתי את המסך, תפעיל ותנסה שוב.")
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        if (!on) try { audio(ctx).ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
        return ActionResult(if (on) "הטלפון במצב שקט (נא לא להפריע)." else "ביטלתי את מצב השקט, הצלצול חזר.")
    }

    fun vibrate(ctx: Context): ActionResult = try {
        audio(ctx).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        ActionResult("הטלפון על רטט.")
    } catch (e: SecurityException) {
        start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        ActionResult("צריך לאשר 'גישה לנא לא להפריע' כדי לשנות מצב צלצול — פתחתי את המסך.")
    }

    // ---------- settings pages ----------
    fun openSettings(ctx: Context, page: SettingsPage): ActionResult {
        val (action, name) = when (page) {
            SettingsPage.MAIN -> Settings.ACTION_SETTINGS to "ההגדרות"
            SettingsPage.WIFI -> Settings.ACTION_WIFI_SETTINGS to "הגדרות Wi-Fi"
            SettingsPage.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS to "הגדרות בלוטוס"
            SettingsPage.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS to "הגדרות תצוגה"
            SettingsPage.SOUND -> Settings.ACTION_SOUND_SETTINGS to "הגדרות צליל"
            SettingsPage.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "הגדרות סוללה"
            SettingsPage.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "הגדרות מיקום"
            SettingsPage.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "מצב טיסה"
            SettingsPage.APPS -> Settings.ACTION_APPLICATION_SETTINGS to "הגדרות אפליקציות"
            SettingsPage.NETWORK -> Settings.ACTION_WIRELESS_SETTINGS to "הגדרות רשת"
            SettingsPage.HOTSPOT -> Settings.ACTION_WIRELESS_SETTINGS to "הגדרות רשת (נקודה חמה)"
            SettingsPage.LANGUAGE -> Settings.ACTION_LOCALE_SETTINGS to "הגדרות שפה"
            SettingsPage.KEYBOARD -> Settings.ACTION_INPUT_METHOD_SETTINGS to "הגדרות מקלדת"
            SettingsPage.DATE -> Settings.ACTION_DATE_SETTINGS to "הגדרות תאריך ושעה"
            SettingsPage.STORAGE -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "הגדרות אחסון"
            SettingsPage.SECURITY -> Settings.ACTION_SECURITY_SETTINGS to "הגדרות אבטחה"
            SettingsPage.PRIVACY -> Settings.ACTION_PRIVACY_SETTINGS to "הגדרות פרטיות"
            SettingsPage.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "הגדרות נגישות"
            SettingsPage.NOTIFICATIONS -> Settings.ACTION_SETTINGS to "ההגדרות (התראות)"
            SettingsPage.ACCOUNTS -> Settings.ACTION_SYNC_SETTINGS to "הגדרות חשבונות"
            SettingsPage.ABOUT -> Settings.ACTION_DEVICE_INFO_SETTINGS to "מידע על הטלפון"
        }
        return if (start(ctx, Intent(action)) || start(ctx, Intent(Settings.ACTION_SETTINGS))) {
            ActionResult("פתחתי את $name.")
        } else ActionResult("לא הצלחתי לפתוח את ההגדרות.")
    }

    // ---------- battery ----------
    fun battery(ctx: Context): ActionResult {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return ActionResult("הסוללה על $level%" + if (charging) ", והטלפון בטעינה." else ".")
    }
}
