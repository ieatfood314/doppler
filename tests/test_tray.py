import unittest

from doppler.tray import action_label, track_text


class TrayText(unittest.TestCase):
    def test_nothing_loaded(self):
        state = {"playing": False, "loaded": False, "track": None}
        self.assertEqual(action_label(state), "Play")
        self.assertEqual(track_text(state), "Nothing playing")

    def test_paused_track_can_resume(self):
        state = {"playing": False, "loaded": True,
                 "track": {"artist": "Queen", "title": "Keep Yourself Alive"}}
        self.assertEqual(action_label(state), "Resume")
        self.assertEqual(track_text(state), "Paused: Queen — Keep Yourself Alive")

    def test_playing_track_can_pause_without_an_artist(self):
        state = {"playing": True, "loaded": True, "track": {"artist": "", "title": "Song"}}
        self.assertEqual(action_label(state), "Pause")
        self.assertEqual(track_text(state), "Playing: Song")


if __name__ == "__main__":
    unittest.main()
