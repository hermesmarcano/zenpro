package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerPipelineTest {
    private var now = 1_000_000L
    private var ids = 0
    private var enqueued = 0
    private val timeline = MemoryTimeline()
    private val outbox = MemoryOutbox()
    private val on = CaptureSettings(screenText = true, notifications = true, ambientAudio = true)

    private fun pipeline(state: MemoryState) = LayerPipeline(
        state = state, timeline = timeline, outbox = outbox, ownPackage = "com.hermesmarcano.zen",
        encode = { "{\"id\":\"${it.id}\"}" },
        clock = { now }, newId = { "id${++ids}" },
        screenDeduper = TextDeduper(minIntervalMs = 0),
        onEnqueued = { enqueued++ },
    )

    private fun stateOn(brain: Boolean = false) = MemoryState((if (brain) BRAIN_STATE else LayerState()).copy(capture = on))

    @Test fun screenTextIsStoredRedactedAndQueued() {
        val p = pipeline(stateOn(brain = true))
        val out = p.onScreen("com.whatsapp", listOf("Marta: tu código es 482913", "nos vemos mañana"))
        assertEquals(LayerPipeline.Outcome.Kind.STORED, out.kind)
        assertTrue(out.queued)
        val event = timeline.events.single()
        assertFalse(event.text.contains("482913"))
        assertTrue(event.text.contains("nos vemos mañana"))
        assertEquals(LayerEvent.Kind.SCREEN, event.kind)
        assertEquals(listOf(event.id), outbox.items.map { it.id })
        assertEquals(OutboxItem.Kind.EVENT, outbox.items.single().kind)
        assertEquals(1, enqueued)
    }

    @Test fun withoutABrainNothingIsQueued() {
        val p = pipeline(stateOn(brain = false))
        val out = p.onScreen("com.whatsapp", listOf("hola, ¿cómo estás?"))
        assertEquals(LayerPipeline.Outcome.Kind.STORED, out.kind)
        assertFalse(out.queued)
        assertTrue(outbox.items.isEmpty())
        assertEquals(0, enqueued)
    }

    @Test fun gatedSourcesStoreNothing() {
        val off = pipeline(MemoryState())
        assertEquals(CaptureDecision.SOURCE_OFF, off.onScreen("com.whatsapp", listOf("hola mundo")).decision)
        assertEquals(CaptureDecision.SOURCE_OFF, off.onNotification("com.whatsapp", "Marta", "hola").decision)
        assertEquals(CaptureDecision.SOURCE_OFF, off.onAudioSegment("a.wav", 2_000, now).decision)
        val sensitive = pipeline(stateOn())
        assertEquals(CaptureDecision.SENSITIVE, sensitive.onScreen("com.bbva.mx", listOf("saldo disponible 1500")).decision)
        assertTrue(timeline.events.isEmpty())
    }

    @Test fun pauseStopsEverythingUntilItEnds() {
        val state = stateOn()
        val p = pipeline(state)
        state.update { it.copy(capture = it.capture.copy(pausedUntilMs = now + 5_000)) }
        assertEquals(CaptureDecision.PAUSED, p.onScreen("com.whatsapp", listOf("hola mundo")).decision)
        now += 5_000
        assertEquals(LayerPipeline.Outcome.Kind.STORED, p.onScreen("com.whatsapp", listOf("hola mundo")).kind)
    }

    @Test fun repeatedScreenTextIsDuplicate() {
        val p = pipeline(stateOn())
        assertEquals(LayerPipeline.Outcome.Kind.STORED, p.onScreen("a.b", listOf("una línea larga")).kind)
        assertEquals(LayerPipeline.Outcome.Kind.DUPLICATE, p.onScreen("a.b", listOf("una línea larga")).kind)
        assertEquals(1, timeline.events.size)
    }

    @Test fun emptyOrJunkScreenTextIsIgnored() {
        val p = pipeline(stateOn())
        assertEquals(LayerPipeline.Outcome.Kind.DUPLICATE, p.onScreen("a.b", listOf("12:45", "  ")).kind)
        assertTrue(timeline.events.isEmpty())
    }

    @Test fun ownAppIsNeverRecorded() {
        val p = pipeline(stateOn())
        assertEquals(CaptureDecision.OWN_APP, p.onScreen("com.hermesmarcano.zen", listOf("texto de la línea de tiempo")).decision)
    }

    @Test fun screenReadyIsFalseWhenGatedOrThrottled() {
        val state = stateOn()
        val throttled = LayerPipeline(
            state, timeline, outbox, "own", { "{}" }, { now }, { "x" },
            screenDeduper = TextDeduper(minIntervalMs = 5_000),
        )
        assertTrue(throttled.screenReady("com.whatsapp"))
        throttled.onScreen("com.whatsapp", listOf("hola mundo"))
        assertFalse(throttled.screenReady("com.whatsapp"))
        now += 5_000
        assertTrue(throttled.screenReady("com.whatsapp"))
        assertFalse(throttled.screenReady("com.bbva.mx"))
    }

    @Test fun notificationsAreRedactedAndCarryTheConversation() {
        val p = pipeline(stateOn(brain = true))
        val out = p.onNotification("com.whatsapp", "Banco: código 123456", "Tu clave: hunter2", conversationKey = "wa|marta")
        assertEquals(LayerPipeline.Outcome.Kind.STORED, out.kind)
        val event = timeline.events.single()
        assertEquals(LayerEvent.Kind.NOTIFICATION, event.kind)
        assertFalse(event.title.contains("123456"))
        assertFalse(event.text.contains("hunter2"))
        assertEquals("wa|marta", event.extra["conv"])
    }

    @Test fun aCodeInTheBodyIsHiddenWhenTheKeywordIsOnlyInTheTitle() {
        val p = pipeline(stateOn())
        p.onNotification("com.example.app", "Código de verificación", "482913")
        val event = timeline.events.single()
        assertFalse(event.text.contains("482913"))
        assertEquals("Código de verificación", event.title)
    }

    @Test fun linesAreRecoveredAfterAStorageFailure() {
        val p = pipeline(stateOn())
        timeline.failInserts = true
        assertEquals(LayerPipeline.Outcome.Kind.FAILED, p.onScreen("a.b", listOf("una línea larga")).kind)
        timeline.failInserts = false
        assertEquals(LayerPipeline.Outcome.Kind.STORED, p.onScreen("a.b", listOf("una línea larga")).kind)
    }

    @Test fun repostedNotificationsAreDuplicates() {
        val p = pipeline(stateOn())
        assertEquals(LayerPipeline.Outcome.Kind.STORED, p.onNotification("com.whatsapp", "Marta", "¿cenamos?").kind)
        assertEquals(LayerPipeline.Outcome.Kind.DUPLICATE, p.onNotification("com.whatsapp", "Marta", "¿cenamos?").kind)
        assertEquals(LayerPipeline.Outcome.Kind.STORED, p.onNotification("com.whatsapp", "Marta", "¿a las 9?").kind)
    }

    @Test fun emptyNotificationIsIgnored() =
        assertEquals(LayerPipeline.Outcome.Kind.EMPTY, pipeline(stateOn()).onNotification("com.whatsapp", " ", " ").kind)

    @Test fun audioSegmentsAreQueuedOnlyWithABrain() {
        val withBrain = pipeline(stateOn(brain = true)).onAudioSegment("seg1.wav", 4_500, now)
        assertTrue(withBrain.queued)
        assertEquals("[audio 4 s]", withBrain.event!!.text)
        val item = outbox.items.single()
        assertEquals(OutboxItem.Kind.AUDIO, item.kind)
        assertEquals("seg1.wav", item.payload)

        val withoutBrain = pipeline(stateOn(brain = false)).onAudioSegment("seg2.wav", 1_000, now)
        assertEquals(LayerPipeline.Outcome.Kind.STORED, withoutBrain.kind)
        assertFalse("el archivo ya no hace falta", withoutBrain.queued)
    }

    @Test fun systemEventsBypassSourceSwitchesAndAreRedacted() {
        val p = pipeline(MemoryState(BRAIN_STATE)) // todas las fuentes apagadas
        val out = p.onSystem(LayerEvent.Kind.OVERRIDE, "Bloqueo soltado", "Motivo: contraseña: abc123", pkg = "tiktok")
        assertEquals(LayerPipeline.Outcome.Kind.STORED, out.kind)
        assertFalse(timeline.events.single().text.contains("abc123"))
        assertEquals(LayerEvent.Kind.OVERRIDE, timeline.events.single().kind)
    }

    @Test fun storageFailureIsReportedNotThrown() {
        timeline.failInserts = true
        val p = pipeline(stateOn(brain = true))
        assertEquals(LayerPipeline.Outcome.Kind.FAILED, p.onScreen("a.b", listOf("una línea larga")).kind)
        assertEquals(LayerPipeline.Outcome.Kind.FAILED, p.onNotification("a.b", "t", "cuerpo del mensaje").kind)
        assertEquals(LayerPipeline.Outcome.Kind.FAILED, p.onAudioSegment("a.wav", 2_000, now).kind)
        assertTrue("nada se encola si no se pudo guardar", outbox.items.isEmpty())
    }

    @Test fun queueFailureStillKeepsTheLocalMemory() {
        outbox.failEnqueue = true
        val out = pipeline(stateOn(brain = true)).onScreen("a.b", listOf("una línea larga"))
        assertEquals(LayerPipeline.Outcome.Kind.STORED, out.kind)
        assertFalse(out.queued)
        assertEquals(1, timeline.events.size)
    }

    @Test fun longTextIsClamped() {
        val p = pipeline(stateOn())
        p.onScreen("a.b", List(50) { "x".repeat(400) + it })
        assertTrue(timeline.events.single().text.length <= LayerEvent.MAX_TEXT)
    }

    @Test fun eventHasNoRawSecretsEvenWhenSplitAcrossLines() {
        val p = pipeline(stateOn())
        p.onScreen("a.b", listOf("Código de verificación", "482913", "no lo compartas"))
        assertFalse(timeline.events.single().text.contains("482913"))
        assertNotNull(timeline.events.single())
        assertNull(timeline.events.single().extra["conv"])
    }
}

