import json
import os
import tempfile
import unittest

from doppler.library import Library


class Rev(unittest.TestCase):
    def test_rev_changes_when_a_rescan_reads_more_tags(self):
        """Devices fetch the library only when its rev changes, so tags read anew must change it,
        even for files that are just as they were."""
        with tempfile.TemporaryDirectory() as d:
            music, cache = os.path.join(d, "music"), os.path.join(d, "cache")
            os.makedirs(os.path.join(music, "1"))
            path = os.path.join(music, "1", "Queen - Jazz.flac")
            open(path, "wb").close()
            stat = os.stat(path)
            os.makedirs(cache)
            old = {"title": "Jazz", "artist": "Queen", "album": "", "duration": 0.0, "n": 0, "art": False,
                   "m": stat.st_mtime, "s": stat.st_size}
            with open(os.path.join(cache, "tags.json"), "w") as handle:
                json.dump({"root": os.path.abspath(music), "tracks": {"1/Queen - Jazz.flac": old}}, handle)
            library = Library(music, cache)
            before = library.rev
            self.assertTrue(library.scan(force=True))
            self.assertIn("albumartist", library.tracks["1/Queen - Jazz.flac"])
            self.assertNotEqual(library.rev, before)
