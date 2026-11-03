#!/usr/bin/env python3
"""Build a verified, signed Bilibili APK. Python standard library only."""
import argparse
from contextlib import nullcontext
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

# Preserve an ASCII junction on Windows (AGP/aapt2 cannot handle some Unicode paths).
ROOT = Path(os.path.abspath(__file__)).parents[1]
WORK = ROOT / "release-work"
DIST = ROOT / "dist"
CONFIG = ROOT / "release-config.json"


def sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(url, destination, expected):
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("An exact SHA-256 is required")
    if destination.exists() and sha256(destination) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    print(f"Downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "BiliRoamingX-build"})
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as stream:
        shutil.copyfileobj(response, stream)
    actual = sha256(temporary)
    if actual != expected:
        temporary.unlink()
        raise RuntimeError(
            f"Download SHA-256 mismatch: expected {expected}, got {actual}. "
            "The official latest APK may have changed. Review compatibility and update "
            "release-config.json before releasing; do not bypass this check."
        )
    temporary.replace(destination)


def run(args, log=None, quiet=False):
    # Older Windows Android tools cannot open absolute non-ASCII paths.
    args = [str(arg.relative_to(ROOT)) if isinstance(arg, Path) and arg.is_relative_to(ROOT)
            else str(arg) for arg in args]
    # Windows SDK scripts need cmd; arguments here contain only controlled paths
    # and fixed flags. Passwords are passed through environment variables.
    if os.name == "nt" and args[0].endswith(".bat"):
        args = [os.environ.get("COMSPEC", "cmd.exe"), "/c", *args]
    lines = []
    with (Path(log).open("w", encoding="utf-8") if log else nullcontext()) as stream:
        with subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, encoding="utf-8", errors="replace") as process:
            for line in process.stdout:
                lines.append(line)
                if stream:
                    stream.write(line)
                    stream.flush()
                if not quiet:
                    print(line, end="", flush=True)
            result = process.wait()
    if result:
        raise RuntimeError(f"{Path(args[0]).name} exited with {result}")
    return "".join(lines)


def sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME to your Android SDK")
    suffix = ".bat" if name == "apksigner" else ".exe"
    path = Path(sdk) / "build-tools" / "35.0.0" / (name + (suffix if os.name == "nt" else ""))
    if not path.is_file():
        raise RuntimeError(f"Install Android build-tools;35.0.0: missing {path}")
    return path


def apk_identity(apk):
    text = run([sdk_tool("aapt"), "dump", "badging", apk], WORK / "badging.txt", quiet=True)
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text)
    if not match:
        raise RuntimeError("Cannot read APK identity")
    return dict(zip(("package", "version_code", "version"), match.groups()))


def assert_identity(actual, expected):
    for key in ("package", "version_code", "version"):
        if actual[key] != expected[key]:
            raise RuntimeError(f"APK {key}: expected {expected[key]}, got {actual[key]}")


def assert_patch_log(log):
    # This CLI can exit 0 even when individual patches failed.
    if re.search(r"(?im)(SEVERE:|Invalid register:|no viable alternative|mismatched input|\bfailed(?=[:.!\s]|$)|\b[\w.$]*(?:Exception|Error):)", log):
        raise RuntimeError("Patching reported a failure; refusing to publish a partially patched APK")
    for name in ("Integrations", "BiliRoamingX settings entrance", "Moss", "OkHttp", "Config", "Pegasus hook"):
        if f"{name} succeeded" not in log:
            raise RuntimeError(f"Missing successful core patch: {name}")


def tools(config):
    cli = config["cli"]
    download(cli["url"], ROOT / ".tools/revanced-cli.jar", cli["sha256"])