class ScreenTextCollectorTest {
    private fun node(text: String? = null, vararg kids: FakeNode, visible: Boolean = true, password: Boolean = false) =
        FakeNode(text, visible, password, kids.toList())

    @Test fun collectsInReadingOrder() {
        val root = node(null, node("Título"), node(null, node("Hola Marta"), node("¿Cenamos?")), node("Enviar"))
        assertEquals(listOf("Título", "Hola Marta", "¿Cenamos?", "Enviar"), ScreenTextCollector.collect(root))
    }

    @Test fun skipsInvisibleSubtreesAndPasswordFieldsWithTheirChildren() {
        val root = node(
            null,
            node("visible"),
            node("oculto", node("hijo de oculto"), visible = false),
            node("secreto123", node("hijo de contraseña"), password = true),
            node("   "),
            node(null),
        )
        assertEquals(listOf("visible"), ScreenTextCollector.collect(root))
    }

    @Test fun nullRootAndEmptyTreeGiveNothing() {
        assertTrue(ScreenTextCollector.collect(null).isEmpty())
        assertTrue(ScreenTextCollector.collect(node(null)).isEmpty())
    }

    @Test fun trimsLinesAndCapsTheirLength() {
        val long = "y".repeat(2_000)
        val out = ScreenTextCollector.collect(node(null, node("  hola  "), node(long)))
        assertEquals("hola", out[0])
        assertEquals(500, out[1].length)
    }

