package com.hermesmarcano.zen.layer.core

/**
 * Quita de un texto lo que nunca debe guardarse ni salir del teléfono: códigos de un solo uso,
 * números de tarjeta y de cuenta, contraseñas y claves de API. Se aplica ANTES de guardar o enviar.
 *
 * Prefiere ocultar de más a de menos: un falso positivo solo pierde un número; un falso negativo
 * filtra un secreto. Es idempotente (volver a aplicarlo no cambia el resultado).
 */
object Redactor {
    data class Result(val text: String, val count: Int)

    const val OTP = "[código]"
    const val CARD = "[tarjeta]"
    const val ACCOUNT = "[cuenta]"
    const val SECRET = "[oculto]"

    private const val NOT_WORD = "[\\p{L}\\p{N}_]"
    private val options = setOf(RegexOption.IGNORE_CASE)

    /** Palabras que anuncian un código en la misma línea (o en la anterior). */
    private val otpKeyword = Regex(
        "(?<!$NOT_WORD)(?:c[oó]digo|code|otp|pin|clave|verific\\p{L}*|verification|token|un solo uso|one[- ]?time|" +
            "contrase[nñ]a|password|passcode|cvv|cvc|2fa|autentic\\p{L}*)(?!$NOT_WORD)",
        options,
    )

    /** 4–8 dígitos sueltos, o "123 456" / "123-456". No toca números más largos (teléfonos). */
    private val otpValue = Regex("(?<!\\d)(?:\\d{4,8}|\\d{3}[- ]\\d{3})(?!\\d)")

    /** "contraseña: valor", "password=valor", "cvv: 123". */
    private val labelledSecret = Regex(
        "(?<!$NOT_WORD)(?:contrase[nñ]a|password|passcode|passwd|clave|pin|cvv|cvc|token|secret|api[ _-]?key)" +
            "(\\s*[:=]\\s*)(\\S+)",
        options,
    )

    /** Formas conocidas de claves: sk-…, ghp_…, AKIA…, xoxb-…, JWT. */
    private val knownToken = Regex(
        "(?<![\\w-])(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[abprs]-[A-Za-z0-9-]{10,}|" +
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})",
    )

    /** 13–19 dígitos con un único espacio o guion opcional entre ellos. */
    private val cardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")

    /** CLABE (México) y cuentas de 18 dígitos. */
    private val account18 = Regex("(?<!\\d)\\d{18}(?!\\d)")

    fun redact(text: String): String = redactCounting(text).text

    /**
     * Oculta secretos en un título y su texto A LA VEZ. Muchas apps mandan "Código de verificación" como título y
     * "482913" como cuerpo: por separado, el cuerpo no tiene palabra clave y el número se escaparía.
     */
    fun redactPair(title: String, text: String): Pair<String, String> {
        val joined = redact(title.replace('\n', ' ') + "\n" + text)
        val cut = joined.indexOf('\n')
        return if (cut < 0) joined to "" else joined.substring(0, cut) to joined.substring(cut + 1)
    }

    fun redactCounting(text: String): Result {
        if (text.isEmpty()) return Result(text, 0)
        var count = 0
        var out = text

        out = labelledSecret.replace(out) { m ->
            if (m.groupValues[2] == SECRET) {
                m.value
            } else {
                count++
                m.value.substring(0, m.groups[2]!!.range.first - m.range.first) + SECRET
            }
        }
        out = knownToken.replace(out) { count++; SECRET }
        out = cardCandidate.replace(out) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length in 13..19 && luhn(digits)) {
                count++
                CARD
            } else {
                m.value
            }
        }
        out = account18.replace(out) { count++; ACCOUNT }

        // Códigos de un solo uso: línea con palabra clave, o línea corta justo después de una con ella.
        val lines = out.split('\n')
        val rebuilt = StringBuilder(out.length)
        var previousHadKeyword = false
        for ((i, line) in lines.withIndex()) {
            val hasKeyword = otpKeyword.containsMatchIn(line)
            val inContext = hasKeyword || (previousHadKeyword && line.length <= 40)
            val clean = if (inContext) otpValue.replace(line) { count++; OTP } else line
            if (i > 0) rebuilt.append('\n')
            rebuilt.append(clean)
            previousHadKeyword = hasKeyword
        }
        return Result(rebuilt.toString(), count)
    }

    internal fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
