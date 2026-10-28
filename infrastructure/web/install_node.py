"""Install the pinned upstream Node binary while its Docker tag is unpublished."""

import hashlib
import io
import os
from pathlib import Path
import platform
import tarfile
from urllib.request import urlopen

version = os.environ["NODE_VERSION"]
architecture = {"x86_64": "x64", "aarch64": "arm64"}[platform.machine()]
expected = os.environ[f"NODE_SHA256_{architecture.upper()}"]
name = f"node-v{version}-linux-{architecture}"
with urlopen(f"https://nodejs.org/dist/v{version}/{name}.tar.xz", timeout=120) as source:
    archive = source.read()
if hashlib.sha256(archive).hexdigest() != expected:
    raise RuntimeError("Node archive SHA-256 mismatch")
with tarfile.open(fileobj=io.BytesIO(archive)) as package:
    package.extractall("/opt", filter="data")
Path("/opt/node").symlink_to(Path("/opt") / name)