    @Test fun respectsTheLineAndCharacterCaps() {
        val kids = Array(100) { node("línea número $it") }
        val byLines = ScreenTextCollector.collect(node(null, *kids), ScreenTextCollector.Limits(maxLines = 10))
        assertEquals(10, byLines.size)
        val byChars = ScreenTextCollector.collect(node(null, *kids), ScreenTextCollector.Limits(maxChars = 60))
        assertTrue(byChars.sumOf { it.length } <= 60)
    }

    @Test fun respectsTheNodeAndDepthCaps() {
        val kids = Array(100) { node("n$it".padEnd(5, 'x')) }
        val out = ScreenTextCollector.collect(node(null, *kids), ScreenTextCollector.Limits(maxNodes = 11))
        assertEquals(10, out.size)

        val deep = (1..10).fold(node("fondo")) { inner, i -> node("nivel $i", inner) }
        val shallow = ScreenTextCollector.collect(deep, ScreenTextCollector.Limits(maxDepth = 2))
        assertEquals(listOf("nivel 10", "nivel 9", "nivel 8"), shallow)
    }

    @Test fun aVeryDeepChainDoesNotOverflowTheStack() {
        val deep = (1..20_000).fold(node("fondo")) { inner, _ -> node(null, inner) }
        assertTrue(ScreenTextCollector.collect(deep, ScreenTextCollector.Limits(maxNodes = 100_000, maxDepth = 100_000)).isNotEmpty())
    }

