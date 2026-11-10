"""Signed update envelopes shared by release generation and OSS publication."""
import base64
import json
import re
from urllib.parse import unquote, urlsplit
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12

PUBLIC_KEY = base64.b64decode('MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA0IX/RfoXyRkubhMom9SDIQXZNATBmpVqpD8jwEq9su5CnSRlQfi7v7eCvkTpUDoXcPgZ34cvVbcHEtKQVUT56Imf8Es9RTf6FriLlDYOETEUfwlo5y9rc+d8w7tq4f80A4xCWOJftkiPI5qUNi9ZVW/Rv18to/8oN2ZrbnzxtNEb1kE3Ygoho5MNgjsdbfzh9r+CGcW7+vVGatGlJ9aKMva5nWXaj1awzUG4/0Qh0Ki0OIDl7ImmJpDbRg3+pH2nmj4oQrv4oF700ZFr9A1sv+HkEwPRC8ozsuB6pSY0FL/wEwdJc+491nbMPNoW3+Zymql+IsTIl4odLhc9+qDPECp5mA3Ft3zTZs2ouIUZJi9QVpY2f3oQP5LjMeBg8O+e9wJGPwyvOpsmhj5/9O4RpiLvhzsPiBYbXqVL9cTxsoewkChvjXlyBKhDGxxcXxLUWJjJ1lSHt58M/ZL2Ot7W3DX7AbFKI0xhM+GokX5f2J+gW6jQj8RGyXAxI8GHWfzRAgMBAAE=')


def validate(data):
    if data['schema'] != 1 or data['package'] != 'tv.danmaku.bili' or data['abi'] != 'arm64-v8a':
        raise ValueError('Unsupported update package')
    if not isinstance(data['release_code'], int) or data['release_code'] <= 0:
        raise ValueError('Invalid release code')
    if not 0 < data['size'] <= 2 * 1024**3 or not re.fullmatch('[0-9a-f]{64}', data['sha256']):
        raise ValueError('Invalid APK digest or size')
    url = urlsplit(data['url'])
    path = unquote(url.path)
    public_http = (url.scheme == 'http' and url.hostname == 'cdn.ianother.cn' and url.port in (None, 80)
                   and path.startswith('/biliroamingx/') and '..' not in path.split('/') and '\\' not in path)
    if (url.scheme != 'https' and not public_http) or not url.hostname or url.username or url.fragment:
        raise ValueError('Untrusted download URL')
    return data


def encode(data, keystore, password, signer_sha256):
    validate(data)
    key, cert, _ = pkcs12.load_key_and_certificates(keystore, password.encode())
    if cert.fingerprint(hashes.SHA256()).hex() != signer_sha256:
        raise ValueError('Manifest signer differs from release certificate')
    public = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if public != PUBLIC_KEY:
        raise ValueError('Manifest signer differs from client trusted key')
    payload = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    signature = key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
    return json.dumps({'payload': base64.b64encode(payload).decode(),
                       'signature': base64.b64encode(signature).decode()}, separators=(',', ':')).encode() + b'\n'


def decode(envelope, public_key=PUBLIC_KEY):
    if len(envelope) > 131072:
        raise ValueError('Manifest too large')
    wrapper = json.loads(envelope)
    payload = base64.b64decode(wrapper['payload'], validate=True)
    signature = base64.b64decode(wrapper['signature'], validate=True)
    if len(payload) > 65536:
        raise ValueError('Payload too large')
    serialization.load_der_public_key(public_key).verify(signature, payload, padding.PKCS1v15(), hashes.SHA256())
    return validate(json.loads(payload))


def require_advancement(old, new):
    if old and (new['release_code'] < old['release_code'] or
                (new['release_code'] == old['release_code'] and new['sha256'] != old['sha256'])):
        raise ValueError('Refusing to replace current update with an older or conflicting build')
