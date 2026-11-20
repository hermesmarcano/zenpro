"""zen-brain: adaptador entre la app «ZEN Xiaomi» y Hermes.

El teléfono nunca habla con Hermes: habla con este adaptador, y el adaptador es el ÚNICO que valida lo que el
modelo propone antes de que llegue al teléfono. Solo usa la biblioteca estándar (la transcripción de voz
importa faster-whisper de forma perezosa). Ver brain/README.md.
"""

__version__ = "0.1.0"
