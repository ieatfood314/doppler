import random
import unittest

from doppler import core

TRACKS = ["1/a.flac", "1/b.flac", "1/c.flac", "2/x.mp3", "Album/Disc 1/01 - one.flac", "Album/Disc 1/02 - two.flac",
          "Album/Disc 2/10 - ten.flac", "Album/Disc 2/9 - nine.flac"]


class Folders(unittest.TestCase):
    def test_every_folder_is_a_playlist_of_all_beneath_it(self):
        found = core.folders(TRACKS)
        self.assertEqual(set(found), {"f:", "f:1", "f:2", "f:Album", "f:Album/Disc 1", "f:Album/Disc 2"})
        self.assertEqual(len(found["f:Album"]), 4)
        self.assertEqual(len(found["f:"]), 8)

    def test_numbers_sort_as_numbers(self):
        self.assertEqual(core.playlist_tracks("f:Album/Disc 2", TRACKS, {}),
                         ["Album/Disc 2/9 - nine.flac", "Album/Disc 2/10 - ten.flac"])

    def test_folder_prefix_is_a_whole_name(self):
        self.assertEqual(core.playlist_tracks("f:1", TRACKS + ["10/z.flac"], {}), ["1/a.flac", "1/b.flac", "1/c.flac"])

    def test_custom_playlist_keeps_its_order_and_drops_missing_files(self):
        custom = {"c:1": {"name": "Mine", "tracks": ["2/x.mp3", "gone.flac", "1/a.flac"]}}
        self.assertEqual(core.playlist_tracks("c:1", TRACKS, custom), ["2/x.mp3", "1/a.flac"])
        self.assertEqual(core.playlist_name("c:1", custom), "Mine")

    def test_slots_default_to_folders_named_1_and_2(self):
        self.assertEqual(core.default_slots(set(core.folders(TRACKS))), {"1": "f:1", "2": "f:2"})
        self.assertEqual(core.default_slots({"f:", "f:Rock", "f:Jazz", "f:Jazz/Live"}), {"1": "f:Jazz", "2": "f:Rock"})

    def test_names_from_files(self):
        self.assertEqual(core.split_title("1/Van Halen - Jump (2015 Remaster).flac"), ("Van Halen", "Jump (2015 Remaster)"))
        self.assertEqual(core.split_title("x/07 - Californication.flac"), ("", "Californication"))
        self.assertEqual(core.split_title("x/01. Vignette - Panacea.flac"), ("Vignette", "Panacea"))
        self.assertEqual(core.split_title("x/01 No Escape.mp3"), ("", "No Escape"))
        self.assertEqual(core.split_title("x/1979.mp3"), ("", "1979"))


class Orders(unittest.TestCase):
    def setUp(self):
        self.rng = random.Random(7)
        self.tracks = [f"1/{n}.flac" for n in range(20)]

    def test_shuffle_is_a_permutation_and_order_mode_is_not_shuffled(self):
        order = core.new_order(self.tracks, "shuffle", self.rng)
        self.assertEqual(sorted(order), sorted(self.tracks))
        self.assertNotEqual(order, self.tracks)
        self.assertEqual(core.new_order(self.tracks, "order", self.rng), self.tracks)

    def test_chosen_track_goes_first(self):
        self.assertEqual(core.new_order(self.tracks, "shuffle", self.rng, first="1/5.flac")[0], "1/5.flac")

    def test_reshuffle_does_not_repeat_the_last_track(self):
        for seed in range(50):
            order = core.new_order(self.tracks[:3], "shuffle", random.Random(seed), avoid_first="1/0.flac")
            self.assertNotEqual(order[0], "1/0.flac")

    def test_reconcile_keeps_the_run_when_nothing_changed(self):
        order = core.new_order(self.tracks, "shuffle", self.rng)
        self.assertEqual(core.reconcile(order, 6, self.tracks, "shuffle", self.rng), (order, 6, True))

    def test_new_tracks_land_after_the_current_one(self):
        order = core.new_order(self.tracks, "shuffle", self.rng)
        current = order[6]
        new, index, same = core.reconcile(order, 6, self.tracks + ["1/new.flac"], "shuffle", self.rng)
        self.assertTrue(same)
        self.assertEqual(new[index], current)
        self.assertGreater(new.index("1/new.flac"), index)
        self.assertEqual(new[:7], order[:7])

    def test_removed_tracks_drop_out_without_moving_the_spot(self):
        order = core.new_order(self.tracks, "shuffle", self.rng)
        current = order[6]
        left = [t for t in self.tracks if t not in (order[2], order[10])]
        new, index, same = core.reconcile(order, 6, left, "shuffle", self.rng)
        self.assertTrue(same)
        self.assertEqual(new[index], current)
        self.assertEqual(len(new), 18)

    def test_when_the_current_track_is_gone_the_next_one_starts(self):
        order = core.new_order(self.tracks, "shuffle", self.rng)
        left = [t for t in self.tracks if t != order[6]]
        new, index, same = core.reconcile(order, 6, left, "shuffle", self.rng)
        self.assertFalse(same)
        self.assertEqual(new[index], order[7])
        self.assertEqual(core.resume_point(new, index, 99.0, 200.0, same), (index, 0.0))

    def test_order_mode_follows_the_folder(self):
        new, index, same = core.reconcile(self.tracks, 4, self.tracks + ["1/zz.flac"], "order", self.rng)
        self.assertEqual((new[-1], index, same), ("1/zz.flac", 4, True))

    def test_an_empty_playlist_is_empty(self):
        self.assertEqual(core.reconcile(["a"], 0, [], "shuffle", self.rng), ([], 0, False))

    def test_a_saved_run_with_nothing_left_starts_fresh(self):
        new, index, same = core.reconcile(["gone"], 0, self.tracks, "shuffle", self.rng)
        self.assertEqual((sorted(new), index, same), (sorted(self.tracks), 0, False))


class Resuming(unittest.TestCase):
    def test_a_session_playing_elsewhere_has_moved_on(self):
        session = {"position": 30.0, "playing": True, "device": "phone", "updated": 1000.0}
        self.assertEqual(core.live_position(session, 1012.0, "desk"), 42.0)

    def test_but_not_my_own_or_a_paused_or_a_silent_one(self):
        base = {"position": 30.0, "playing": True, "device": "phone", "updated": 1000.0}
        self.assertEqual(core.live_position(base, 1012.0, "phone"), 30.0)
        self.assertEqual(core.live_position(dict(base, playing=False), 1012.0, "desk"), 30.0)
        self.assertEqual(core.live_position(base, 1000.0 + core.LIVE_WINDOW + 1, "desk"), 30.0)

    def test_the_very_end_of_a_track_resumes_at_the_next(self):
        self.assertEqual(core.resume_point(["a", "b"], 0, 199.0, 200.0), (1, 0.0))
        self.assertEqual(core.resume_point(["a", "b"], 1, 199.0, 200.0), (0, 0.0))
        self.assertEqual(core.resume_point(["a", "b"], 0, 50.0, 200.0), (0, 50.0))
        self.assertEqual(core.resume_point(["a", "b"], 0, 50.0, 0), (0, 50.0))


if __name__ == "__main__":
    unittest.main()
