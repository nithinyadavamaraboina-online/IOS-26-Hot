#!/usr/bin/env python3
"""
Inspects, upgrades (with 2026 OpenELA 4.14 BPF kernel if present), and repacks RMX1931 boot.img
in exact Realme ABL Header v1 format (page_size=4096, Image.gz-dtb, LZ4 ramdisk, AVB v1.0 hash
footer padded to 100,663,296 bytes = 96 MiB).
"""
import gzip
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

BOOT_PARTITION_SIZE = 100663296  # 96 MiB exact on RMX1931


def clean_ramdisk_fstab_lz4(ramdisk_blob: bytes) -> bytes:
    """
    Unpacks the LZ4-legacy cpio ramdisk, strips inlinecrypt/wrappedkey/fileencryption
    from /data in fstab.qcom (matching /vendor/etc/fstab.qcom so Android 17 first-stage
    init + vold mount userdata cleanly on Linux 4.14), and repacks with lz4 -l -9.
    """
    if ramdisk_blob[:4] != bytes.fromhex("02214c18"):
        return ramdisk_blob
    with tempfile.TemporaryDirectory(prefix="a17_rd_") as tmpd:
        orig_lz4 = os.path.join(tmpd, "orig.lz4")
        new_lz4 = os.path.join(tmpd, "new.lz4")
        root_dir = os.path.join(tmpd, "root")
        os.makedirs(root_dir, exist_ok=True)
        with open(orig_lz4, "wb") as f:
            f.write(ramdisk_blob)
        subprocess.run(
            ["bash", "-c", f'cd "{root_dir}" && lz4 -dc "{orig_lz4}" | cpio -idm --quiet'],
            check=True,
        )
        fstab_p = os.path.join(root_dir, "fstab.qcom")
        if os.path.isfile(fstab_p):
            with open(fstab_p, "r", encoding="utf-8", errors="replace") as f:
                lines = f.read().splitlines()
            cleaned = []
            for ln in lines:
                if "\t/data\t" in ln or " /data " in ln:
                    ln = ln.replace(",inlinecrypt", "")
                    ln = ln.replace("errors=panic", "errors=remount-ro")
                    for tok in [
                        ",fileencryption=aes-256-xts:aes-256-cts:v2+inlinecrypt_optimized",
                        ",fileencryption=aes-256-xts:aes-256-cts:v2",
                        ",wrappedkey",
                        ",keydirectory=/metadata/vold/metadata_encryption",
                    ]:
                        ln = ln.replace(tok, "")
                cleaned.append(ln)
            with open(fstab_p, "w", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(cleaned) + "\n")
            os.chmod(fstab_p, 0o640)
            os.chown(fstab_p, 0, 0)
            print("[boot-repack] Cleaned /data inlinecrypt/wrappedkey flags in ramdisk fstab.qcom")

        subprocess.run(
            [
                "bash",
                "-c",
                f'cd "{root_dir}" && find . | LC_ALL=C sort | cpio -o -H newc --owner=0:0 --quiet | lz4 -l -9 > "{new_lz4}"',
            ],
            check=True,
        )
        with open(new_lz4, "rb") as f:
            repacked = f.read()
        if repacked[:4] != bytes.fromhex("02214c18"):
            raise RuntimeError(f"Repacked ramdisk lost LZ4-legacy magic: {repacked[:4].hex()}")
        return repacked


