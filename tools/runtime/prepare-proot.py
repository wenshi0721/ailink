#!/usr/bin/env python3
"""
装配 proot 到 Android 的 jniLibs（两种 ABI）。

为什么需要这个脚本：
  * Android 10+ 只允许执行 APK 内嵌的二进制，且 jniLibs 里的文件必须叫 lib*.so；
  * Termux 的 proot 依赖 libtalloc.so.2 与 libandroid-shmem.so，而 APK 只能放 lib*.so，
    因此需要把 proot 的 DT_NEEDED 字符串就地改成 libtalloc.so（长度不变或更短，安全）；
  * proot 运行 guest 程序还需要 loader（ELF 解释器），同样改名后随包分发。

用法：
    python tools/runtime/prepare-proot.py            # 两种 ABI 都装
    python tools/runtime/prepare-proot.py --abi arm64-v8a

产物：
    android/app/src/main/jniLibs/<abi>/libproot.so
    android/app/src/main/jniLibs/<abi>/libtalloc.so
    android/app/src/main/jniLibs/<abi>/libandroid-shmem.so
    android/app/src/main/jniLibs/<abi>/libproot-loader.so
"""

import argparse
import io
import os
import sys
import tarfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
# 本文件位于 <root>/tools/runtime/，向上三级即工作区根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JNI = os.path.join(ROOT, "android", "app", "src", "main", "jniLibs")
CACHE = os.path.join(ROOT, ".cache", "termux-deb")

# Termux 架构名 -> Android ABI
ABIS = {"aarch64": "arm64-v8a", "x86_64": "x86_64"}

PACKAGES = {
    "proot": "pool/main/p/proot/proot_5.1.107.98_{arch}.deb",
    "libtalloc": "pool/main/libt/libtalloc/libtalloc_2.5.0_{arch}.deb",
    "libandroid-shmem": "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_{arch}.deb",
}

PREFIX = "data/data/com.termux/files/usr"


def download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    print(f"  ↓ {url}")
    urllib.request.urlretrieve(url, dest)
    return dest


def ar_members(path):
    """解析 deb（ar 归档），返回 [(name, bytes)]。"""
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise RuntimeError(f"不是 ar 归档: {path}")
        while True:
            header = f.read(60)
            if len(header) < 60:
                break
            name = header[0:16].decode().strip().rstrip("/")
            size = int(header[48:58].decode().strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            yield name, data


def extract_deb(deb_path, dest_dir):
    for name, data in ar_members(deb_path):
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(data)) as tf:
                tf.extractall(dest_dir)
            return


def patch_needed(binary_path, old: bytes, new: bytes):
    """把 DT_NEEDED 字符串就地替换；要求 new 不长于 old。"""
    if len(new) > len(old):
        raise ValueError("替换后的名字不能更长")
    with open(binary_path, "rb") as f:
        data = f.read()
    if old not in data:
        print(f"    ! 未找到 {old.decode()}，跳过")
        return False
    data = data.replace(old, new + b"\x00" * (len(old) - len(new)))
    with open(binary_path, "wb") as f:
        f.write(data)
    print(f"    ✓ {old.decode()} -> {new.decode()}")
    return True


def build_abi(arch, abi):
    print(f"\n== {arch} -> {abi}")
    tmp = os.path.join(CACHE, arch)
    debs = {}
    for name, pattern in PACKAGES.items():
        url = f"{REPO}/{pattern.format(arch=arch)}"
        debs[name] = download(url, os.path.join(tmp, f"{name}.deb"))

    for name, deb in debs.items():
        extract_deb(deb, os.path.join(tmp, name))

    out = os.path.join(JNI, abi)
    os.makedirs(out, exist_ok=True)

    proot_bin = os.path.join(tmp, "proot", PREFIX, "bin", "proot")
    talloc_lib = os.path.join(tmp, "libtalloc", PREFIX, "lib", "libtalloc.so.2.5.0")
    shmem_lib = os.path.join(tmp, "libandroid-shmem", PREFIX, "lib", "libandroid-shmem.so")
    loader = os.path.join(tmp, "proot", PREFIX, "libexec", "proot", "loader")

    for path in (proot_bin, talloc_lib, loader):
        if not os.path.exists(path):
            raise SystemExit(f"[ERR] 缺少文件: {path}")

    # proot 本体
    proot_out = os.path.join(out, "libproot.so")
    with open(proot_bin, "rb") as f:
        payload = f.read()
    with open(proot_out, "wb") as f:
        f.write(payload)
    print("    ✓ libproot.so")
    patch_needed(proot_out, b"libtalloc.so.2", b"libtalloc.so")

    # 依赖
    with open(talloc_lib, "rb") as f:
        talloc = f.read()
    with open(os.path.join(out, "libtalloc.so"), "wb") as f:
        f.write(talloc)
    print("    ✓ libtalloc.so")

    if os.path.exists(shmem_lib):
        with open(shmem_lib, "rb") as f:
            shmem = f.read()
        with open(os.path.join(out, "libandroid-shmem.so"), "wb") as f:
            f.write(shmem)
        print("    ✓ libandroid-shmem.so")
    else:
        # proot --version 不需要 shmem，但运行容器需要；缺失时给出提示
        print("    ! 缺少 libandroid-shmem.so（运行容器前必须补齐）")

    # guest loader
    with open(loader, "rb") as f:
        loader_data = f.read()
    with open(os.path.join(out, "libproot-loader.so"), "wb") as f:
        f.write(loader_data)
    print("    ✓ libproot-loader.so")

    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=list(ABIS.values()), default=None)
    args = parser.parse_args()

    print(f"输出目录: {JNI}")
    for arch, abi in ABIS.items():
        if args.abi and args.abi != abi:
            continue
        build_abi(arch, abi)
    print("\n完成。重新构建 APK 后，jniLibs 会随包解压到 nativeLibraryDir。")


if __name__ == "__main__":
    sys.exit(main())
