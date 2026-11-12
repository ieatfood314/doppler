import tempfile
import unittest

from doppler.player import Player


class Counting(unittest.TestCase):
    """mpv never starts here: position() falls back to the spot being headed for."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        config = {"hub": "", "device": "desk", "root": "", "eq": {}, "port": 0}
        self.player = Player(config, self.dir.name + "/state", self.dir.name + "/run")
        p = self.player
        p.library = {t: {"id": t, "title": t, "duration": 200} for t in ("1/a.flac", "1/b.flac")}
        p.current, p.order, p.index, p.playing, p.listen = "f:1", ["1/a.flac", "1/b.flac"], 0, True, 1

    def tearDown(self):
        self.dir.cleanup()

    def plays(self, track="1/a.flac"):
        return self.player._plays_of(track)[0]

    def test_a_play_counts_after_half_the_track_once_per_listen(self):
        p = self.player
        p.target = 99.0
        p._check_play()
        self.assertEqual(self.plays(), 0)
        p.target = 100.0
        p._check_play()
        p._check_play()
        self.assertEqual(self.plays(), 1)
        p.listen += 1  # played again from the top
        p._check_play()
        self.assertEqual(self.plays(), 2)

    def test_playing_to_the_end_counts_even_if_never_checked_midway(self):
        self.player.target = 0.0
        self.player._check_play(ended=True)
        self.assertEqual(self.plays(), 1)

    def test_counts_are_kept_across_restarts_and_shown_with_the_tracks(self):
        self.player.target = 150.0
        self.player._check_play()
        again = Player(self.player.config, self.dir.name + "/state", self.dir.name + "/run")
        self.assertEqual(again.plays["1/a.flac"][0], 1)
        self.assertEqual(again.source, self.player.source)
        again.library = self.player.library
        views = {v["id"]: v for v in again.tracks_view("f:1")}
        self.assertEqual((views["1/a.flac"]["plays"], views["1/b.flac"]["plays"]), (1, 0))


if __name__ == "__main__":
    unittest.main()
