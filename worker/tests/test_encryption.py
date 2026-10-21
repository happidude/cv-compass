"""Real PDF engines; ordinary synthetic AES PDFs, no hostile fixtures."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from pypdf import PdfReader, PdfWriter
from cv_compass_worker.pdf import preflight


class EncryptionTests(unittest.TestCase):
    def check_password(self, password):
        with tempfile.TemporaryDirectory(prefix='cv-m62-encryption-') as tmp:
            path = Path(tmp) / 'synthetic.pdf'
            writer = PdfWriter()
            writer.add_blank_page(width=100, height=100)
            writer.encrypt(user_password=password, owner_password='synthetic-owner', algorithm='AES-256')
            writer.write(path)
            before = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertTrue(PdfReader(path).is_encrypted)
            with self.assertRaisesRegex(ValueError, '^FILE_ENCRYPTED$'):
                preflight(path, 50)
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)
        self.assertFalse(path.exists())

    def test_nonempty_password_rejected(self):
        self.check_password('synthetic-password')

    def test_empty_password_rejected(self):
        self.check_password('')

    def test_unencrypted_control(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'plain.pdf'
            writer = PdfWriter()
            writer.add_blank_page(width=100, height=100)
            writer.write(path)
            self.assertEqual(preflight(path, 50), 1)


if __name__ == '__main__':
    unittest.main()
