package com.hermesmarcano.zen.layer.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.layer.capture.NotificationText
import com.hermesmarcano.zen.layer.capture.ReplyActionCache
import com.hermesmarcano.zen.layer.core.ReplySendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val REPLY_ACTION = "zen.test.REPLY"

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class ReplyActionCacheTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L

    private fun pending(): PendingIntent =
        PendingIntent.getBroadcast(app, 0, Intent(REPLY_ACTION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(freeForm: Boolean = true, semantic: Int = NotificationCompat.Action.SEMANTIC_ACTION_NONE, pi: PendingIntent = pending(), key: String = "reply_key") =
        NotificationCompat.Action.Builder(0, "Responder", pi)
            .addRemoteInput(RemoteInput.Builder(key).setLabel("Responder").setAllowFreeFormInput(freeForm).build())
            .setSemanticAction(semantic)
            .build()

    private fun notification(vararg actions: NotificationCompat.Action, wearable: NotificationCompat.Action? = null): Notification {
        val builder = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Marta").setContentText("hola")
        actions.forEach(builder::addAction)
        if (wearable != null) builder.extend(NotificationCompat.WearableExtender().addAction(wearable))
        return builder.build()
    }

    private fun cache(ttl: Long = 10_000, max: Int = 50) = ReplyActionCache(app, ttl, max) { now }

    @Test fun storesAReplyActionAndExposesTheTarget() {
        val cache = cache()
        assertTrue(cache.put("wa|1", "com.whatsapp", "Marta", notification(action())))
        assertEquals("Marta", cache.target("wa|1")!!.contact)
        assertEquals("com.whatsapp", cache.target("wa|1")!!.pkg)
        assertNull(cache.target("otra"))
    }

    @Test fun sendingFiresTheReplyWithTheTypedText() {
        val cache = cache()
        cache.put("wa|1", "com.whatsapp", "Marta", notification(action(key = "my_key")))
        assertEquals(ReplySendResult.SENT, cache.send("wa|1", "claro, a las 9"))
        val intent = shadowOf(app as android.app.Application).broadcastIntents.single { it.action == REPLY_ACTION }
        assertEquals("claro, a las 9", RemoteInput.getResultsFromIntent(intent)!!.getCharSequence("my_key").toString())
    }

    @Test fun aNotificationWithoutAFreeTextReplyIsNotReplyable() {
        val cache = cache()
        assertFalse(cache.put("a", "p", "c", notification()))
        assertFalse(cache.put("b", "p", "c", notification(action(freeForm = false))))
        assertEquals(0, cache.size())
    }

    @Test fun repliesOnTheWatchExtenderAreFound() {
        val cache = cache()
        assertTrue(cache.put("wa|1", "p", "Marta", notification(wearable = action())))
        assertEquals(ReplySendResult.SENT, cache.send("wa|1", "ok"))
    }

    @Test fun theMarkedReplyActionWinsOverOthers() {
        val first = action(pi = PendingIntent.getBroadcast(app, 1, Intent("zen.test.OTHER").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE))
        val reply = action(semantic = NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        val cache = cache()
        cache.put("k", "p", "c", notification(first, reply))
        cache.send("k", "hola")
        val actions = shadowOf(app as android.app.Application).broadcastIntents.map { it.action }
        assertTrue(REPLY_ACTION in actions)
        assertFalse("zen.test.OTHER" in actions)
    }

    @Test fun entriesExpire() {
        val cache = cache(ttl = 1_000)
        cache.put("k", "p", "c", notification(action()))
        now += 1_000
        assertNull(cache.target("k"))
        assertEquals(ReplySendResult.GONE, cache.send("k", "hola"))
        assertEquals(0, cache.size())
    }

    @Test fun removingAConversationMakesItUnreachable() {
        val cache = cache()
        cache.put("k", "p", "c", notification(action()))
        cache.remove("k")
        assertEquals(ReplySendResult.GONE, cache.send("k", "hola"))
    }

    @Test fun aCancelledPendingIntentMeansTheConversationIsGone() {
        val pi = pending()
        val cache = cache()
        cache.put("k", "p", "c", notification(action(pi = pi)))
        pi.cancel()
        assertEquals(ReplySendResult.GONE, cache.send("k", "hola"))
        assertNull("y se olvida", cache.target("k"))
    }

    @Test fun theOldestConversationsAreEvicted() {
        val cache = cache(max = 2)
        cache.put("a", "p", "c", notification(action()))
        cache.put("b", "p", "c", notification(action()))
        cache.target("a") // "a" se usó hace poco
        cache.put("c", "p", "c", notification(action()))
        assertNotNull(cache.target("a"))
        assertNull(cache.target("b"))
        assertNotNull(cache.target("c"))
        cache.clear()
        assertEquals(0, cache.size())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class NotificationTextTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun base(title: String? = "Marta", text: String? = "hola", category: String? = null, flags: Int = 0): Notification {
        val b = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
        if (title != null) b.setContentTitle(title)
        if (text != null) b.setContentText(text)
        if (category != null) b.setCategory(category)
        return b.build().also { it.flags = it.flags or flags }
    }

    @Test fun titleAndText() {
        val c = NotificationText.from(base())!!
        assertEquals("Marta", c.title)
        assertEquals("hola", c.body)
    }

    @Test fun bigTextBeatsTheShortText() {
        val n = base()
        n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "el mensaje completo y largo")
        assertEquals("el mensaje completo y largo", NotificationText.from(n)!!.body)
    }

    @Test fun conversationTitleBeatsTheTitle() {
        val n = base(title = "Marta")
        n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Familia")
        assertEquals("Familia", NotificationText.from(n)!!.title)
    }

    @Test fun messagingStyleJoinsTheLastMessagesWithTheirSender() {
        val n = base(text = "3 mensajes nuevos")
        val messages = (1..7).map { i ->
            Bundle().apply {
                putCharSequence("text", "mensaje $i")
                putCharSequence("sender", if (i % 2 == 0) "Marta" else "")
            }
        }
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES, messages.toTypedArray())
        val body = NotificationText.from(n)!!.body.lines()
        assertEquals(5, body.size)
        assertEquals("mensaje 3", body.first())
        assertEquals("Marta: mensaje 6", body[3])
        assertFalse(NotificationText.from(n)!!.body.contains("3 mensajes nuevos"))
    }

    @Test fun emptyNotificationsHaveNoContent() {
        assertNull(NotificationText.from(base(title = null, text = null)))
        assertNull(NotificationText.from(base(title = " ", text = " ")))
    }

    @Test fun noiseIsFilteredButMessagesAreNot() {
        assertTrue(NotificationText.isNoise(base(flags = Notification.FLAG_GROUP_SUMMARY), false))
        assertTrue(NotificationText.isNoise(base(category = Notification.CATEGORY_TRANSPORT), false))
        assertTrue(NotificationText.isNoise(base(category = Notification.CATEGORY_PROGRESS), false))
        assertTrue("fija y no es un mensaje", NotificationText.isNoise(base(), ongoing = true))
        assertFalse("fija pero es un mensaje", NotificationText.isNoise(base(category = Notification.CATEGORY_MESSAGE), ongoing = true))
        assertFalse(NotificationText.isNoise(base(), false))
    }
}
