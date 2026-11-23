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

    def test_time_heard_goes_to_the_track_that_was_sounding(self):
        import time
        from unittest import mock
        p = self.player
        month = time.strftime("%Y-%m")
        clock = [1000.0]
        with mock.patch("doppler.player.time.monotonic", lambda: clock[0]):
            p.core_idle = False
            p._tally()
            clock[0] += 30
            p._tally()
            p.index = 1  # moved on
            clock[0] += 5
            p._tally()
            p.core_idle = True  # buffering
            p._tally()
            clock[0] += 50
            p._tally()
        self.assertEqual(p.listened[month], {"1/a.flac": [35.0, 0]})
        p.target = 150.0
        p._check_play()
        self.assertEqual(p.listened[month]["1/b.flac"], [0.0, 1])
        months = p._listening_named(None)["months"]
        self.assertEqual(months[0]["top"][0]["id"], "1/a.flac")
        self.assertEqual(months[0]["top"][0]["seconds"], 35)

    def test_old_months_not_yet_sent_survive_a_restart(self):
        p = self.player
        p.listened = {"2020-01": {"1/a.flac": [90.0, 1]}}
        p._save()
        config = dict(p.config, hub="http://127.0.0.1:9")
        again = Player(config, self.dir.name + "/state", self.dir.name + "/run")
        self.assertEqual(again.listened, {"2020-01": {"1/a.flac": [90.0, 1]}})
        self.assertEqual(again._listened_out, {"2020-01": {"1/a.flac": [90.0, 1]}})

    def test_recent_is_newest_first_and_skips_unplayed(self):
        p = self.player
        p.library["1/c.flac"] = {"id": "1/c.flac", "title": "c", "duration": 200}
        p.hub_plays = {"1/a.flac": [3, 100.0], "1/b.flac": [1, 300.0]}
        self.assertEqual([t["id"] for t in p.recent_view()], ["1/b.flac", "1/a.flac"])
        self.assertEqual(p.recent_view()[1]["plays"], 3)

    def test_up_next_wraps_round_from_the_current_song(self):
        p = self.player
        p.order, p.index = ["1/a.flac", "1/b.flac", "1/c.flac"], 1
        self.assertEqual(p.up_next("f:1"), ["1/b.flac", "1/c.flac", "1/a.flac"])
        p.sessions["f:2"] = {"order": ["2/a.flac", "2/b.flac"], "index": 1}
        self.assertEqual(p.up_next("f:2"), ["2/b.flac", "2/a.flac"])
        self.assertIsNone(p.up_next("f:missing"))

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


class Tagged(unittest.TestCase):
    def test_artists_and_albums_are_listed_and_playable(self):
        with tempfile.TemporaryDirectory() as d:
            config = {"hub": "", "device": "desk", "root": "", "eq": {}, "port": 0}
            p = Player(config, d + "/state", d + "/run")
            p.library = {
                "1/a.flac": {"id": "1/a.flac", "title": "A", "artist": "Queen", "albumartist": "Queen", "album": "Jazz", "n": 2},
                "2/b.flac": {"id": "2/b.flac", "title": "B", "artist": "Queen", "albumartist": "Queen", "album": "Jazz", "n": 1},
            }
            self.assertEqual(p.tracks_of("b:Jazz\tQueen"), ["2/b.flac", "1/a.flac"])
            self.assertEqual(p.tracks_of("a:queen"), ["2/b.flac", "1/a.flac"])
            listed = {v["id"]: v for v in p.view()["playlists"]}
            self.assertEqual(listed["a:Queen"]["kind"], "artist")
            self.assertEqual((listed["b:Jazz\tQueen"]["name"], listed["b:Jazz\tQueen"]["by"]), ("Jazz", "Queen"))
            self.assertEqual(listed["f:1"]["by"], "")
            self.assertEqual(p.tracks_view("f:1")[0]["albumArtist"], "Queen")
