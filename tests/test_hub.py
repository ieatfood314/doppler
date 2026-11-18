import tempfile
import threading
import time
import unittest

from doppler.hub import Hub


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def report(hub, device, playlist="f:1", **kw):
    body = {"device": device, "playlist": playlist, "index": 0, "position": 0.0, "playing": True}
    body.update(kw)
    return hub.report(body)


class Sessions(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.dir = tempfile.TemporaryDirectory()
        self.hub = Hub(self.dir.name, clock=self.clock)
        self.order = ["1/a.flac", "1/b.flac", "1/c.flac"]

    def tearDown(self):
        self.dir.cleanup()

    def test_claim_makes_a_device_the_one_playing(self):
        self.assertTrue(report(self.hub, "desk", claim=True, order=self.order)["ok"])
        state = self.hub.public()
        self.assertEqual(state["active"]["device"], "desk")
        self.assertEqual(state["sessions"]["f:1"]["track"], "1/a.flac")
        self.assertNotIn("order", state["sessions"]["f:1"])

    def test_heartbeat_needs_no_order_once_the_hub_has_it(self):
        first = report(self.hub, "desk", claim=True, order=self.order)
        self.assertTrue(first["ok"])
        from doppler.core import order_hash
        beat = report(self.hub, "desk", index=0, position=20.0, orderHash=order_hash(self.order))
        self.assertTrue(beat["ok"])
        self.assertEqual(beat["rev"], first["rev"], "a position report alone wakes nobody")
        self.assertEqual(self.hub.session("f:1")["session"]["position"], 20.0)
        self.assertTrue(report(self.hub, "desk", orderHash="wrong")["needOrder"])

    def test_track_change_and_pause_wake_listeners(self):
        from doppler.core import order_hash
        rev = report(self.hub, "desk", claim=True, order=self.order)["rev"]
        h = order_hash(self.order)
        self.assertGreater(report(self.hub, "desk", index=1, orderHash=h)["rev"], rev)
        rev = self.hub.public()["rev"]
        self.assertGreater(report(self.hub, "desk", index=1, playing=False, orderHash=h)["rev"], rev)

    def test_a_second_device_takes_over_and_the_first_is_told(self):
        from doppler.core import order_hash
        report(self.hub, "desk", claim=True, order=self.order)
        self.clock.now += 30
        self.assertTrue(report(self.hub, "phone", claim=True, order=self.order, index=1, position=5.0)["ok"])
        beat = report(self.hub, "desk", position=40.0, orderHash=order_hash(self.order))
        self.assertTrue(beat["superseded"])
        self.assertEqual(beat["active"]["device"], "phone")
        self.assertEqual(self.hub.session("f:1")["session"]["index"], 1, "the loser's report changed nothing")
        # ...and its pause afterwards doesn't overwrite the new owner either
        self.assertFalse(report(self.hub, "desk", playing=False, position=41.0, orderHash=order_hash(self.order))["ok"])
        self.assertEqual(self.hub.session("f:1")["session"]["device"], "phone")

    def test_playing_a_different_playlist_elsewhere_also_takes_over(self):
        from doppler.core import order_hash
        report(self.hub, "desk", claim=True, order=self.order)
        self.clock.now += 5
        report(self.hub, "phone", "f:2", claim=True, order=["2/x.mp3"])
        self.assertTrue(report(self.hub, "desk", position=9.0, orderHash=order_hash(self.order))["superseded"])
        self.assertEqual(self.hub.session("f:1")["session"]["device"], "desk", "its own session stays its own")

    def test_offline_listening_is_brought_in_when_it_is_the_newest(self):
        report(self.hub, "desk", claim=True, order=self.order, playing=False)
        self.clock.now += 3600
        # the phone played offline and paused ten minutes ago
        self.assertTrue(report(self.hub, "phone", claim=True, order=self.order, index=2, position=7.0,
                               playing=False, age=600)["ok"])
        session = self.hub.session("f:1")["session"]
        self.assertEqual((session["index"], session["device"], session["updated"]), (2, "phone", 4000.0))

    def test_but_not_when_someone_has_listened_since(self):
        report(self.hub, "phone", claim=True, order=self.order, playing=False)
        self.clock.now += 3600
        report(self.hub, "desk", claim=True, order=self.order, index=1)
        self.clock.now += 60
        late = report(self.hub, "phone", claim=True, order=self.order, index=2, playing=True, age=1800)
        self.assertFalse(late["ok"])
        self.assertTrue(late["stale"])
        self.assertTrue(late["superseded"])
        self.assertEqual(self.hub.session("f:1")["session"]["index"], 1)

    def test_a_press_of_play_beats_the_other_devices_heartbeat_that_got_there_first(self):
        from doppler.core import order_hash
        report(self.hub, "desk", claim=True, order=self.order)
        self.clock.now += 600
        # the phone's claim was made two seconds ago; a heartbeat from the desk landed in between
        report(self.hub, "desk", position=600.0, orderHash=order_hash(self.order))
        claim = report(self.hub, "phone", claim=True, order=self.order, index=0, position=598.0, age=2)
        self.assertTrue(claim["ok"])
        self.assertEqual(self.hub.public()["active"]["device"], "phone")

    def test_a_claim_queued_offline_loses_to_a_later_press_on_the_device_that_already_had_it(self):
        report(self.hub, "desk", claim=True, order=self.order)
        self.clock.now += 86400
        report(self.hub, "desk", claim=True, order=self.order, index=1)
        self.clock.now += 900
        # the phone pressed play twenty minutes ago, offline, five minutes before the desk's press
        late = report(self.hub, "phone", claim=True, order=self.order, index=2, playing=False, age=1200)
        self.assertFalse(late["ok"])
        self.assertEqual(self.hub.public()["active"]["device"], "desk")
        self.assertEqual(self.hub.session("f:1")["session"]["index"], 1)

    def test_moving_to_another_playlist_stops_the_old_one_playing(self):
        report(self.hub, "phone", claim=True, order=self.order)
        self.clock.now += 60
        report(self.hub, "phone", "f:2", claim=True, order=["2/x.mp3"])
        sessions = self.hub.public()["sessions"]
        self.assertFalse(sessions["f:1"]["playing"])
        self.assertTrue(sessions["f:2"]["playing"])

    def test_a_device_that_went_quiet_is_not_playing(self):
        report(self.hub, "phone", claim=True, order=self.order)
        self.clock.now += 60
        self.assertTrue(self.hub.public()["sessions"]["f:1"]["playing"])
        self.clock.now += 600
        self.assertFalse(self.hub.public()["sessions"]["f:1"]["playing"])

    def test_session_fetch_skips_an_order_the_caller_already_has(self):
        from doppler.core import order_hash
        report(self.hub, "desk", claim=True, order=self.order)
        self.assertNotIn("order", self.hub.session("f:1", order_hash(self.order))["session"])
        self.assertEqual(self.hub.session("f:1", "other")["session"]["order"], self.order)
        self.assertIsNone(self.hub.session("f:9")["session"])

    def test_state_survives_a_restart(self):
        report(self.hub, "desk", claim=True, order=self.order, index=2, position=12.0)
        self.hub.flush()
        again = Hub(self.dir.name, clock=self.clock)
        self.assertEqual(again.session("f:1")["session"]["index"], 2)

    def test_wait_returns_when_something_changes(self):
        rev = self.hub.public()["rev"]
        got = {}
        waiter = threading.Thread(target=lambda: got.update(self.hub.wait(rev, 5)))
        waiter.start()
        time.sleep(0.05)
        report(self.hub, "desk", claim=True, order=self.order)
        waiter.join(2)
        self.assertFalse(waiter.is_alive())
        self.assertGreater(got["rev"], rev)


class Playlists(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.hub = Hub(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def test_make_edit_and_delete(self):
        made = self.hub.put_playlist(None, {"name": "Road", "tracks": ["1/a.flac", "1/a.flac", "1/b.flac"]})["playlist"]
        self.assertEqual(made["tracks"], ["1/a.flac", "1/b.flac"])
        self.hub.put_playlist(made["id"], {"add": ["2/x.mp3"], "remove": ["1/a.flac"]})
        self.assertEqual(self.hub.public()["playlists"][0]["tracks"], ["1/b.flac", "2/x.mp3"])
        self.hub.set_slots({"2": made["id"]})
        self.assertEqual(self.hub.public()["slots"]["2"], made["id"])
        self.assertTrue(self.hub.delete_playlist(made["id"])["ok"])
        self.assertNotIn("2", self.hub.public()["slots"])

    def test_folders_cannot_be_edited(self):
        self.assertFalse(self.hub.put_playlist("f:1", {"tracks": []})["ok"])

    def test_modes(self):
        self.assertTrue(self.hub.set_mode({"playlist": "f:Album", "mode": "order"})["ok"])
        self.assertEqual(self.hub.session("f:Album")["mode"], "order")
        self.hub.set_mode({"playlist": "f:Album", "mode": "shuffle"})
        self.assertEqual(self.hub.public()["modes"], {})
        self.assertFalse(self.hub.set_mode({"playlist": "f:Album", "mode": "sideways"})["ok"])


class DeleteTracks(unittest.TestCase):
    def setUp(self):
        import os
        from doppler.library import Library
        self.dir = tempfile.TemporaryDirectory()
        self.music = os.path.join(self.dir.name, "music")
        os.makedirs(os.path.join(self.music, "1"))
        for name in ("a.mp3", "b.mp3"):
            open(os.path.join(self.music, "1", name), "wb").close()
        self.library = Library(self.music, os.path.join(self.dir.name, "cache"))
        self.library.scan(force=True)
        self.hub = Hub(os.path.join(self.dir.name, "data"), self.library)
        self.hub.use_gio = False

    def tearDown(self):
        self.dir.cleanup()

    def test_a_deleted_song_goes_to_the_trash_and_out_of_playlists(self):
        import os
        made = self.hub.put_playlist(None, {"name": "Mix", "tracks": ["1/a.mp3", "1/b.mp3"]})["playlist"]
        rev, lib = self.hub.public()["rev"], self.library.rev
        reply = self.hub.delete_tracks({"tracks": ["1/a.mp3"]})
        self.assertTrue(reply["ok"])
        self.assertEqual(reply["deleted"], ["1/a.mp3"])
        self.assertFalse(os.path.exists(os.path.join(self.music, "1", "a.mp3")))
        trashed = [f for _, _, files in os.walk(self.hub.trash_dir) for f in files]
        self.assertEqual(trashed, ["a.mp3"])
        self.assertEqual(self.library.ids(), ["1/b.mp3"])
        self.assertNotEqual(self.library.rev, lib, "devices must fetch the library again")
        self.assertGreater(self.hub.public()["rev"], rev)
        self.assertEqual(self.hub.state["playlists"][made["id"]]["tracks"], ["1/b.mp3"])

    def test_two_songs_with_the_same_file_name_both_keep_their_copy_in_the_trash(self):
        import os
        os.makedirs(os.path.join(self.music, "2"))
        with open(os.path.join(self.music, "2", "a.mp3"), "wb") as handle:
            handle.write(b"two")
        self.library.scan(force=True)
        self.assertTrue(self.hub.delete_tracks({"tracks": ["1/a.mp3", "2/a.mp3"]})["ok"])
        sizes = sorted(os.path.getsize(os.path.join(base, f)) for base, _, files in os.walk(self.hub.trash_dir) for f in files)
        self.assertEqual(sizes, [0, 3])

    def test_only_songs_in_the_library(self):
        import os
        open(os.path.join(self.music, "1", "cover.jpg"), "wb").close()
        self.assertFalse(self.hub.delete_tracks({"tracks": ["1/cover.jpg", "1/./b.mp3"]})["ok"])
        self.assertTrue(os.path.exists(os.path.join(self.music, "1", "cover.jpg")))
        self.assertTrue(os.path.exists(os.path.join(self.music, "1", "b.mp3")))

    def test_nothing_outside_the_library(self):
        import os
        outside = os.path.join(self.dir.name, "secret.mp3")
        open(outside, "wb").close()
        self.assertFalse(self.hub.delete_tracks({"tracks": ["../secret.mp3"]})["ok"])
        self.assertTrue(os.path.exists(outside))
        self.assertFalse(self.hub.delete_tracks({"tracks": []})["ok"])


class Plays(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.hub = Hub(self.dir.name, clock=Clock())

    def tearDown(self):
        self.dir.cleanup()

    def test_counts_from_every_device_add_up(self):
        reply = self.hub.add_plays({"source": "desk-1", "plays": {"1/a.flac": [2, 100.0]}})
        self.assertEqual(reply["plays"], {"1/a.flac": [2, 100.0]})
        self.hub.add_plays({"source": "phone-1", "plays": {"1/a.flac": [1, 200.0]}})
        self.assertEqual(self.hub.plays(), {"1/a.flac": [3, 200.0]})

    def test_a_resent_or_late_report_counts_once(self):
        self.hub.add_plays({"source": "desk-1", "plays": {"1/a.flac": [2, 100.0]}})
        rev = self.hub.public()["playsRev"]
        self.hub.add_plays({"source": "desk-1", "plays": {"1/a.flac": [2, 100.0]}})
        self.hub.add_plays({"source": "desk-1", "plays": {"1/a.flac": [1, 50.0]}})
        self.assertEqual(self.hub.plays(), {"1/a.flac": [2, 100.0]})
        self.assertEqual(self.hub.public()["playsRev"], rev)

    def test_counts_survive_a_restart(self):
        self.hub.add_plays({"source": "desk-1", "plays": {"1/a.flac": [2, 100.0]}})
        self.assertEqual(Hub(self.dir.name, clock=Clock()).plays(), {"1/a.flac": [2, 100.0]})

    def test_needs_a_source(self):
        self.assertFalse(self.hub.add_plays({"plays": {}})["ok"])


if __name__ == "__main__":
    unittest.main()