def parse_bootv1(path: str) -> dict:
    with open(path, "rb") as f:
        hdr = f.read(4096)
        magic = hdr[:8]
        if magic != b"ANDROID!":
            raise ValueError(f"Invalid boot magic in {path}: {magic!r}")
        (
            ksize, kaddr, rsize, raddr, ssize, saddr,
            tags, psize, hver, osver
        ) = struct.unpack("<8x10I", hdr[:48])
        name = hdr[48:64].split(b"\x00")[0]
        cmdline = hdr[64:576].split(b"\x00")[0] + hdr[1024:2048].split(b"\x00")[0]
        extra_id = hdr[576:608]

        def align(x: int) -> int:
            return ((x + psize - 1) // psize) * psize

        f.seek(psize)
        kernel = f.read(ksize)
        f.seek(psize + align(ksize))
        ramdisk = f.read(rsize)
        second = b""
        if ssize > 0:
            f.seek(psize + align(ksize) + align(rsize))
            second = f.read(ssize)

    return {
        "kaddr": kaddr,
        "raddr": raddr,
        "saddr": saddr,
        "tags": tags,
        "psize": psize,
        "hver": hver,
        "osver": osver,
        "name": name,
        "cmdline": cmdline,
        "id": extra_id,
        "kernel": kernel,
        "ramdisk": ramdisk,
        "second": second,
    }


def split_image_gz_dtb(blob: bytes) -> tuple[bytes, bytes, bytes]:
    """Splits Image.gz-dtb into (raw_decompressed_kernel, gzip_part, appended_dtb)."""
    if blob[:2] != b"\x1f\x8b":
        raise ValueError("Kernel in boot.img does not start with gzip magic 1f8b")
    # Locate FDT magic d00dfeed after the gzip stream
    # Walk decompress stream via zlib unused_data!
    import zlib
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    raw_k = d.decompress(blob)
    rem = d.unused_data
    gz_len = len(blob) - len(rem)
    # Verify DTB starts in rem
    fdt_idx = rem.find(b"\xd0\x0d\xfe\xed")
    dtb = rem[fdt_idx:] if fdt_idx >= 0 else b""
    return raw_k, blob[:gz_len], dtb


def extract_kernel_banner_and_ikconfig(raw_kernel: bytes) -> tuple[str, str]:
    m = re.search(rb"Linux version [^\x00\n]+", raw_kernel)
    banner = m.group(0).decode("latin1", errors="replace") if m else "unknown"
    ikcfg = ""
    idx = raw_kernel.find(b"IKCFG_ST\x1f\x8b\x08")
    if idx >= 0:
        import zlib
        try:
            d = zlib.decompressobj(16 + zlib.MAX_WBITS)
            ikcfg = d.decompress(raw_kernel[idx + 8:]).decode("utf-8", errors="replace")
        except Exception:
            pass
    return banner, ikcfg


def build_bootv1(meta: dict, out_path: str, avbtool_py: str) -> None:
    psize = meta["psize"]
    kernel = meta["kernel"]
    ramdisk = meta["ramdisk"]
    second = meta["second"]

    hdr = bytearray(psize)
    hdr[:8] = b"ANDROID!"
    struct.pack_into(
        "<10I", hdr, 8,
        len(kernel), meta["kaddr"],
        len(ramdisk), meta["raddr"],
        len(second), meta["saddr"],
        meta["tags"], psize,
        meta["hver"], meta["osver"]
    )
    hdr[48:48 + min(16, len(meta["name"]))] = meta["name"][:16]
    cmd = meta["cmdline"]
    hdr[64:64 + min(512, len(cmd))] = cmd[:512]
    if len(cmd) > 512:
        hdr[1024:1024 + min(1024, len(cmd) - 512)] = cmd[512:1536]
    hdr[576:608] = meta["id"][:32]

    def pad_page(b: bytes) -> bytes:
        rem = len(b) % psize
        return b if rem == 0 else b + b"\x00" * (psize - rem)

    with open(out_path, "wb") as f:
        f.write(hdr)
        f.write(pad_page(kernel))
        f.write(pad_page(ramdisk))
        if second:
            f.write(pad_page(second))

    # Append Realme ABL AVB v1.0 unsigned hash footer padded to 100,663,296 B
    subprocess.run(
        [
            "python3", avbtool_py, "add_hash_footer",
            "--image", out_path,
            "--partition_name", "boot",
            "--partition_size", str(BOOT_PARTITION_SIZE),
        ],
        check=True
    )
    # Pad image to exact partition size if avbtool leaves sparse/short file
    cur = os.path.getsize(out_path)
    if cur != BOOT_PARTITION_SIZE:
        raise RuntimeError(f"Unexpected boot.img size after avbtool: {cur} != {BOOT_PARTITION_SIZE}")


def main() -> int:
    if len(sys.argv) < 5:
        print("Usage: repack_boot_a17.py <donor_boot.img> <anykernel_zip_or_none> <avbtool.py> <out_boot.img>")
        return 1

    donor_boot, ak3_zip, avbtool_py, out_boot = sys.argv[1:5]
    meta = parse_bootv1(donor_boot)
    raw_k, gz_k, donor_dtb = split_image_gz_dtb(meta["kernel"])
    banner, ikcfg = extract_kernel_banner_and_ikconfig(raw_k)
    print(f"[boot-repack] Donor kernel: {banner}")
    print(f"[boot-repack] Donor cmdline: {meta['cmdline'].decode('latin1')}")
    print(f"[boot-repack] Donor DTB size: {len(donor_dtb)} B, Ramdisk size: {len(meta['ramdisk'])} B")

    # Check if 2026 OpenELA AnyKernel3 zip supplies a newer kernel Image.gz / Image.gz-dtb
    if ak3_zip != "none" and os.path.isfile(ak3_zip):
        with zipfile.ZipFile(ak3_zip, "r") as zf:
            names = zf.namelist()
            cand_gz_dtb = next((n for n in names if n.endswith("Image.gz-dtb")), None)
            cand_gz = next((n for n in names if n.endswith("Image.gz")), None)
            cand_img = next((n for n in names if os.path.basename(n) == "Image"), None)
            cand_dtb = next((n for n in names if os.path.basename(n) in ("dtb", "msmnile.dtb")), None)

            new_k_blob = None
            if cand_gz_dtb:
                new_k_blob = zf.read(cand_gz_dtb)
            elif cand_gz:
                gz_bytes = zf.read(cand_gz)
                dtb_bytes = zf.read(cand_dtb) if cand_dtb else donor_dtb
                new_k_blob = gz_bytes + dtb_bytes
            elif cand_img:
                raw_new = zf.read(cand_img)
                gz_bytes = gzip.compress(raw_new, compresslevel=9)
                dtb_bytes = zf.read(cand_dtb) if cand_dtb else donor_dtb
                new_k_blob = gz_bytes + dtb_bytes

            if new_k_blob:
                rk2, _, dtb2 = split_image_gz_dtb(new_k_blob)
                b2, ik2 = extract_kernel_banner_and_ikconfig(rk2)
                print(f"[boot-repack] Upgraded to 2026 OpenELA kernel: {b2} (DTB {len(dtb2)} B)")
                meta["kernel"] = new_k_blob
                banner, ikcfg = b2, (ik2 or ikcfg)

    if ikcfg:
        for flag in ["CONFIG_BPF=y", "CONFIG_BPF_SYSCALL=y", "CONFIG_BPF_JIT=y", "CONFIG_CGROUP_BPF=y"]:
            present = flag in ikcfg
            print(f"[boot-repack] Kernel flag {flag}: {'PRESENT' if present else 'NOT_IN_IKCFG'}")

    meta["ramdisk"] = clean_ramdisk_fstab_lz4(meta["ramdisk"])
    build_bootv1(meta, out_boot, avbtool_py)
    print(f"[boot-repack] Built {out_boot} ({os.path.getsize(out_boot):,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
