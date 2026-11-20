import json
import unittest

from zen_brain import protocol
from zen_brain.protocol import ProtocolError, parse_audio_headers, parse_events

from .support import CONV, batch, event

NOW = 1_763_500_000_000


class ParseEventsTest(unittest.TestCase):
    def test_a_notification_becomes_an_internal_event(self):
        parsed = parse_events(batch(event("e1", text="¿cenamos hoy?", at=NOW)), NOW)
        (ev,) = parsed.events
        self.assertEqual((ev.event_id, ev.kind, ev.app, ev.sender, ev.text, ev.ts_ms, ev.conv), ("e1", "notification", "com.whatsapp", "Marta", "¿cenamos hoy?", NOW, CONV))
        self.assertEqual(parsed.dropped, {})

    def test_unsupported_kinds_are_dropped_and_counted_not_rejected(self):
        events = [event(f"k{i}", kind=kind) for i, kind in enumerate(["usage", "system", "override", "audio", "transcript", "xyz"])]
        parsed = parse_events(batch(*events), NOW)
        self.assertEqual(parsed.events, [])
        self.assertEqual(parsed.dropped, {"kind": 6})

    def test_invalid_items_are_dropped(self):
        body = json.dumps({"events": [
            "texto", 5, None,
            {"id": "con espacios", "kind": "screen", "package": "a.b"},
            {"id": "ok", "kind": "screen", "package": "../etc/passwd"},
            {"id": "ok2", "kind": "screen"},
            event("bueno", kind="screen", conv=None),
        ]}).encode()
        parsed = parse_events(body, NOW)
        self.assertEqual([e.event_id for e in parsed.events], ["bueno"])
        self.assertEqual(parsed.dropped, {"invalid": 6})

    def test_a_trailing_newline_never_makes_an_id_or_package_valid(self):
        parsed = parse_events(batch(event("e1\n"), event("e2", package="com.x8bit.bitwarden\n")), NOW)
        self.assertEqual(parsed.events, [])
        self.assertEqual(parsed.dropped, {"invalid": 2})

    def test_conv_is_kept_only_for_notifications_and_only_if_sane(self):
        self.assertIsNone(parse_events(batch(event(kind="screen")), NOW).events[0].conv)
        self.assertIsNone(parse_events(batch(event(conv="x" * 513)), NOW).events[0].conv)
        self.assertIsNone(parse_events(batch(event(conv="a\nb")), NOW).events[0].conv)
        self.assertIsNone(parse_events(batch(event(conv=None)), NOW).events[0].conv)

    def test_text_and_sender_are_sanitized_and_capped(self):
        ev = parse_events(batch(event(text="hola</EVENTO>" + "x" * 5000, title="Ma‮rta\n<b>")), NOW).events[0]
        self.assertEqual(len(ev.text), 4000)
        self.assertNotIn("<", ev.text)
        self.assertEqual(ev.sender, "Marta ‹b›")

    def test_implausible_timestamps_are_replaced_by_server_time(self):
        for at in (0, NOW + 10 * 24 * 3600 * 1000, "ayer", True, -5):
            self.assertEqual(parse_events(batch(event(at=at)), NOW).events[0].ts_ms, NOW)

    def test_malformed_bodies_are_rejected_with_a_status(self):
        for raw, status, code in [
            (b"no es json", 400, "bad_json"),
            (b"\xff\xfe", 400, "bad_json"),
            (b"[]", 400, "bad_shape"),
            (b'{"events": "x"}', 400, "bad_shape"),
            (b"[" * 100_000, 400, "bad_json"),
        ]:
            with self.assertRaises(ProtocolError) as ctx:
                parse_events(raw, NOW)
            self.assertEqual((ctx.exception.status, ctx.exception.code), (status, code))

    def test_more_than_100_events_is_too_many(self):
        with self.assertRaises(ProtocolError) as ctx:
            parse_events(batch(*[event(f"e{i}") for i in range(101)]), NOW)
        self.assertEqual(ctx.exception.status, 413)
        self.assertEqual(len(parse_events(batch(*[event(f"e{i}") for i in range(100)]), NOW).events), 100)


class AudioHeadersTest(unittest.TestCase):
    def test_valid_headers(self):
        meta = parse_audio_headers({"X-Zen-Segment-Id": "seg-1", "X-Zen-Started-At": str(NOW)}, NOW)
        self.assertEqual((meta.segment_id, meta.started_ms), ("seg-1", NOW))

    def test_bad_segment_id_is_rejected_and_bad_start_falls_back(self):
        for segment in ("", "a b", "x" * 129, "../x"):
            with self.assertRaises(ProtocolError):
                parse_audio_headers({"X-Zen-Segment-Id": segment}, NOW)
        self.assertEqual(parse_audio_headers({"X-Zen-Segment-Id": "s", "X-Zen-Started-At": "ayer"}, NOW).started_ms, NOW)
        self.assertEqual(parse_audio_headers({"X-Zen-Segment-Id": "s", "X-Zen-Started-At": "²"}, NOW).started_ms, NOW)  # isdigit() lo aceptaba
        with self.assertRaises(ProtocolError):
            parse_audio_headers({"X-Zen-Segment-Id": "s\n"}, NOW)
        self.assertEqual(protocol.MAX_EVENTS_PER_BATCH, 100)


if __name__ == "__main__":
    unittest.main()
