"""Audio → texto en el servidor.

El WAV se valida ANTES de decodificar (cabecera, canales, ancho de muestra, frecuencia, duración y tamaño real) y
se decodifica en memoria con la biblioteca estándar: nunca se escribe en disco ni se pasa una ruta a
faster-whisper (con PyAV 19 una ruta rompe con `metadata_errors`). Se pasa un array numpy float32.
"""

from __future__ import annotations

import io
import threading
import wave
from typing import Protocol

from .sanitize import clean_text

SAMPLE_RATE = 16_000
MAX_SECONDS = 30.0
MAX_WAV_BYTES = int(MAX_SECONDS * SAMPLE_RATE * 2) + 4096


class WavError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def parse_wav(data: bytes) -> bytes:
    """Valida un WAV PCM16 mono de 16 kHz y devuelve las muestras en bruto (little-endian)."""
    if len(data) > MAX_WAV_BYTES:
        raise WavError("too_large")
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise WavError("not_wav")
    try:
        with wave.open(io.BytesIO(data), "rb") as w:
            channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
            if channels != 1 or width != 2:
                raise WavError("not_pcm16_mono")
            if rate != SAMPLE_RATE:
                raise WavError("bad_rate")
            if frames <= 0:
                raise WavError("empty")
            if frames / rate > MAX_SECONDS:
                raise WavError("too_long")
            pcm = w.readframes(frames)
    except WavError:
        raise
    except Exception:  # wave puede lanzar también RuntimeError/struct.error con cabeceras mentirosas
        raise WavError("not_wav") from None
    if len(pcm) != frames * 2:
        raise WavError("truncated")  # la cabecera promete más muestras de las que hay
    return pcm


class Transcriber(Protocol):
    def transcribe(self, pcm: bytes) -> str: ...


class NullTranscriber:
    """STT apagado: el evento sigue su curso sin texto."""

    def transcribe(self, pcm: bytes) -> str:
        return ""


class WhisperTranscriber:
    def __init__(self, model_name: str, language: str) -> None:
        self._name = model_name
        self._language = language
        self._model = None
        self._lock = threading.Lock()  # una transcripción a la vez: es CPU puro

    def _load(self):
        from faster_whisper import WhisperModel  # import perezoso: no hace falta para arrancar ni para las pruebas

        return WhisperModel(self._name, device="cpu", compute_type="int8")

    def transcribe(self, pcm: bytes) -> str:
        import numpy as np

        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        with self._lock:
            if self._model is None:
                self._model = self._load()
            segments, _info = self._model.transcribe(
                audio, language=self._language, beam_size=1, condition_on_previous_text=False
            )
            kept = []
            for segment in segments:
                # Whisper «alucina» frases sobre el silencio: se descartan los segmentos poco fiables.
                if getattr(segment, "no_speech_prob", 0.0) > 0.6 or getattr(segment, "avg_logprob", 0.0) < -1.2:
                    continue
                kept.append(segment.text)
        return clean_text(" ".join(kept), 4_000)
