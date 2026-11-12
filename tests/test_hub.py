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


if __name__ == "__main__":
    unittest.main()
