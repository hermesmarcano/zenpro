"""Saneado de TODO texto que viene de fuera (notificaciones, pantalla, voz) antes de ponerlo en un prompt.

Principio: el texto observado es contenido de terceros y puede traer instrucciones. Aquí se le quita lo que
sirve para esconderlas o para falsificar la estructura del prompt; que no sea obedecido lo garantizan, además,
el delimitador con identificador aleatorio y la validación estricta de la respuesta (validate.py).
"""

from __future__ import annotations

import re
import unicodedata

# Categorías Unicode que no son texto visible: controles (Cc), formato (Cf: espacios de ancho cero, controles
# bidireccionales y los caracteres «etiqueta» U+E0000.. con los que se esconde texto invisible), sustitutos (Cs),
# uso privado (Co) y sin asignar (Cn).
_DROP_CATEGORIES = frozenset({"Cc", "Cf", "Cs", "Co", "Cn"})

# Otros «ángulos» que NFKC no iguala a < y > (los corchetes angulares CJK, matemáticos y ornamentales).
_ANGLES = str.maketrans({
    "\u2329": "\u2039", "\u232a": "\u203a", "\u3008": "\u2039", "\u3009": "\u203a", "\u27e8": "\u2039", "\u27e9": "\u203a",
    "\u276c": "\u2039", "\u276d": "\u203a", "\u2770": "\u2039", "\u2771": "\u203a", "<": "\u2039", ">": "\u203a",
})
_FENCE = re.compile(r"`{3,}")
_BLANKS = re.compile(r"\n{3,}")
_SPACES = re.compile(r"[ \t ]{2,}")
_INST = re.compile(r"(?i)\[/?INST\]|<<\s*/?SYS\s*>>")
# Selectores de variación: no se ven y sirven para esconder texto dentro de otro (U+FE00..FE0F, U+E0100.., U+180B..180F).
_VARIATION = re.compile("[︀-️\U000e0100-\U000e01ef᠋-᠏]")
# «System: ...» al principio de línea imita un cambio de rol en el prompt (también tras «>», «›», «*», «#», «-»).
_ROLE_LINE = re.compile(
    r"(?im)^([ \t>›*#\-]*)(system|assistant|user|human|developer|tool|sistema|asistente|usuario)[ \t]*[:：]"
)


def _strip_invisible(value: str, keep_newlines: bool) -> str:
    out: list[str] = []
    for ch in value:
        if ch in "\n\r  ":  # cualquier salto de línea, también los de Unicode (Zl/Zp)
            out.append("\n" if keep_newlines else " ")
        elif ch == "\t":
            out.append(" ")
        elif unicodedata.category(ch) in _DROP_CATEGORIES:
            continue
        else:
            out.append(ch)
    return "".join(out)


def _neutralize(value: str) -> str:
    # Primero NFKC: «＜», «〈» o «﹤» se vuelven un «<» normal y caen en la regla de abajo (y las letras de ancho
    # completo, las ligaduras o los superíndices se igualan a las normales).
    value = unicodedata.normalize("NFKC", value)
    value = _VARIATION.sub("", value)
    # Los marcadores de rol se rompen ANTES de tocar los ángulos (si no, «> system:» se les escaparía).
    value = _ROLE_LINE.sub(lambda m: f"{m.group(1)}{m.group(2)}：", value)
    value = _INST.sub("(INST)", value)
    # Los ángulos desaparecen: así el texto no puede falsificar la etiqueta que delimita el evento ni los
    # marcadores de chat tipo <|im_start|>.
    return _FENCE.sub("'''", value.translate(_ANGLES))


def clean_text(value: object, limit: int) -> str:
    """Texto multilínea saneado y recortado a `limit` caracteres. Un valor que no es texto da ''."""
    if not isinstance(value, str) or limit <= 0:
        return ""
    value = _neutralize(_strip_invisible(value, keep_newlines=True))
    value = _BLANKS.sub("\n\n", _SPACES.sub(" ", value)).strip()
    return value[:limit].strip()


def clean_line(value: object, limit: int) -> str:
    """Igual que [clean_text] pero en una sola línea (nombres, paquetes)."""
    if not isinstance(value, str) or limit <= 0:
        return ""
    value = _neutralize(_strip_invisible(value, keep_newlines=False))
    return _SPACES.sub(" ", value).strip()[:limit].strip()