    @Test fun everyChildIsReleasedExactlyOnceAndTheRootNever() {
        val hiddenChild = node("c1")
        val root = node(null, node("a1", node("b1"), node("b2")), node("a2"), node("oculto", hiddenChild, visible = false))
        ScreenTextCollector.collect(root)
        assertEquals(0, root.releases)
        // Los hijos de un nodo invisible nunca se piden, así que no hay nada que liberar de ellos.
        assertEquals(0, hiddenChild.releases)
        for (n in root.all().drop(1).filter { it !== hiddenChild }) assertEquals("nodo ${n.text}", 1, n.releases)
    }

    @Test fun nodesLeftOverWhenACapStopsEarlyAreStillReleased() {
        val root = node(null, *Array(30) { node("línea $it", node("hijo $it")) })
        ScreenTextCollector.collect(root, ScreenTextCollector.Limits(maxLines = 3))
        assertEquals(0, root.releases)
        for (n in root.all().drop(1)) assertTrue("nodo ${n.text} liberado ${n.releases}", n.releases <= 1)
        // Los que se visitaron o quedaron en la pila se liberan; los hijos aún sin pedir nunca se crearon.
        assertTrue(root.all().drop(1).count { it.releases == 1 } >= 3)
    }
}

class WindowClassifierTest {
    @Test fun regularAppsAreApps() {
        assertEquals(WindowKind.APP, WindowClassifier.classify("com.zhiliaoapp.musically"))
        assertEquals(WindowKind.APP, WindowClassifier.classify("com.whatsapp"))
    }

    @Test fun launcherAndCallScreenEndASession() {
        assertEquals(WindowKind.HOME, WindowClassifier.classify("com.miui.home"))
        assertEquals(WindowKind.HOME, WindowClassifier.classify("com.android.incallui"))
    }

    @Test fun keyboardsAndShadeAreTransient() {
        assertEquals(WindowKind.TRANSIENT, WindowClassifier.classify("com.android.systemui"))
        assertEquals(WindowKind.TRANSIENT, WindowClassifier.classify("com.miui.securityinputmethod"))
        assertEquals(WindowKind.TRANSIENT, WindowClassifier.classify("android"))
        // Un teclado que no está en la lista se detecta por el tipo de ventana.
        assertEquals(WindowKind.TRANSIENT, WindowClassifier.classify("com.algun.teclado", isInputMethodWindow = true))
    }

    @Test fun missingPackageIsTransient() {
        assertEquals(WindowKind.TRANSIENT, WindowClassifier.classify(null))
        assertEquals(WindowKind.TRANSIENT, WindowClassifier.classify(" "))
    }
}

class FtsQueryTest {
    @Test fun quotesEachWordWithAPrefixWildcard() {
        assertEquals("\"reunión*\" \"mar*\"", FtsQuery.build("reunión mar"))
    }

    @Test fun syntaxCharactersCannotInjectOperators() {
        val built = FtsQuery.build("\" OR 1=1 --; DROP TABLE x")!!
        assertFalse(built.contains("--"))
        assertFalse(built.contains(";"))
        assertTrue(built.split(" ").all { it.startsWith("\"") && it.endsWith("*\"") })
    }

    @Test fun nothingSearchableGivesNull() {
        assertNull(FtsQuery.build(""))
        assertNull(FtsQuery.build("  \"* -- "))
    }

    @Test fun capsTheNumberOfWords() {
        assertEquals(FtsQuery.MAX_TOKENS, FtsQuery.build((1..30).joinToString(" ") { "w$it" })!!.split(" ").size)
    }
}
