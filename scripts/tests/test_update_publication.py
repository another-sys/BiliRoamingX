import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import publish_oss as publisher


class UpdatePublication(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.apk = b'verified APK fixture'
        self.envelope = b'signed envelope fixture'
        self.current = {'release_code': 99, 'sha256': '0' * 64}
        self.update = dict(release='v-test', release_code=100, source_commit='abc',
                           size=len(self.apk), sha256=hashlib.sha256(self.apk).hexdigest(),
                           url=publisher.BASE + 'biliroamingx/releases/v-test/client.apk')
        (self.directory / 'client.apk').write_bytes(self.apk)
        (self.directory / 'update.json').write_bytes(self.envelope)
        (self.directory / 'build-info.json').write_text(json.dumps(dict(
            apk='client.apk', source_dirty=False, source_commit='abc')), encoding='utf-8')
        self.client = Mock()
        self.client.head_object.side_effect = publisher.oss2.exceptions.NotFound(404, {}, '', {})
        self.client.get_object.return_value.read.return_value = b'previous envelope'
        decoder = patch.object(publisher, 'decode', side_effect=lambda value:
                               self.update if value == self.envelope else self.current)
        decoder.start()
        self.addCleanup(decoder.stop)

    def publish(self, hashes):
        with patch.object(publisher, 'public_hash', side_effect=hashes):
            publisher.publish(self.directory, self.client)

    def test_pointer_changes_only_after_both_public_files_verify(self):
        def verify(url):
            if '/releases/' in url:
                self.client.put_object.assert_not_called()
            else:
                self.client.put_object.assert_called_once()
            return self.update['sha256'] if url.endswith('.apk') else hashlib.sha256(self.envelope).hexdigest()
        self.publish(verify)
        args, kwargs = self.client.put_object.call_args
        self.assertEqual(args, ('biliroamingx/update.json', self.envelope))
        self.assertIn('no-store', kwargs['headers']['Cache-Control'])
        for call in self.client.put_object_from_file.call_args_list:
            self.assertEqual(call.kwargs['headers']['x-oss-forbid-overwrite'], 'true')

    def test_public_download_corruption_leaves_pointer_unchanged(self):
        for hashes in (["bad"], [self.update['sha256'], "bad"]):
            with self.subTest(hashes=hashes), self.assertRaises(ValueError):
                self.publish(hashes)
            self.client.put_object.assert_not_called()

    def test_upload_failure_leaves_pointer_unchanged(self):
        self.client.put_object_from_file.side_effect = OSError('network disconnected')
        with self.assertRaises(OSError):
            self.publish([])
        self.client.put_object.assert_not_called()

    def test_local_corruption_and_rollback_never_upload(self):
        (self.directory / 'client.apk').write_bytes(b'broken')
        with self.assertRaises(ValueError):
            self.publish([])
        (self.directory / 'client.apk').write_bytes(self.apk)
        self.current['release_code'] = 101
        with self.assertRaises(ValueError):
            self.publish([])
        self.client.put_object_from_file.assert_not_called()
        self.client.put_object.assert_not_called()

    def test_matching_existing_files_skip_upload(self):
        def head(key):
            path = self.directory / key.rsplit('/', 1)[1]
            return Mock(headers={'x-oss-meta-sha256': publisher.public_file_hash(path)},
                        content_length=path.stat().st_size)
        self.client.head_object.side_effect = head
        self.publish([self.update['sha256']] + [hashlib.sha256(self.envelope).hexdigest()] * 2)
        self.client.put_object_from_file.assert_not_called()
        self.client.put_object.assert_called_once()

    def test_conflicting_immutable_object_never_overwritten(self):
        self.client.head_object.side_effect = None
        self.client.head_object.return_value = Mock(headers={'x-oss-meta-sha256': 'bad'}, content_length=1)
        with self.assertRaises(ValueError):
            self.publish([])
        self.client.put_object_from_file.assert_not_called()
        self.client.put_object.assert_not_called()


if __name__ == '__main__':
    unittest.main()
