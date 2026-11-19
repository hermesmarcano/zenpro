package com.hermesmarcano.zen.layer.core

/** Qué es lo que apareció delante: una app de verdad, la pantalla de inicio o algo pasajero que no cuenta. */
enum class WindowKind { APP, HOME, TRANSIENT }

object WindowClassifier {
    /** Pantalla de inicio y llamadas: al llegar a ellas se acaba la sesión de la app anterior. */
    private val homeLike = setOf(
        "com.miui.home", "com.mi.android.globallauncher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.android.incallui", "com.android.dialer", "com.google.android.dialer",
    )

    /** Teclado, barra de estado, diálogos del sistema: aparecen SOBRE la app sin sacarla de primer plano. */
    private val transient = setOf(
        "com.android.systemui", "android", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.miui.securityinputmethod", "com.android.inputmethod.latin", "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey", "com.sohu.inputmethod.sogou", "com.baidu.input_mi", "com.miui.aod", "com.miui.notification",
    )

    /** [isInputMethodWindow] cubre cualquier teclado, aunque no esté en la lista. */
    fun classify(pkg: String?, isInputMethodWindow: Boolean = false): WindowKind = when {
        pkg.isNullOrBlank() -> WindowKind.TRANSIENT
        isInputMethodWindow || pkg in transient -> WindowKind.TRANSIENT
        pkg in homeLike -> WindowKind.HOME
        else -> WindowKind.APP
    }
}

/** Vista mínima de un nodo de la pantalla. El adaptador de Android la implementa sobre `AccessibilityNodeInfo`. */
interface TextNode {
    val text: CharSequence?
    val isPassword: Boolean
    val isVisible: Boolean
    val childCount: Int
    fun child(index: Int): TextNode?

    /** Libera el nodo (en Android antiguo hay que reciclarlos). Por defecto no hace nada. */
    fun release() {}
}

/**
 * Recorre el árbol de la pantalla y devuelve sus textos en orden de lectura. Se salta lo invisible y los
 * campos de contraseña (y todo lo que cuelga de ellos) y respeta topes de nodos, profundidad y tamaño para
 * no bloquear el servicio con una pantalla enorme. No libera la raíz: es de quien la pidió.
 */
object ScreenTextCollector {
    data class Limits(val maxNodes: Int = 1_500, val maxDepth: Int = 40, val maxLines: Int = 300, val maxChars: Int = 6_000, val maxLineChars: Int = 500)

    fun collect(root: TextNode?, limits: Limits = Limits()): List<String> {
        if (root == null) return emptyList()
        val lines = ArrayList<String>()
        var visited = 0
        var chars = 0
        // Pila explícita: una pantalla muy anidada no debe desbordar la pila de llamadas.
        val stack = ArrayDeque<Pair<TextNode, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val isRoot = node === root
            try {
                if (++visited > limits.maxNodes) continue
                if (!node.isVisible || node.isPassword) continue
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val line = LayerEvent.clamp(text, limits.maxLineChars)
                    if (lines.size >= limits.maxLines || chars + line.length > limits.maxChars) {
                        // Tope alcanzado: lo que queda por visitar solo hay que soltarlo.
                        releaseAll(stack, root)
                        stack.clear()
                        break
                    }
                    lines += line
                    chars += line.length
                }
                if (depth < limits.maxDepth) {
                    // En orden inverso para que, al sacar de la pila, salgan en orden de lectura.
                    for (i in node.childCount - 1 downTo 0) node.child(i)?.let { stack.addLast(it to depth + 1) }
                }
            } finally {
                if (!isRoot) node.release()
            }
        }
        return lines
    }

    private fun releaseAll(stack: ArrayDeque<Pair<TextNode, Int>>, root: TextNode) {
        for ((node, _) in stack) if (node !== root) node.release()
    }
}

/**
 * Convierte lo que escribe el usuario en una consulta segura para la búsqueda de texto completo de SQLite (FTS4).
 * Cada palabra va entre comillas con comodín de prefijo DENTRO de ellas ("hol*"; en FTS4 el asterisco fuera de las
 * comillas, como en FTS5, no funciona), así que "reunión mar" encuentra "reuniones con Marta" y ningún carácter
 * del usuario se interpreta como sintaxis de búsqueda (AND, OR, NEAR, columnas, paréntesis).
 */
object FtsQuery {
    const val MAX_TOKENS = 8

    fun build(raw: String): String? {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(raw).map { it.value }.take(MAX_TOKENS).toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it*\"" }
    }
}