def source(config):
    client = config["client"]
    WORK.mkdir(exist_ok=True)
    apk = WORK / "original.apk"
    download(client["url"], apk, client["sha256"])
    assert_identity(apk_identity(apk), client)
    cert = run([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", apk], WORK / "original-signature.txt")
    if f"certificate SHA-256 digest: {client['signer_sha256']}" not in cert:
        raise RuntimeError("Original APK does not have the pinned official Bilibili signing certificate")


def unique_artifact(pattern):
    files = list((ROOT / "build").glob(pattern))
    if len(files) != 1:
        raise RuntimeError(f"Expected exactly one build/{pattern}, found {len(files)}; run gradlew dist")
    return files[0]


def patch(config):
    tools(config)
    source(config)
    integrations = unique_artifact("BiliRoamingX-integrations-*.apk")
    patches = unique_artifact("BiliRoamingX-patches-*.jar")
    output = WORK / "patched.apk"
    output.unlink(missing_ok=True)
    args = ["java", "-Xmx6g", "-Duser.language=en", "-Duser.country=US", "-jar",
            ROOT / ".tools/revanced-cli.jar", "patch", "--merge", integrations,
            "--patch-bundle", patches, "--signing-levels", "1,2,3",
            "--out", output, "--temporary-files-path", WORK / "patch-cache"]
    for name in config["excluded_patches"]:
        args += ["--exclude", name]
    args.append(WORK / "original.apk")
    log = run(args, WORK / "patch.log")
    assert_patch_log(log)
    if not output.is_file() or not zipfile.is_zipfile(output):
        raise RuntimeError("Patcher did not produce a valid APK")
    audit_classes = WORK / "audit-classes"
    audit_classes.mkdir(exist_ok=True)
    cli_path = ROOT / ".tools/revanced-cli.jar"
    for verifier, log_name in (("VerifyProtobuf", "protobuf"),
                               ("VerifyIntegrationHierarchy", "integration-hierarchy")):
        run(["javac", "--release", "17", "-cp", cli_path, "-d", audit_classes,
             ROOT / f"scripts/{verifier}.java"])
        run(["java", "-Xmx3g", "-cp", os.pathsep.join(map(str, (cli_path, audit_classes))),
             verifier, output], WORK / f"{log_name}-audit.log")
    assert_identity(apk_identity(output), config["client"])


def sign(config):
    # Signing and publication only follow a successful, current patch invocation.
    patch(config)
    integrations = unique_artifact("BiliRoamingX-integrations-*.apk")
    patches = unique_artifact("BiliRoamingX-patches-*.jar")
    for key in ("APK_KEYSTORE_PATH", "APK_KEYSTORE_PASSWORD", "APK_KEY_ALIAS", "APK_KEY_PASSWORD"):
        if not os.environ.get(key):
            raise RuntimeError(f"Missing signing configuration: {key}")
    aligned = WORK / "aligned.apk"
    signed = WORK / "signed.apk"
    run([sdk_tool("zipalign"), "-P", "16", "-f", "4", WORK / "patched.apk", aligned])
    run([sdk_tool("apksigner"), "sign", "--ks", os.environ["APK_KEYSTORE_PATH"],
         "--ks-key-alias", os.environ["APK_KEY_ALIAS"],
         "--ks-pass", "env:APK_KEYSTORE_PASSWORD", "--key-pass", "env:APK_KEY_PASSWORD",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--out", signed, aligned])
    # On minSdk 24+ apksigner skips v1 unless verification explicitly includes
    # older API levels, even when the JAR signature is present and valid.
    cert = run([sdk_tool("apksigner"), "verify", "--min-sdk-version", "21", "--verbose", "--print-certs", signed], WORK / "signature.txt")
    for scheme in ("v1", "v2", "v3"):
        if not re.search(rf"Verified using {scheme} scheme.*: true", cert):
            raise RuntimeError(f"Missing valid {scheme} signature")
    signer = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", cert).group(1)
    if signer != config["release_signer_sha256"]:
        raise RuntimeError("Release signing certificate changed; refusing incompatible update")
    run([sdk_tool("zipalign"), "-c", "-P", "16", "4", signed])
    assert_identity(apk_identity(signed), config["client"])
    with zipfile.ZipFile(signed) as archive:
        if "lib/arm64-v8a/libbiliroamingx.so" not in archive.namelist():
            raise RuntimeError("APK does not contain the BiliRoamingX native module")
        abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")}
        if abis != {"arm64-v8a"}:
            raise RuntimeError(f"Unexpected native ABIs in the ARM64 release: {abis}")
    if DIST.exists() and any(DIST.iterdir()):
        raise RuntimeError("dist must be empty before publication")
    DIST.mkdir(exist_ok=True)
    tag = os.environ.get("GITHUB_REF_NAME", "local")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", tag):
        tag = "manual"
    name = f"Bilibili-{config['client']['version']}-BiliRoamingX-{tag}-arm64.apk"
    shutil.copy2(signed, DIST / name)
    shutil.copy2(integrations, DIST / integrations.name)
    shutil.copy2(patches, DIST / patches.name)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    metadata = {"source_commit": commit, "source_dirty": dirty, "tag": tag, "client": config["client"],
                "cli": config["cli"], "excluded_patches": config["excluded_patches"],
                "release_signer_sha256": signer, "apk": name, "apk_sha256": sha256(DIST / name),
                "validation": {"patches": "passed", "protobuf_dispatch": "passed", "integration_hierarchy": "passed", "signature_v1_v2_v3": "passed",
                               "zipalign_16k": "passed", "device_runtime": "not tested by this job"}}
    (DIST / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (DIST / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(p)}  {p.name}\n" for p in sorted(DIST.iterdir()) if p.is_file()), encoding="utf-8")
    limitations = ", ".join(config["excluded_patches"])
    (WORK / "release-notes.md").write_text(
        "**兼容性预览版。各项增强功能与真机验收结果见本 tag 的兼容性说明。**\n\n"
        f"哔哩哔哩 **{config['client']['version']}**，已集成哔哩漫游 X，Android 7.0+ / ARM64。\n\n"
        f"直接下载 **`{name}`** 安装，无需 Root、LSPosed 或另装模块。\n\n"
        "首次安装与官方包签名不同，需先备份数据并卸载官方版；本仓库后续版本使用固定签名，可覆盖升级。\n\n"
        "模块设置位于客户端设置内。区域相关功能仍取决于可用的解析服务。\n\n"
        f"本版明确停用的补丁：{limitations}。相应开关已禁用，详情见 [兼容性说明](https://github.com/another-sys/BiliRoamingX/blob/{tag}/docs/compatibility.md)。\n\n"
        f"源码提交：`{commit}`。源码及 GPL-3.0 许可见本 tag；构建信息、校验和见附件。\n\n"
        "本次 CI 验证了补丁、APK 身份、签名和对齐；真机功能验证状态见仓库兼容性文档。\n",
        encoding="utf-8")
    print(f"Ready: {DIST / name}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("tools", "source", "patch", "release"))
    command = parser.parse_args().command
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    {"tools": tools, "source": source, "patch": patch, "release": sign}[command](config)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        sys.exit(str(error))
