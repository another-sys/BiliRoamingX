import base64
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

sys.path.insert(0, str(Path(__file__).parents[1]))
import update_protocol as protocol


class UpdateProtocol(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.public = cls.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

    def manifest(self, **changes):
        return dict(schema=1, package='tv.danmaku.bili', abi='arm64-v8a', min_sdk=24,
                    release='v-test', release_code=100, version_code=9170300,
                    url='https://cdn.ianother.cn/biliroamingx/releases/v-test/client.apk',
                    size=123, sha256='a'*64, **changes)

    def sign(self, data):
        payload = json.dumps(data).encode()
        signature = self.key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
        return json.dumps(dict(payload=base64.b64encode(payload).decode(),
                               signature=base64.b64encode(signature).decode())).encode()

    def test_round_trip_and_tampering(self):
        data = self.manifest()
        envelope = self.sign(data)
        self.assertEqual(protocol.decode(envelope, self.public), data)
        wrapper = json.loads(envelope)
        data['url'] = 'https://example.invalid/tampered.apk'
        wrapper['payload'] = base64.b64encode(json.dumps(data).encode()).decode()
        with self.assertRaises(InvalidSignature):
            protocol.decode(json.dumps(wrapper).encode(), self.public)

    def test_wrong_signer_rejected(self):
        with self.assertRaises(InvalidSignature):
            protocol.decode(self.sign(self.manifest()))

    def test_unsafe_or_incompatible_manifest_rejected(self):
        for key, value in [('url','http://cdn.ianother.cn/app.apk'), ('url','https://user@cdn.ianother.cn/app.apk'),
                           ('url','http://example.invalid/biliroamingx/app.apk'),
                           ('url','http://cdn.ianother.cn/biliroamingx/%2e%2e/other.apk'),
                           ('package','wrong.package'),('abi','x86'),('size',0),('sha256','bad'),('release_code',-1)]:
            data = self.manifest()
            data[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                protocol.decode(self.sign(data), self.public)

    def test_signed_public_http_distribution(self):
        data = self.manifest()
        data['url'] = 'http://cdn.ianother.cn/biliroamingx/releases/v-test/client.apk'
        self.assertEqual(protocol.decode(self.sign(data), self.public), data)

    def test_republication_does_not_downgrade_or_replace_same_version(self):
        old = self.manifest()
        protocol.require_advancement(None, old)
        protocol.require_advancement(old, old)
        protocol.require_advancement(old, {**old,'release_code':101})
        for new in ({**old,'release_code':99}, {**old,'sha256':'b'*64}):
            with self.assertRaises(ValueError):
                protocol.require_advancement(old, new)

    def test_client_and_publisher_trust_same_key(self):
        source = (Path(__file__).parents[2] / 'integrations/app/src/main/java/app/revanced/bilibili/update/UpdateManifest.kt').read_text(encoding='utf-8')
        self.assertIn(base64.b64encode(protocol.PUBLIC_KEY).decode(), source)


if __name__ == '__main__':
    unittest.main()
