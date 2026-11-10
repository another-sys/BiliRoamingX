"""Publish immutable release files first, verify the public CDN, then promote update.json."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.request
from urllib.parse import urlsplit
import oss2
from oss2.credentials import StaticCredentialsProvider
from update_protocol import decode, require_advancement

PREFIX = 'biliroamingx/'
BASE = 'http://cdn.ianother.cn/'


def bucket():
    credentials = StaticCredentialsProvider(os.environ['ALIBABA_CLOUD_ACCESS_KEY_ID'],
        os.environ['ALIBABA_CLOUD_ACCESS_KEY_SECRET'], os.environ['ALIBABA_CLOUD_SECURITY_TOKEN'])
    return oss2.Bucket(oss2.ProviderAuthV4(credentials), 'https://oss-cn-beijing.aliyuncs.com',
                       'another-oss', region='cn-beijing', connect_timeout=30)


def public_hash(url):
    print('Checking public download ' + url, flush=True)
    digest = hashlib.sha256()
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'BiliRoamingX-release'}), timeout=60) as response:
        if not response.url.startswith(BASE):
            raise ValueError('Unexpected public download redirect')
        for data in iter(lambda: response.read(1024 * 1024), b''):
            digest.update(data)
    return digest.hexdigest()


def publish(directory, client):
    envelope = (directory / 'update.json').read_bytes()
    update = decode(envelope)
    info = json.loads((directory / 'build-info.json').read_text(encoding='utf-8'))
    if info['source_dirty'] or info['source_commit'] != update['source_commit'] or not update['release'].startswith('v'):
        raise ValueError('Only clean tagged releases may be published')
    name = info['apk']
    if Path(name).name != name or update['url'] != f"{BASE}{PREFIX}releases/{update['release']}/{name}":
        raise ValueError('APK destination differs from signed manifest')
    apk = directory / name
    if apk.stat().st_size != update['size'] or public_file_hash(apk) != update['sha256']:
        raise ValueError('Local APK differs from signed manifest')
    current_key = PREFIX + 'update.json'
    try:
        print('Reading current OSS update manifest', flush=True)
        old_bytes = client.get_object(current_key).read()
        old = decode(old_bytes)
    except oss2.exceptions.NoSuchKey:
        old = None
    require_advancement(old, update)
    release_prefix = PREFIX + 'releases/' + update['release'] + '/'
    for path in sorted(directory.iterdir()):
        if not path.is_file():
            continue
        key = release_prefix + path.name
        headers = {'Cache-Control': 'public,max-age=31536000,immutable', 'x-oss-object-acl': 'public-read',
                   'x-oss-forbid-overwrite': 'true', 'x-oss-meta-sha256': public_file_hash(path),
                   'Content-Type': 'application/vnd.android.package-archive' if path.suffix == '.apk' else 'application/octet-stream'}
        try:
            print('Uploading ' + key, flush=True)
            client.put_object_from_file(key, str(path), headers=headers)
        except oss2.exceptions.ServerError as error:
            if error.status != 409 or client.head_object(key).headers.get('x-oss-meta-sha256') != headers['x-oss-meta-sha256']:
                raise
        print('Uploaded ' + key, flush=True)
    if public_hash(update['url']) != update['sha256']:
        raise ValueError('Public CDN APK verification failed; current manifest unchanged')
    # Verify the immutable signed envelope too, before replacing the discovery pointer.
    if public_hash(BASE + release_prefix + 'update.json') != hashlib.sha256(envelope).hexdigest():
        raise ValueError('Public CDN update metadata verification failed')
    client.put_object(current_key, envelope, headers={
        'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store, no-cache, max-age=0, must-revalidate',
        'x-oss-object-acl': 'public-read'})
    expected = hashlib.sha256(envelope).hexdigest()
    for attempt in range(6):
        if public_hash(BASE + current_key + '?verify=' + str(time.time_ns())) == expected:
            print('Published and verified ' + BASE + current_key, flush=True)
            return
        time.sleep(5)
    raise ValueError('CDN current manifest has not converged')


def public_file_hash(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def probe(client):
    key = PREFIX + 'checks/' + os.environ.get('GITHUB_RUN_ID', str(time.time_ns())) + '.txt'
    data = b'BiliRoamingX OSS publishing check\n'
    headers = {'x-oss-object-acl': 'public-read', 'Cache-Control': 'no-store, no-cache, max-age=0, must-revalidate'}
    print('Probe: upload ' + key, flush=True)
    client.put_object(key, data, headers=headers)
    print('Probe: authenticated OSS read', flush=True)
    if client.get_object(key).read() != data or public_hash(BASE + key) != hashlib.sha256(data).hexdigest():
        raise ValueError('OSS/CDN round trip failed')
    # Fetch the exact same URL after an overwrite: query-string cache busting is
    # deliberately absent so a stale CDN rule cannot hide behind a new cache key.
    replacement = data + b'Freshness verified\n'
    print('Probe: replace no-cache object', flush=True)
    client.put_object(key, replacement, headers=headers)
    if public_hash(BASE + key) != hashlib.sha256(replacement).hexdigest():
        raise ValueError('CDN cached an outdated no-cache object')
    print('Verified CDN freshness without query-string cache busting', flush=True)
    # This read-only attempt must be denied; it cannot alter another directory.
    try:
        client.get_object('brx-permission-boundary-check.txt')
    except oss2.exceptions.AccessDenied:
        print('Verified: another directory is not accessible', flush=True)
    else:
        raise ValueError('Unexpected permission outside update prefix')
    print('Verified OSS upload, read, public CDN and permission boundary', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', action='store_true')
    parser.add_argument('--dist', type=Path, default=Path('dist'))
    args = parser.parse_args()
    probe(bucket()) if args.probe else publish(args.dist, bucket())
