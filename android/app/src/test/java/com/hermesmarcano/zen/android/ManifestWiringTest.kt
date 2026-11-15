package com.hermesmarcano.zen.android

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmReceiver
import com.hermesmarcano.zen.alarm.AlarmService
import com.hermesmarcano.zen.alarm.BootReceiver
import com.hermesmarcano.zen.ui.MainActivity
import com.hermesmarcano.zen.ui.RingingActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class ManifestWiringTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager get() = app.packageManager

    @Test fun applicationIsOurClass() = assertTrue(app is ZenApp)

    @Test fun launcherActivityResolves() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val resolved = pm.queryIntentActivities(intent, 0)
        assertEquals(MainActivity::class.java.name, resolved.single().activityInfo.name)
    }

    @Test fun ringingActivityIsPrivateAndShowsWhenLocked() {
        val info = pm.getActivityInfo(ComponentName(app, RingingActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
    }

    @Test fun serviceIsMediaPlaybackAndPrivate() {
        val info = pm.getServiceInfo(ComponentName(app, AlarmService::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
    }

    @Test fun alarmReceiverIsPrivateAndBootReceiverListensToSystemBroadcasts() {
        assertFalse(pm.getReceiverInfo(ComponentName(app, AlarmReceiver::class.java), 0).exported)
        for (action in listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )) {
            val receivers = pm.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)
            assertTrue("falta receptor para $action", receivers.any { it.activityInfo.name == BootReceiver::class.java.name })
        }
    }

    @Test fun requiredPermissionsAreDeclared() {
        val requested = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        for (p in listOf(
            "android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS", "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.WAKE_LOCK", "android.permission.VIBRATE", "android.permission.RECEIVE_BOOT_COMPLETED",
        )) assertTrue("falta $p", p in requested)
    }

    @Test fun fileProviderAuthorityMatchesTheCode() {
        assertNotNull(pm.resolveContentProvider("${app.packageName}.fileprovider", 0))
    }

    @Test fun alarmManagerIsAvailable() = assertNotNull(app.getSystemService(AlarmManager::class.java))
}
