import unittest

from zen_brain.stt import MAX_SECONDS, NullTranscriber, WavError, parse_wav

from .support import wav


class ParseWavTest(unittest.TestCase):
    def code(self, data: bytes) -> str:
        with self.assertRaises(WavError) as ctx:
            parse_wav(data)
        return ctx.exception.code

    def test_a_valid_clip_returns_raw_samples(self):
        self.assertEqual(len(parse_wav(wav(1.0))), 32_000)
        self.assertEqual(len(parse_wav(wav(MAX_SECONDS))), 960_000)

    def test_wrong_formats_are_rejected_before_decoding(self):
        self.assertEqual(self.code(wav(1.0, channels=2)), "not_pcm16_mono")
        self.assertEqual(self.code(wav(1.0, width=1)), "not_pcm16_mono")
        self.assertEqual(self.code(wav(1.0, rate=44100)), "bad_rate")
        self.assertEqual(self.code(wav(1.0, rate=8000)), "bad_rate")

    def test_duration_and_size_limits(self):
        self.assertEqual(self.code(wav(30.05)), "too_long")
        self.assertEqual(self.code(wav(40.0)), "too_large")

    def test_garbage_is_not_a_wav(self):
        for data in (b"", b"RIFF", b"x" * 100, b"RIFF" + b"\x00" * 100, b"\xff" * 5000, b"RIFFxxxxWAVE" + b"\x00" * 100):
            self.assertEqual(self.code(data), "not_wav", data[:12])

    def test_empty_and_truncated_clips_are_rejected(self):
        self.assertEqual(self.code(wav(0.0)), "empty")
        data = wav(1.0)
        self.assertIn(self.code(data[: len(data) // 2]), ("truncated", "not_wav"))

    def test_a_lying_chunk_size_is_not_a_wav_and_never_escapes_as_a_crash(self):
        data = bytearray(wav(1.0))
        data[16:20] = (32).to_bytes(4, "little")  # el chunk fmt dice 32 bytes y tiene 16: wave lanza RuntimeError
        self.assertEqual(self.code(bytes(data)), "not_wav")
        for size in (0, 1, 0xFFFFFFFF):
            mutated = bytearray(wav(1.0))
            mutated[16:20] = size.to_bytes(4, "little")
            self.assertIn(self.code(bytes(mutated)), ("not_wav", "truncated", "not_pcm16_mono", "bad_rate", "empty"))

    def test_the_null_transcriber_returns_no_text(self):
        self.assertEqual(NullTranscriber().transcribe(b"\x00\x00"), "")


if __name__ == "__main__":
    unittest.main()
