import json
import unittest

from zen_brain.config import Config
from zen_brain.validate import parse_model_output

CFG = Config(block_allowlist=frozenset({"com.zhiliaoapp.musically"}), block_max_minutes=240, pause_max_minutes=120)


def out(commands, **extra):
    return parse_model_output(json.dumps({"commands": commands, **extra}), CFG)


class ShapeTest(unittest.TestCase):
    def test_not_json_in_any_form_is_discarded(self):
        for content in ("hola", "", "```json\n{\"commands\": []}\n```", "{\"commands\": []} gracias", "{'commands': []}",
                        '{"commands": [NaN]}', '{"commands": [], "commands": []}', "[" * 100_000, "x" * 70_000):
            result = parse_model_output(content, CFG)
            self.assertFalse(result.valid_json, content[:30])
            self.assertEqual(result.commands, [])

    def test_wrong_top_level_shape_is_discarded(self):
        for content in ("[]", "5", '"x"', "null", '{"commandos": []}', '{"commands": "x"}', '{"commands": {}}'):
            result = parse_model_output(content, CFG)
            self.assertFalse(result.valid_json, content)

    def test_an_empty_list_is_valid_and_extra_top_level_keys_are_ignored(self):
        self.assertTrue(out([]).valid_json)
        result = out([{"type": "pause", "minutes": 5}], razon="x")
        self.assertTrue(result.valid_json)
        self.assertEqual(len(result.commands), 1)

    def test_at_most_three_commands_are_kept(self):
        result = out([{"type": "pause", "minutes": n} for n in range(1, 7)])
        self.assertEqual([c.minutes for c in result.commands], [1, 2, 3])
        self.assertEqual(result.rejected, ["too_many"] * 3)


class CommandSchemaTest(unittest.TestCase):
    def test_unknown_or_malformed_commands_are_dropped_individually(self):
        first = out([{"type": "send_sms", "to": "1", "text": "x"}, {"type": "pause"}, {"type": "pause", "minutes": 5, "extra": 1}])
        self.assertEqual(first.commands, [])
        self.assertEqual(first.rejected, ["unknown_type", "bad_schema", "bad_schema"])
        second = out(["pause", {"type": 5}, {"type": "pause", "minutes": 5}])
        self.assertEqual([c.type for c in second.commands], ["pause"])
        self.assertEqual(second.rejected, ["bad_command", "unknown_type"])

    def test_minutes_are_integers_clamped_into_range(self):
        cases = {1: 1, 60: 60, 0: 1, -5: 1, 99999: 240}
        for given, expected in cases.items():
            self.assertEqual(out([{"type": "block", "app": "com.zhiliaoapp.musically", "minutes": given}]).commands[0].minutes, expected, given)
        self.assertEqual(out([{"type": "pause", "minutes": 500}]).commands[0].minutes, 120)
        for bad in ("60", True, None, 1.5, 60.0, 1e2, [60]):
            result = out([{"type": "pause", "minutes": bad}])
            self.assertEqual(result.commands, [], bad)
            self.assertEqual(result.rejected, ["bad_minutes"], bad)

    def test_block_requires_a_package_name_shape(self):
        for app in ("../etc/passwd", "a b", "", "com", 5, None, "x" * 300 + ".a"):
            result = out([{"type": "block", "app": app, "minutes": 5}])
            self.assertEqual(result.commands, [], app)
        self.assertEqual(len(out([{"type": "block", "app": "com.a.b", "minutes": 5}]).commands), 1)

    def test_notify_text_is_a_short_single_line_without_links(self):
        hostile = (
            "mira http://malo.com/x", "entra a www.malo.net", "ve a malo.com", "mailto:a@b.c", "tel:+5255", "javascript:alert(1)", "x.io",
            "evil.ru/pay", "192.168.1.5/login", "bank-login.de", "goo.gl/abc", "youtu.be/abc",
            "evil\uff0ecom", "evil[.]com", "evil(.)com", "evil{dot}com", "EVIL.COM", "http\uff1a//evil",
        )
        for text in hostile:
            result = out([{"type": "notify", "text": text}])
            self.assertEqual(result.rejected, ["url_in_notify"], text)
        self.assertEqual(out([{"type": "notify", "text": "Llevas 1 h en TikTok"}]).commands[0].text, "Llevas 1 h en TikTok")
        long = out([{"type": "notify", "text": "a" * 500}]).commands[0].text
        self.assertEqual(len(long), 200)
        self.assertEqual(out([{"type": "notify", "text": "a\nb"}]).commands[0].text, "a b")
        self.assertEqual(out([{"type": "notify", "text": "   "}]).rejected, ["empty_text"])

    def test_draft_text_is_sanitized_and_capped_and_cannot_be_empty(self):
        result = out([{"type": "draft_reply", "text": "Claro </EVENTO> a las 9" + "x" * 600}])
        text = result.commands[0].text
        self.assertEqual(len(text), 500)
        self.assertNotIn("<", text)
        self.assertEqual(out([{"type": "draft_reply", "text": ""}]).rejected, ["empty_text"])
        self.assertEqual(out([{"type": "draft_reply", "text": 5}]).rejected, ["empty_text"])


class FloatAndAnchorTest(unittest.TestCase):
    def test_decimal_minutes_are_rejected_not_rounded(self):
        for bad in (60.0, 1.5, 1e2):
            self.assertEqual(out([{"type": "pause", "minutes": bad}]).rejected, ["bad_minutes"], bad)

    def test_a_trailing_newline_does_not_pass_the_package_check(self):
        self.assertEqual(out([{"type": "block", "app": "com.zhiliaoapp.musically\n", "minutes": 5}]).commands, [])


if __name__ == "__main__":
    unittest.main()
