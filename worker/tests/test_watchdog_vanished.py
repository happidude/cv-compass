from pathlib import Path
import tempfile
import unittest
from unittest import mock

from cv_compass_worker import container_entry, sandbox


class WatchdogVanishedFileTests(unittest.TestCase):
    def test_size_of_missing_file_is_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / 'gone.txt'
            self.assertEqual(sandbox._size(missing), 0)
            self.assertEqual(container_entry._size(missing), 0)

    def test_size_of_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'a.txt'
            path.write_bytes(b'abcd')
            self.assertEqual(sandbox._size(path), 4)
            self.assertEqual(container_entry._size(path), 4)

    def test_size_does_not_swallow_permission_error(self):
        with mock.patch.object(Path, 'stat', side_effect=PermissionError):
            with self.assertRaises(PermissionError):
                sandbox._size(Path('x'))
            with self.assertRaises(PermissionError):
                container_entry._size(Path('x'))

    def test_usage_survives_file_vanishing_between_listing_and_stat(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'doc' / 'work'
            root.mkdir(parents=True)
            (root / 'a.txt').write_bytes(b'aaaa')
            (root / 'b.txt').write_bytes(b'bb')
            real_stat = Path.stat

            def flaky(self, *args, **kwargs):
                if self.name == 'a.txt':
                    raise FileNotFoundError(str(self))
                return real_stat(self, *args, **kwargs)

            with mock.patch.object(Path, 'stat', flaky):
                document_bytes, engine_bytes = sandbox._usage(root)
            self.assertEqual(engine_bytes, 2)
            self.assertEqual(document_bytes, 2)


if __name__ == '__main__':
    unittest.main()
