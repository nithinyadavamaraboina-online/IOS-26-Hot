#!/usr/bin/env python3
"""Build a POSIX ustar root overlay (work/boot/ios26-overlay.tar) for iOS 26 bring-up.

Reads /System/Library/xpc/launchd.plist strictly read-only from an HFS+/HFSX
root image (default ~/ios26-root-rmx1931.img) or an extracted plist,
strips crash-looping / hardware-absent LaunchDaemons via sweet-root-tweaks.py,
configures dropbear/wland/hotstored/hotauthd, and optionally stages de-PAC'd DSC stub
subcaches, HotMetal2.bundle, and IOS-26-Hot userland binaries/apps/drivers into a
deterministic POSIX ustar archive for mkpayload.py --overlay.
"""
import argparse
import importlib.util
import io
import os
import plistlib
import stat
import struct
import sys
import tarfile
import zlib

CPBITMAP_FORMAT_PNG = 4
CPBITMAP_MAGIC = 0xDCB543A2
TIMED_UID = 266
MOBILE_UID = 501

KEEP_DAEMON_KEYS = (
    '/System/Library/LaunchDaemons/com.apple.lsd.plist',
    '/System/Library/LaunchDaemons/com.apple.mobile.installd.plist',
    '/System/Library/LaunchDaemons/com.apple.installcoordinationd.plist',
    '/System/Library/LaunchDaemons/com.apple.mobileactivationd.plist',
    '/System/Library/LaunchDaemons/org.iosonpixel.dropbear.plist',
    '/System/Library/LaunchDaemons/org.iosonpixel.wland.plist',
    '/System/Library/LaunchDaemons/com.ios26hot.hotstored.plist',
    '/System/Library/LaunchDaemons/com.ios26hot.hotauthd.plist',
)

SKIP_USERLAND_BINS = {'m2vtest'}


def _load_tweaks_module():
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location('sweet_root_tweaks', os.path.join(here, 'sweet-root-tweaks.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class HFSReadOnly:
    """Minimal read-only HFS+/HFSX catalog + data fork reader (never writes or mounts)."""

    def __init__(self, path):
        self.f = open(path, 'rb')
        self.f.seek(1024)
        vh = self.f.read(512)
        sig, _ = struct.unpack_from('>2sH', vh, 0)
        if sig not in (b'H+', b'HX'):
            raise ValueError(f'{path}: unexpected HFS+/HFSX volume signature {sig!r}')
        self.block_size, = struct.unpack_from('>I', vh, 40)
        self.extents_fork = self._parse_fork(vh, 192)
        self.catalog_fork = self._parse_fork(vh, 272)
        hdr0 = self._read_fork_bytes(self.catalog_fork, 0, 512)
        self.cat_node_size, = struct.unpack_from('>H', hdr0, 14 + 18)
        self.cat_first_leaf, = struct.unpack_from('>I', hdr0, 14 + 10)
        ext0 = self._read_fork_bytes(self.extents_fork, 0, 512)
        self.ext_node_size, = struct.unpack_from('>H', ext0, 14 + 18)
        self.ext_first_leaf, = struct.unpack_from('>I', ext0, 14 + 10)

    def close(self):
        self.f.close()

    @staticmethod
    def _parse_fork(buf, off):
        logical_size, _, total_blocks = struct.unpack_from('>QII', buf, off)
        extents = []
        for i in range(8):
            sb, bc = struct.unpack_from('>II', buf, off + 16 + i * 8)
            if bc:
                extents.append((sb, bc))
        return (logical_size, total_blocks, extents)

    def _overflow_extents(self, cnid, fork_type, start_block):
        out = []
        node_num = self.ext_first_leaf
        ns = self.ext_node_size
        while node_num != 0:
            node = self._read_fork_bytes(self.extents_fork, node_num * ns, ns)
            flink, _, kind, _, num_recs = struct.unpack_from('>IIbbH', node, 0)
            assert kind == -1
            for r in range(num_recs):
                rec_off, = struct.unpack_from('>H', node, ns - 2 * (r + 1))
                key_len, ftype, _, file_id, sb0 = struct.unpack_from('>HBBII', node, rec_off)
                if file_id == cnid and ftype == fork_type and sb0 >= start_block:
                    val_off = rec_off + 2 + key_len
                    for i in range(8):
                        sb, bc = struct.unpack_from('>II', node, val_off + i * 8)
                        if bc:
                            out.append((sb, bc))
            node_num = flink
        return out

    def _read_fork_bytes(self, fork, offset, size, cnid=None):
        logical_size, total_blocks, extents = fork
        if offset + size > logical_size:
            size = max(0, logical_size - offset)
        have_blocks = sum(bc for _, bc in extents)
        if cnid is not None and have_blocks < total_blocks:
            extents = list(extents) + self._overflow_extents(cnid, 0, have_blocks)
        out = bytearray()
        cur_off = 0
        bs = self.block_size
        for sb, bc in extents:
            ext_len = bc * bs
            if offset < cur_off + ext_len and len(out) < size:
                rel = max(0, offset - cur_off)
                take = min(size - len(out), ext_len - rel)
                self.f.seek(sb * bs + rel)
                out += self.f.read(take)
            cur_off += ext_len
            if len(out) >= size:
                break
        if len(out) != size:
            raise IOError(f'short HFS fork read ({len(out)} != {size})')
        return bytes(out)

    def _lookup_child(self, parent_cnid, target_name):
        node_num = self.cat_first_leaf
        ns = self.cat_node_size
        while node_num != 0:
            node = self._read_fork_bytes(self.catalog_fork, node_num * ns, ns)
            flink, _, kind, _, num_recs = struct.unpack_from('>IIbbH', node, 0)
            assert kind == -1
            for r in range(num_recs):
                rec_off, = struct.unpack_from('>H', node, ns - 2 * (r + 1))
                key_len, pid = struct.unpack_from('>HI', node, rec_off)
                if pid == parent_cnid:
                    nchars, = struct.unpack_from('>H', node, rec_off + 6)
                    name = node[rec_off + 8:rec_off + 8 + nchars * 2].decode('utf-16-be', errors='replace')
                    if name == target_name:
                        val_off = rec_off + 2 + key_len
                        if val_off & 1:
                            val_off += 1
                        rec_type, = struct.unpack_from('>h', node, val_off)
                        return rec_type, node[val_off:]
                elif pid > parent_cnid:
                    return None
            node_num = flink
        return None

    def read_file(self, path):
        parts = [p for p in path.strip('/').split('/') if p]
        cnid = 2
        for i, part in enumerate(parts):
            res = self._lookup_child(cnid, part)
            if res is None:
                raise FileNotFoundError(f'{path}: component {part!r} not found under CNID {cnid}')
            rec_type, val = res
            if i < len(parts) - 1:
                if rec_type != 1:
                    raise IsADirectoryError(f'{part}: expected folder (1), got {rec_type}')
                cnid, = struct.unpack_from('>I', val, 8)
            else:
                if rec_type != 2:
                    raise ValueError(f'{part}: expected file (2), got {rec_type}')
                file_cnid, = struct.unpack_from('>I', val, 8)
                uid, gid, _, _, mode = struct.unpack_from('>IIBBH', val, 32)
                data_fork = self._parse_fork(val, 88)
                return self._read_fork_bytes(data_fork, 0, data_fork[0], cnid=file_cnid), uid, gid, mode & 0o7777


def add_ustar_bytes(tf, rel_path, data, mode=0o644, uid=0, gid=0):
    rel_path = rel_path.lstrip('/')
    ti = tarfile.TarInfo(name=rel_path)
    ti.size = len(data)
    ti.mode = mode
    ti.uid = uid
    ti.gid = gid
    ti.uname = 'root'
    ti.gname = 'wheel'
    ti.mtime = 0
    ti.type = tarfile.REGTYPE
    tf.addfile(ti, io.BytesIO(data))


def add_ustar_dir(tf, rel_path, mode=0o755, uid=0, gid=0):
    rel_path = rel_path.strip('/')
    ti = tarfile.TarInfo(name=rel_path)
    ti.size = 0
    ti.mode = mode
    ti.uid = uid
    ti.gid = gid
    ti.uname = 'root'
    ti.gname = 'wheel'
    ti.mtime = 0
    ti.type = tarfile.DIRTYPE
    tf.addfile(ti)


def add_ustar_symlink(tf, rel_path, target):
    rel_path = rel_path.lstrip('/')
    ti = tarfile.TarInfo(name=rel_path)
    ti.type = tarfile.SYMTYPE
    ti.linkname = target
    ti.mode = 0o777
    ti.uid = 0
    ti.gid = 0
    ti.uname = 'root'
    ti.gname = 'wheel'
    ti.mtime = 0
    tf.addfile(ti)


def _decode_png_rgb(png_bytes):
    """Decode an 8-bit non-interlaced PNG into contiguous 24-bit RGB bytes (pure stdlib)."""
    if png_bytes[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('input wallpaper is not a valid PNG file')
    pos = 8
    w = h = bit_depth = color_type = interlace = None
    palette = None
    idat_parts = []
    while pos + 8 <= len(png_bytes):
        clen, ctype = struct.unpack_from('>I4s', png_bytes, pos)
        pos += 8
        cdata = png_bytes[pos:pos + clen]
        pos += clen + 4  # skip CRC
        if ctype == b'IHDR':
            w, h, bit_depth, color_type, comp, filt, interlace = struct.unpack('>IIBBBBB', cdata[:13])
            if bit_depth != 8 or comp != 0 or filt != 0 or interlace != 0:
                raise ValueError(f'unsupported PNG format: depth={bit_depth} color={color_type} interlace={interlace}')
            if color_type not in (0, 2, 3, 4, 6):
                raise ValueError(f'unsupported PNG color_type={color_type}')
        elif ctype == b'PLTE':
            palette = cdata
        elif ctype == b'IDAT':
            idat_parts.append(cdata)
        elif ctype == b'IEND':
            break
    if not w or not h:
        raise ValueError('PNG missing IHDR')
    bpp = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    stride = w * bpp
    raw = zlib.decompress(b''.join(idat_parts))
    expected = h * (1 + stride)
    if len(raw) < expected:
        raise ValueError(f'short decompressed PNG stream ({len(raw)} < {expected})')

    prev = bytearray(stride)
    rgb = bytearray(w * h * 3)
    src_off = 0
    for y in range(h):
        ftype = raw[src_off]
        src_off += 1
        cur = bytearray(raw[src_off:src_off + stride])
        src_off += stride
        if ftype == 1:  # Sub
            for i in range(bpp, stride):
                cur[i] = (cur[i] + cur[i - bpp]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(bpp):
                cur[i] = (cur[i] + (prev[i] >> 1)) & 0xFF
            for i in range(bpp, stride):
                cur[i] = (cur[i] + ((cur[i - bpp] + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(bpp):
                cur[i] = (cur[i] + prev[i]) & 0xFF
            for i in range(bpp, stride):
                a = cur[i - bpp]
                b = prev[i]
                c = prev[i - bpp]
                p = a + b - c
                pa = p - a if p >= a else a - p
                pb = p - b if p >= b else b - p
                pc = p - c if p >= c else c - p
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 0xFF
        elif ftype != 0:
            raise ValueError(f'unknown PNG scanline filter {ftype} at row {y}')

        dst_off = y * w * 3
        if color_type == 2:
            rgb[dst_off:dst_off + w * 3] = cur
        elif color_type == 6:
            alphas = cur[3::4]
            if min(alphas) == 255:
                rgb[dst_off:dst_off + w * 3:3] = cur[0::4]
                rgb[dst_off + 1:dst_off + w * 3:3] = cur[1::4]
                rgb[dst_off + 2:dst_off + w * 3:3] = cur[2::4]
            else:
                for x in range(w):
                    si = x * 4
                    di = dst_off + x * 3
                    al = cur[si + 3]
                    rgb[di] = (cur[si] * al + 127) // 255
                    rgb[di + 1] = (cur[si + 1] * al + 127) // 255
                    rgb[di + 2] = (cur[si + 2] * al + 127) // 255
        elif color_type == 0:
            rgb[dst_off:dst_off + w * 3:3] = cur
            rgb[dst_off + 1:dst_off + w * 3:3] = cur
            rgb[dst_off + 2:dst_off + w * 3:3] = cur
        elif color_type == 4:
            for x in range(w):
                v = (cur[x * 2] * cur[x * 2 + 1] + 127) // 255
                di = dst_off + x * 3
                rgb[di] = rgb[di + 1] = rgb[di + 2] = v
        elif color_type == 3:
            if not palette:
                raise ValueError('indexed PNG missing PLTE chunk')
            for x in range(w):
                pi = cur[x] * 3
                di = dst_off + x * 3
                rgb[di:di + 3] = palette[pi:pi + 3]
        prev = cur
    return w, h, bytes(rgb)


def _resize_rgb_cover(rgb, src_w, src_h, dst_w, dst_h):
    """Center-crop (aspect-fill) and bilinear-resample 24-bit RGB to dst_w x dst_h."""
    if src_w == dst_w and src_h == dst_h:
        return rgb
    src_aspect = src_w / src_h
    dst_aspect = dst_w / dst_h
    if src_aspect > dst_aspect:
        crop_h = float(src_h)
        crop_w = crop_h * dst_aspect
        off_x = (src_w - crop_w) * 0.5
        off_y = 0.0
    else:
        crop_w = float(src_w)
        crop_h = crop_w / dst_aspect
        off_x = 0.0
        off_y = (src_h - crop_h) * 0.5

    scale_x = crop_w / dst_w
    scale_y = crop_h / dst_h
    max_x = src_w - 1
    max_y = src_h - 1

    x_tab = []
    for dx in range(dst_w):
        sx = off_x + (dx + 0.5) * scale_x - 0.5
        if sx <= 0.0:
            ix0 = ix1 = 0
            wx1 = 0
        elif sx >= max_x:
            ix0 = ix1 = max_x
            wx1 = 0
        else:
            ix0 = int(sx)
            ix1 = ix0 + 1
            wx1 = int((sx - ix0) * 256.0 + 0.5)
        x_tab.append((ix0 * 3, ix1 * 3, 256 - wx1, wx1))

    h_cache = {}
    src_stride = src_w * 3
    dst_stride = dst_w * 3

    def get_hrow(iy):
        row = h_cache.get(iy)
        if row is not None:
            return row
        base = iy * src_stride
        srow = rgb[base:base + src_stride]
        out_r = bytearray(dst_stride)
        di = 0
        for o0, o1, wx0, wx1 in x_tab:
            out_r[di] = (srow[o0] * wx0 + srow[o1] * wx1 + 128) >> 8
            out_r[di + 1] = (srow[o0 + 1] * wx0 + srow[o1 + 1] * wx1 + 128) >> 8
            out_r[di + 2] = (srow[o0 + 2] * wx0 + srow[o1 + 2] * wx1 + 128) >> 8
            di += 3
        h_cache[iy] = out_r
        return out_r

    out = bytearray(dst_h * dst_stride)
    for dy in range(dst_h):
        sy = off_y + (dy + 0.5) * scale_y - 0.5
        if sy <= 0.0:
            iy0 = iy1 = 0
            wy1 = 0
        elif sy >= max_y:
            iy0 = iy1 = max_y
            wy1 = 0
        else:
            iy0 = int(sy)
            iy1 = iy0 + 1
            wy1 = int((sy - iy0) * 256.0 + 0.5)
        r0 = get_hrow(iy0)
        d_off = dy * dst_stride
        if wy1 == 0 or iy0 == iy1:
            out[d_off:d_off + dst_stride] = r0
        else:
            r1 = get_hrow(iy1)
            wy0 = 256 - wy1
            out[d_off:d_off + dst_stride] = bytes(
                (a * wy0 + b * wy1 + 128) >> 8 for a, b in zip(r0, r1)
            )
    return bytes(out)


def _png_chunk(ctype, cdata):
    crc = zlib.crc32(ctype)
    crc = zlib.crc32(cdata, crc) & 0xFFFFFFFF
    return struct.pack('>I4s', len(cdata), ctype) + cdata + struct.pack('>I', crc)


def _encode_png_rgb(rgb, width, height):
    """Encode contiguous 24-bit RGB bytes as a standard 8-bit PNG (pure stdlib)."""
    stride = width * 3
    scanlines = bytearray(height * (1 + stride))
    for y in range(height):
        soff = y * stride
        doff = y * (1 + stride)
        scanlines[doff] = 1  # Sub filter
        row = rgb[soff:soff + stride]
        scanlines[doff + 1:doff + 4] = row[:3]
        scanlines[doff + 4:doff + 1 + stride] = bytes(
            (b - a) & 0xFF for a, b in zip(row, row[3:])
        )
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    idat = zlib.compress(bytes(scanlines), 9)
    return (
        b'\x89PNG\r\n\x1a\n'
        + _png_chunk(b'IHDR', ihdr)
        + _png_chunk(b'IDAT', idat)
        + _png_chunk(b'IEND', b'')
    )


def _encode_cpbitmap_png(png_bytes, width, height):
    """Wrap PNG bytes in an AppSupport CPBitmap v2 container (format=4 PNG, magic=0xdcb543a2)."""
    trailer = struct.pack('<7I', 0, 0, width, height, CPBITMAP_FORMAT_PNG, 1, CPBITMAP_MAGIC)
    return png_bytes + trailer


def _parse_wh(spec):
    parts = spec.lower().split('x')
    if len(parts) != 2 or not parts[0].isdigit() or not parts[1].isdigit():
        raise ValueError(f'invalid --wallpaper-size {spec!r}, expected WxH (e.g. 1125x2436)')
    w, h = int(parts[0]), int(parts[1])
    if w < 16 or h < 16:
        raise ValueError(f'wallpaper dimensions too small: {w}x{h}')
    return w, h


def add_tree(tf, src_dir, dst_prefix, force_exec_names=()):
    dst_prefix = dst_prefix.strip('/')
    add_ustar_dir(tf, dst_prefix, 0o755)
    for cur_root, dirs, files in os.walk(src_dir):
        dirs.sort()
        files.sort()
        rel_sub = os.path.relpath(cur_root, src_dir)
        cur_dst = dst_prefix if rel_sub == '.' else f'{dst_prefix}/{rel_sub.replace(os.sep, "/")}'
        if rel_sub != '.':
            add_ustar_dir(tf, cur_dst, 0o755)
        for fn in files:
            full = os.path.join(cur_root, fn)
            st = os.lstat(full)
            if stat.S_ISLNK(st.st_mode):
                add_ustar_symlink(tf, f'{cur_dst}/{fn}', os.readlink(full))
            elif stat.S_ISREG(st.st_mode):
                is_exec = bool(st.st_mode & 0o111) or fn in force_exec_names
                with open(full, 'rb') as f:
                    add_ustar_bytes(tf, f'{cur_dst}/{fn}', f.read(), mode=0o755 if is_exec else 0o644)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('-o', '--output', default='work/boot/ios26-overlay.tar', help='output POSIX ustar archive')
    ap.add_argument('--root-img', default=os.path.expanduser('~/ios26-root-rmx1931.img'),
                    help='read-only HFS+/HFSX rootfs image to extract launchd.plist from')
    ap.add_argument('--launchd-plist', help='optional pre-extracted launchd.plist (overrides --root-img)')
    ap.add_argument('--hotmetal2', '--sweetmetal2', dest='hotmetal2',
                    help='optional path to HotMetal2.bundle to stage under /System/Library/Extensions')
    ap.add_argument('--userland-dir', help='optional extracted userland dir (Applications/, HAL/, bin/, lib/)')
    ap.add_argument('--dsc-stubs-dir', help='optional dir of de-PACd dyld_shared_cache_arm64e.* stub subcaches')
    ap.add_argument('--mtl2-clients',
                    default='*\n-chronod\n-cameracaptured\n-iconservicesagent\n-WidgetRenderer_Default',
                    help='/private/var/local/mtl2-clients content')
    ap.add_argument('--wallpaper', help='optional PNG image to stage as Lock Screen and Home Screen wallpaper')
    ap.add_argument('--wallpaper-size', default='1125x2499',
                    help='target WxH for full-size wallpaper (default: 1125x2499)')
    ap.add_argument('--timezone', default='Asia/Kolkata',
                    help='system IANA timezone (default: Asia/Kolkata; empty string disables)')
    ap.add_argument('--enable-cellular', action='store_true',
                    help='register org.iosonpixel.cellulard LaunchDaemon (CoreTelephony XPC + QRTR QMI)')
    ap.add_argument('--enable-adsp', action='store_true',
                    help='register org.iosonpixel.adspd LaunchDaemon (SM8150 LPASS ADSP + MAX98927 audio)')
    ap.add_argument('--wland-wrapper', help='optional wrapper path for org.iosonpixel.wland ProgramArguments[0]')
    ap.add_argument('--selftest-bin', help='optional compiled arm64 self-test Mach-O for guarded test boots')
    ap.add_argument('--selftest-script', help='optional bring-up self-test shell script for guarded test boots')
    ap.add_argument('--extra', action='append', default=[],
                    help='extra file SRC:DST[:OCTAL_MODE] to include in the overlay (repeatable)')
    args = ap.parse_args()

    tweaks = _load_tweaks_module()
    tz_name = (args.timezone or '').strip().strip('/')

    build_ver, prod_ver = '23F84', '26.5.2'
    jetsam_bytes, j_uid, j_gid, j_mode = None, 0, 0, 0o644
    blastdoor_plists = []
    mobile_global_prefs = {'AppleLocale': 'en_US', 'AppleLanguages': ['en']}
    sys_global_prefs = {}
    sb_ff_plist = None
    tu_ff_plist = None
    va_disabled_files = []
    has_idle_xpc = bool(
        args.userland_dir and (
            os.path.isfile(os.path.join(args.userland_dir, 'bin', 'hot-idle-xpc'))
            or os.path.isfile(os.path.join(args.userland_dir, 'bin', 'sweet-idle-xpc'))
        )
    )

    if args.launchd_plist:
        with open(args.launchd_plist, 'rb') as f:
            raw_plist = f.read()
        uid, gid, mode = 0, 80, 0o644
    else:
        hfs = HFSReadOnly(args.root_img)
        try:
            raw_plist, uid, gid, mode = hfs.read_file('/System/Library/xpc/launchd.plist')
            for sv_rel in ('/System/Library/CoreServices/SystemVersion.plist',
                           '/private/preboot/Cryptexes/OS/System/Library/CoreServices/SystemVersion.plist'):
                try:
                    raw_sv, _, _, _ = hfs.read_file(sv_rel)
                    sv = plistlib.loads(raw_sv)
                    build_ver = sv.get('ProductBuildVersion', build_ver)
                    prod_ver = sv.get('ProductVersion', prod_ver)
                    break
                except FileNotFoundError:
                    pass
            try:
                raw_j, j_uid, j_gid, j_mode = hfs.read_file(
                    '/System/Library/LaunchDaemons/com.apple.jetsamproperties.D421.plist')
                j_plist = plistlib.loads(raw_j)
                j_raised = tweaks.patch_jetsam_limits(j_plist, min_mb=64)
                jetsam_bytes = plistlib.dumps(j_plist, fmt=plistlib.FMT_BINARY)
                print(f'jetsamproperties.D421.plist: raised {j_raised} limits to >= 64 MB')
            except FileNotFoundError:
                pass
            for bd_rel in (
                'System/Library/PrivateFrameworks/MessagesBlastDoorSupport.framework/XPCServices/MessagesBlastDoorService.xpc/Info.plist',
                'System/Library/PrivateFrameworks/IDSBlastDoorSupport.framework/XPCServices/IDSBlastDoorService.xpc/Info.plist',
            ):
                try:
                    raw_bd, bd_uid, bd_gid, bd_mode = hfs.read_file('/' + bd_rel)
                    bd_plist = plistlib.loads(raw_bd)
                    if isinstance(bd_plist.get('XPCService'), dict):
                        bd_plist['XPCService']['ReslideSharedCache'] = False
                    blastdoor_plists.append((bd_rel, plistlib.dumps(bd_plist, fmt=plistlib.FMT_BINARY), bd_mode, bd_uid, bd_gid))
                except FileNotFoundError:
                    pass
            if not has_idle_xpc:
                for idle_rel in ('/private/var/local/bin/hot-idle-xpc', '/private/var/local/bin/sweet-idle-xpc'):
                    try:
                        hfs.read_file(idle_rel)
                        has_idle_xpc = True
                        break
                    except FileNotFoundError:
                        pass
            if tz_name:
                tz_rel = f'/usr/share/zoneinfo.default/{tz_name}'
                raw_tz, _, _, _ = hfs.read_file(tz_rel)
                if len(raw_tz) < 20 or raw_tz[:4] != b'TZif':
                    raise ValueError(f'{tz_rel} in root image is not a valid TZif file')
                try:
                    raw_mgp, _, _, _ = hfs.read_file('/private/var/mobile/Library/Preferences/.GlobalPreferences.plist')
                    loaded_mgp = plistlib.loads(raw_mgp)
                    if isinstance(loaded_mgp, dict):
                        mobile_global_prefs.update(loaded_mgp)
                except FileNotFoundError:
                    pass
                try:
                    raw_sgp, _, _, _ = hfs.read_file('/private/var/preferences/.GlobalPreferences.plist')
                    loaded_sgp = plistlib.loads(raw_sgp)
                    if isinstance(loaded_sgp, dict):
                        sys_global_prefs.update(loaded_sgp)
                except FileNotFoundError:
                    pass
            if args.wallpaper:
                try:
                    raw_ff, _, _, _ = hfs.read_file('/System/Library/FeatureFlags/Domain/SpringBoard.plist')
                    loaded_ff = plistlib.loads(raw_ff)
                    if isinstance(loaded_ff, dict):
                        sb_ff_plist = loaded_ff
                except FileNotFoundError:
                    pass
            try:
                raw_tu_ff, _, _, _ = hfs.read_file('/System/Library/FeatureFlags/Domain/TelephonyUtilities.plist')
                loaded_tu_ff = plistlib.loads(raw_tu_ff)
                if isinstance(loaded_tu_ff, dict):
                    tu_ff_plist = loaded_tu_ff
            except FileNotFoundError:
                pass
            try:
                va_info, va_uid, va_gid, va_mode = hfs.read_file(
                    '/Library/Audio/Plug-Ins/HAL.disabled/VirtualAudio.plugin/Info.plist')
                va_disabled_files.append(('Info.plist', va_info, va_mode, va_uid, va_gid))
                try:
                    va_loc, vl_uid, vl_gid, vl_mode = hfs.read_file(
                        '/Library/Audio/Plug-Ins/HAL.disabled/VirtualAudio.plugin/Localizable.loctable')
                    va_disabled_files.append(('Localizable.loctable', va_loc, vl_mode, vl_uid, vl_gid))
                except FileNotFoundError:
                    pass
            except FileNotFoundError:
                pass
        finally:
            hfs.close()

    plist = plistlib.loads(raw_plist)
    before_count = len(plist['LaunchDaemons'])
    renamed, removed = tweaks.clean_launchd_plist(plist)

    jobs = plist['LaunchDaemons']
    # Ensure backboardd never selects a 10-bit b3a8 / compressed swapchain on Boot #1
    # before SweetMetal2.bundle loads (+2.6 s after IOMFB open).
    bb_key = '/System/Library/LaunchDaemons/com.apple.backboardd.plist'
    if bb_key in jobs and isinstance(jobs[bb_key], dict):
        bb_env = dict(jobs[bb_key].get('EnvironmentVariables') or {})
        bb_env['CA_DISABLE_FRAMEBUFFER_COMPRESSION'] = '1'
        jobs[bb_key]['EnvironmentVariables'] = bb_env

    # Dropbear v2024.86 is built without syslog (-E is invalid; stderr is default).
    db_key = '/System/Library/LaunchDaemons/org.iosonpixel.dropbear.plist'
    if db_key in jobs:
        jobs[db_key]['ProgramArguments'] = ['/var/local/bin/dropbearmulti', 'dropbear', '-F', '-R', '-p', '22']
        jobs[db_key]['ProcessType'] = 'Interactive'

    # Give wland Interactive QoS + non-throttled I/O + Nice -10 so MPSS GLINK/rmtfs/TFTP
    # handshakes never starve under Darwin-BG scheduling during boot.
    wl_key = '/System/Library/LaunchDaemons/org.iosonpixel.wland.plist'
    if wl_key in jobs:
        jobs[wl_key]['Nice'] = -10
        jobs[wl_key]['ProcessType'] = 'Interactive'
        jobs[wl_key]['LowPriorityIO'] = False
        jobs[wl_key]['LegacyTimers'] = True
        if args.wland_wrapper:
            jobs[wl_key].pop('Program', None)
            jobs[wl_key]['ProgramArguments'] = [args.wland_wrapper, 'auto']

    diag_key = '/System/Library/LaunchDaemons/org.iosonpixel.sweetdiag.plist'
    if diag_key in jobs:
        jobs[diag_key]['ProcessType'] = 'Interactive'

    # Remove legacy Sweet* LaunchDaemon keys when upgrading an existing root image.
    jobs.pop('/System/Library/LaunchDaemons/org.iosonpixel.sweetstored.plist', None)
    jobs.pop('/System/Library/LaunchDaemons/org.iosonpixel.sweetauthd.plist', None)

    # Ensure hotstored root helper is registered for HotStore.app (127.0.0.1:29170).
    jobs['/System/Library/LaunchDaemons/com.ios26hot.hotstored.plist'] = {
        'EnvironmentVariables': {'PATH': '/var/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
        'KeepAlive': True,
        'Label': 'com.ios26hot.hotstored',
        'ProcessType': 'Interactive',
        'ProgramArguments': ['/var/local/bin/hotstored'],
        'RunAtLoad': True,
        'StandardErrorPath': '/var/local/hotstored.log',
        'StandardOutPath': '/var/local/hotstored.log',
        'UserName': 'root',
    }

    # Ensure hotauthd Apple Account / iCloud helper is registered for HotAuth.app & akd (127.0.0.1:29171).
    jobs['/System/Library/LaunchDaemons/com.ios26hot.hotauthd.plist'] = {
        'EnvironmentVariables': {'PATH': '/var/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
        'KeepAlive': True,
        'Label': 'com.ios26hot.hotauthd',
        'ProcessType': 'Interactive',
        'ProgramArguments': ['/var/local/bin/hotauthd'],
        'RunAtLoad': True,
        'StandardErrorPath': '/var/local/hotauth.log',
        'StandardOutPath': '/var/local/hotauth.log',
        'UserName': 'root',
    }

    idle_svcs = []
    if has_idle_xpc:
        idle_svcs = tweaks.ensure_idle_xpc_daemon(jobs)

    if args.enable_cellular:
        jobs['/System/Library/LaunchDaemons/org.iosonpixel.cellulard.plist'] = {
            'EnvironmentVariables': {'PATH': '/var/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
            'KeepAlive': True,
            'Label': 'org.iosonpixel.cellulard',
            'MachServices': {
                'com.apple.commcenter.coretelephony.xpc': True,
                'com.apple.commcenter.xpc': True,
            },
            'ProcessType': 'Interactive',
            'ProgramArguments': ['/var/local/bin/cellulard'],
            'RunAtLoad': True,
            'StandardErrorPath': '/var/local/cellulard.log',
            'StandardOutPath': '/var/local/cellulard.log',
            'UserName': 'root',
        }

    if args.enable_adsp:
        jobs['/System/Library/LaunchDaemons/org.iosonpixel.adspd.plist'] = {
            'EnvironmentVariables': {'PATH': '/var/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
            'KeepAlive': True,
            'Label': 'org.iosonpixel.adspd',
            'LegacyTimers': True,
            'LowPriorityIO': False,
            'Nice': -10,
            'ProcessType': 'Interactive',
            'ProgramArguments': ['/var/local/bin/adspd', 'auto'],
            'RunAtLoad': True,
            'StandardErrorPath': '/var/local/adspd.log',
            'StandardOutPath': '/var/local/adspd.log',
            'UserName': 'root',
        }

    if args.selftest_bin or args.selftest_script:
        prog_args = (['/private/var/local/bin/sweet-selftest'] if args.selftest_bin
                     else ['/private/var/local/bin/sh', '/private/var/local/bin/sweet-selftest.sh'])
        jobs['/System/Library/LaunchDaemons/org.iosonpixel.selftest.plist'] = {
            'EnvironmentVariables': {'PATH': '/private/var/local/bin:/var/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
            'KeepAlive': False,
            'Label': 'org.iosonpixel.selftest',
            'ProcessType': 'Interactive',
            'ProgramArguments': prog_args,
            'RunAtLoad': True,
            'StandardErrorPath': '/var/local/selftest.log',
            'StandardOutPath': '/var/local/selftest.log',
            'UserName': 'root',
        }

    after_count = len(jobs)
    missing_keep = [k for k in KEEP_DAEMON_KEYS if k not in jobs]
    if missing_keep:
        raise RuntimeError(f'refusing to build overlay: required LaunchDaemons missing: {missing_keep}')

    wp_assets = None
    wp_defaults = {}
    if args.wallpaper:
        wp_w, wp_h = _parse_wh(args.wallpaper_size)
        th_w, th_h = max(1, wp_w // 3), max(1, wp_h // 3)
        with open(args.wallpaper, 'rb') as f:
            src_png = f.read()
        src_w, src_h, src_rgb = _decode_png_rgb(src_png)
        full_rgb = _resize_rgb_cover(src_rgb, src_w, src_h, wp_w, wp_h)
        thumb_rgb = _resize_rgb_cover(src_rgb, src_w, src_h, th_w, th_h)
        full_png = _encode_png_rgb(full_rgb, wp_w, wp_h)
        thumb_png = _encode_png_rgb(thumb_rgb, th_w, th_h)
        full_cpb = _encode_cpbitmap_png(full_png, wp_w, wp_h)
        thumb_cpb = _encode_cpbitmap_png(thumb_png, th_w, th_h)
        wp_bundle_plist = plistlib.dumps({
            'appearanceAware': False,
            'assets': {
                'lockAndHome': {
                    'default': {
                        'disableParallax': True,
                        'fullSizeImageFileName': 'Wallpaper.png',
                        'identifier': 7565,
                        'name': 'Default',
                        'thumbnailImageFileName': 'Thumbnail.png',
                        'type': 'Still',
                    },
                },
            },
            'disableParallax': True,
            'family': 'iOS 17',
            'fullSizeImageFileName': 'Wallpaper.png',
            'identifier': 7565,
            'logicalScreenClass': '375w-812h@3x~iphone',
            'name': 'Default',
            'thumbnailImageFileName': 'Thumbnail.png',
            'type': 'Still',
            'version': 1,
        }, fmt=plistlib.FMT_BINARY)
        wp_assets = (wp_w, wp_h, th_w, th_h, full_png, thumb_png, full_cpb, thumb_cpb, wp_bundle_plist)
        wp_defaults = {
            'SBWallpaperHasVideoKey': False,
            'SBWallpaperMagnifyHomeScreenKey': False,
            'SBWallpaperMagnifyLockScreenKey': False,
            'SBWallpaperParallaxFactorHomeScreen': 0.0,
            'SBWallpaperParallaxFactorLockScreen': 0.0,
            'SBWallpaperPortraitHomeScreenKey': True,
            'SBWallpaperPortraitLockScreenKey': True,
            'SBWallpaperZoomScaleHomeScreen': 1.0,
            'SBWallpaperZoomScaleLockScreen': 1.0,
            'kSBProceduralWallpaperHomeUserSetKey': False,
            'kSBProceduralWallpaperLockUserSetKey': False,
        }
        print(f'wallpaper: {src_w}x{src_h} -> {wp_w}x{wp_h} PNG ({len(full_png)} B) + '
              f'{th_w}x{th_h} thumb ({len(thumb_png)} B) + CPBitmaps')

    cleaned_bytes = plistlib.dumps(plist, fmt=plistlib.FMT_BINARY)
    major_ver = int(prod_ver.split('.')[0]) if prod_ver and prod_ver.split('.')[0].isdigit() else 26
    setup_ver = 15 if major_ver >= 26 else 11
    mig_bytes = plistlib.dumps({
        'LastSystemVersion': build_ver,
        'DMUserDataDisposition': {'SyntheticDidUpgrade': False, 'buildVersion': build_ver},
    }, fmt=plistlib.FMT_BINARY)
    sb_dict = {
        'BKNoWatchdogs': True,
        'FBLaunchWatchdogExceptions': {'com.apple.purplebuddy': 100.0},
        'MCForceShowCloudConfigurationUI': False,
    }
    sb_dict.update(wp_defaults)
    sb_bytes = plistlib.dumps(sb_dict, fmt=plistlib.FMT_BINARY)
    buddy_bytes = plistlib.dumps({
        'ForceNoBuddy': True,
        'LastSystemVersion': build_ver,
        'SetupDone': True,
        'SetupFinishedAllSteps': True,
        'SetupVersion': setup_ver,
        'daemonizeNeedsToRun': False,
    }, fmt=plistlib.FMT_BINARY)
    buddy_nb_bytes = plistlib.dumps({
        k: False for k in (
            'ShouldRunDiagnosticsMiniBuddy',
            'ShouldRunHSA2MiniBuddy',
            'ShouldRunKeychainSyncMiniBuddy',
            'ShouldRunMesaMiniBuddy',
            'ShouldRunPaymentMiniBuddy',
            'ShouldRunStandardMiniBuddy',
            'SilentICDPUpgrade',
        )
    }, fmt=plistlib.FMT_BINARY)
    mc_nb_bytes = plistlib.dumps({
        'LockdownActivationIndicatesCloudConfigurationAvailable': False,
    }, fmt=plistlib.FMT_BINARY)
    tu_prefs_bytes = plistlib.dumps({
        'CallScreeningDisabled': True,
        'CallScreeningEnabled': False,
        'LiveVoicemailEnabled': False,
        'SilenceUnknownCallersEnabled': False,
        'SilenceUnknownFaceTimeCallersEnabled': False,
    }, fmt=plistlib.FMT_BINARY)
    bb_prefs = {
        'CAEnableDeepFramebuffer': False,
        'CAEnableFramebufferCompression': False,
        'CAEnableIOSurfaceCompression': False,
    }
    bb_prefs_bytes = plistlib.dumps(bb_prefs, fmt=plistlib.FMT_BINARY)
    mobile_global_prefs.update(bb_prefs)
    sys_global_prefs.update(bb_prefs)
    # Disable iOS 26 AI Call Screening ("Receptionist" / LiveVoicemail) before launchd builds
    # com.apple.featureflags.shm so callservicesd synchronously creates CSDProviderCall and launches
    # InCallService for all incoming calls instead of holding unknown callers on stripped ANE/SiriTTS daemons.
    tu_ff_override = {
        'CallScreeningEnabled': {'Enabled': False},
        'CallScreeningEnabledM3': {'Enabled': False},
        'CallScreeningRTTEnabled': {'Enabled': False},
        'LVMEverywhere': {'Enabled': False},
        'LVMGeneralASRMigration': {'Enabled': False},
        'ReceptionistEnabled': {'Enabled': False},
        'ReceptionistEnabledMac': {'Enabled': False},
        'ReceptionistEnabledPad': {'Enabled': False},
        'lvmExpansionLiveOnEnabled': {'Enabled': False},
    }
    tu_ff_domain = dict(tu_ff_plist) if tu_ff_plist is not None else {}
    tu_ff_domain.update(tu_ff_override)

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with tarfile.open(args.output, 'w', format=tarfile.USTAR_FORMAT) as tf:
        add_ustar_bytes(tf, 'System/Library/xpc/launchd.plist', cleaned_bytes, mode=mode, uid=uid, gid=gid)
        if jetsam_bytes is not None:
            add_ustar_bytes(tf, 'System/Library/LaunchDaemons/com.apple.jetsamproperties.D421.plist',
                            jetsam_bytes, mode=j_mode, uid=j_uid, gid=j_gid)
        add_ustar_dir(tf, 'System/Library/FeatureFlags', mode=0o755, uid=0, gid=0)
        add_ustar_dir(tf, 'System/Library/FeatureFlags/Domain', mode=0o755, uid=0, gid=0)
        add_ustar_bytes(tf, 'System/Library/FeatureFlags/Domain/TelephonyUtilities.plist',
                        plistlib.dumps(tu_ff_domain, fmt=plistlib.FMT_BINARY), mode=0o644, uid=0, gid=0)
        add_ustar_dir(tf, 'private/var/preferences', mode=0o755, uid=0, gid=0)
        add_ustar_dir(tf, 'private/var/preferences/FeatureFlags', mode=0o755, uid=0, gid=0)
        add_ustar_dir(tf, 'private/var/preferences/FeatureFlags/Domain', mode=0o755, uid=0, gid=0)
        add_ustar_bytes(tf, 'private/var/preferences/FeatureFlags/Domain/TelephonyUtilities.plist',
                        plistlib.dumps(tu_ff_override, fmt=plistlib.FMT_BINARY), mode=0o644, uid=0, gid=0)

        add_ustar_dir(tf, 'private/var/mobile/Library', mode=0o755, uid=MOBILE_UID, gid=MOBILE_UID)
        add_ustar_dir(tf, 'private/var/mobile/Library/Preferences', mode=0o755, uid=MOBILE_UID, gid=MOBILE_UID)
        for pref_name, pref_data in (
            ('com.apple.migration.plist', mig_bytes),
            ('com.apple.springboard.plist', sb_bytes),
            ('com.apple.backboardd.plist', bb_prefs_bytes),
            ('com.apple.purplebuddy.plist', buddy_bytes),
            ('com.apple.purplebuddy.notbackedup.plist', buddy_nb_bytes),
            ('com.apple.managedconfiguration.notbackedup.plist', mc_nb_bytes),
            ('com.apple.TelephonyUtilities.plist', tu_prefs_bytes),
        ):
            add_ustar_bytes(tf, f'private/var/mobile/Library/Preferences/{pref_name}',
                            pref_data, mode=0o600, uid=MOBILE_UID, gid=MOBILE_UID)
        add_ustar_dir(tf, 'private/var/root', mode=0o750, uid=0, gid=0)
        add_ustar_dir(tf, 'private/var/root/Library', mode=0o750, uid=0, gid=0)
        add_ustar_dir(tf, 'private/var/root/Library/Preferences', mode=0o750, uid=0, gid=0)
        add_ustar_bytes(tf, 'private/var/root/Library/Preferences/com.apple.backboardd.plist',
                        bb_prefs_bytes, mode=0o600, uid=0, gid=0)
        add_ustar_bytes(tf, 'private/var/preferences/com.apple.backboardd.plist',
                        bb_prefs_bytes, mode=0o644, uid=0, gid=0)

        if tz_name:
            add_ustar_dir(tf, 'private/var/db/timezone', mode=0o755, uid=0, gid=0)
            add_ustar_symlink(tf, 'private/var/db/timezone/zoneinfo', '/usr/share/zoneinfo.default')
            # Point localtime through /var/db/timezone/zoneinfo/<TZ> so both libc stat/open
            # and Foundation's readlink("/var/db/timezone/localtime") prefix check resolve <TZ>.
            add_ustar_symlink(tf, 'private/var/db/timezone/localtime', f'/var/db/timezone/zoneinfo/{tz_name}')

            timed_bytes = plistlib.dumps({
                'TMAutomaticTimeEnabled': True,
                'TMAutomaticTimeOnlyEnabled': True,
                'TMAutomaticTimeZoneEnabled': False,
                'TMTimeZone': tz_name,
                'TMTimeZoneConfirmed': True,
            }, fmt=plistlib.FMT_BINARY)
            dt_bytes = plistlib.dumps({
                'AutomaticTimeZoneEnabled': False,
                'timezone': tz_name,
                'timezoneset': True,
            }, fmt=plistlib.FMT_BINARY)

            for tdir in (
                'private/var/db/timed',
                'private/var/db/timed/Library',
                'private/var/db/timed/Library/Preferences',
            ):
                add_ustar_dir(tf, tdir, mode=0o750, uid=TIMED_UID, gid=TIMED_UID)
            add_ustar_bytes(tf, 'private/var/db/timed/com.apple.timed.plist',
                            timed_bytes, mode=0o600, uid=TIMED_UID, gid=TIMED_UID)
            add_ustar_bytes(tf, 'private/var/db/timed/Library/Preferences/com.apple.timed.plist',
                            timed_bytes, mode=0o600, uid=TIMED_UID, gid=TIMED_UID)
            add_ustar_bytes(tf, 'private/var/db/timed/Library/Preferences/com.apple.preferences.datetime.plist',
                            dt_bytes, mode=0o600, uid=TIMED_UID, gid=TIMED_UID)
            add_ustar_bytes(tf, 'private/var/mobile/Library/Preferences/com.apple.timed.plist',
                            timed_bytes, mode=0o600, uid=MOBILE_UID, gid=MOBILE_UID)
            add_ustar_bytes(tf, 'private/var/mobile/Library/Preferences/com.apple.preferences.datetime.plist',
                            dt_bytes, mode=0o600, uid=MOBILE_UID, gid=MOBILE_UID)

            mobile_global_prefs.update({
                'AutomaticTimeZoneEnabled': False,
                'TimeZone': tz_name,
            })
            sys_global_prefs.update({
                'AutomaticTimeZoneEnabled': False,
                'TimeZone': tz_name,
            })

        add_ustar_bytes(tf, 'private/var/mobile/Library/Preferences/.GlobalPreferences.plist',
                        plistlib.dumps(mobile_global_prefs, fmt=plistlib.FMT_BINARY),
                        mode=0o644, uid=MOBILE_UID, gid=MOBILE_UID)
        add_ustar_bytes(tf, 'private/var/preferences/.GlobalPreferences.plist',
                        plistlib.dumps(sys_global_prefs, fmt=plistlib.FMT_BINARY),
                        mode=0o644, uid=0, gid=0)

        if wp_assets is not None:
            _, _, _, _, full_png, thumb_png, full_cpb, thumb_cpb, wp_bundle_plist = wp_assets
            wp_bundle_dir = (
                'Library/Wallpaper/Collections/iOS_17~iphone.wallpaperCollection/'
                'Wallpapers/7565.iOS_17-375w-812h@3x~iphone.wallpaper'
            )
            add_ustar_dir(tf, wp_bundle_dir, mode=0o755, uid=0, gid=0)
            add_ustar_bytes(tf, f'{wp_bundle_dir}/Wallpaper.png', full_png, mode=0o644, uid=0, gid=0)
            add_ustar_bytes(tf, f'{wp_bundle_dir}/Thumbnail.png', thumb_png, mode=0o644, uid=0, gid=0)
            add_ustar_bytes(tf, f'{wp_bundle_dir}/Wallpaper.plist', wp_bundle_plist, mode=0o644, uid=0, gid=0)

            # Disable SpringBoard's Autobahn feature flag before launchd builds com.apple.featureflags.shm
            # so SBWallpaperController renders Lock & Home backdrops in-process via PaperBoardUI + SweetMetal2
            # instead of waiting on PosterBoard ExtensionKit scene hosting.
            autobahn_override = {
                'Autobahn': {'Enabled': False},
                'AutobahnLegacyMigration': {'Enabled': False},
                'AutobahnLegacyMigrationSupportingDistinctHome': {'Enabled': False},
            }
            ff_domain = dict(sb_ff_plist) if sb_ff_plist is not None else {}
            ff_domain.update(autobahn_override)
            add_ustar_bytes(tf, 'System/Library/FeatureFlags/Domain/SpringBoard.plist',
                            plistlib.dumps(ff_domain, fmt=plistlib.FMT_BINARY), mode=0o644, uid=0, gid=0)
            add_ustar_bytes(tf, 'private/var/preferences/FeatureFlags/Domain/SpringBoard.plist',
                            plistlib.dumps(autobahn_override, fmt=plistlib.FMT_BINARY), mode=0o644, uid=0, gid=0)

            add_ustar_dir(tf, 'private/var/mobile/Library/SpringBoard', mode=0o755, uid=MOBILE_UID, gid=MOBILE_UID)
            for cpb_name, cpb_data in (
                ('LockBackground.cpbitmap', full_cpb),
                ('HomeBackground.cpbitmap', full_cpb),
                ('LockBackgroundThumbnail.cpbitmap', thumb_cpb),
                ('HomeBackgroundThumbnail.cpbitmap', thumb_cpb),
                ('LockBackgroundThumbnail.jpg', thumb_png),
                ('HomeBackgroundThumbnail.jpg', thumb_png),
            ):
                add_ustar_bytes(tf, f'private/var/mobile/Library/SpringBoard/{cpb_name}',
                                cpb_data, mode=0o644, uid=MOBILE_UID, gid=MOBILE_UID)
            add_ustar_bytes(tf, 'private/var/mobile/Library/Preferences/com.apple.PaperBoard.plist',
                            plistlib.dumps(wp_defaults, fmt=plistlib.FMT_BINARY),
                            mode=0o600, uid=MOBILE_UID, gid=MOBILE_UID)
        # Ensure all MCM system & user container class base directories exist with right owners
        for cdir, cuid in (
            ('private/var/containers', 0),
            ('private/var/containers/Bundle', 33),
            ('private/var/containers/Data', 0),
            ('private/var/containers/Data/System', 0),
            ('private/var/containers/Shared', 0),
            ('private/var/containers/Shared/SystemGroup', 0),
            ('private/var/containers/Temp', 0),
            ('private/var/mobile/Containers', 501),
            ('private/var/mobile/Containers/Bundle', 501),
            ('private/var/mobile/Containers/Data', 501),
            ('private/var/mobile/Containers/Data/Application', 501),
            ('private/var/mobile/Containers/Data/PluginKitPlugin', 501),
            ('private/var/mobile/Containers/Data/InternalDaemon', 501),
            ('private/var/mobile/Containers/Data/XPCServices', 501),
            ('private/var/mobile/Containers/Data/Temp', 501),
            ('private/var/mobile/Containers/Shared', 501),
            ('private/var/mobile/Containers/Shared/AppGroup', 501),
        ):
            add_ustar_dir(tf, cdir, mode=0o755, uid=cuid, gid=cuid)

        # Ensure pre-seeded installcoordinationd SystemGroup container is writable by mobile (501)
        for sgdir in (
            'private/var/containers/Shared/SystemGroup/systemgroup.com.apple.installcoordinationd',
            'private/var/containers/Shared/SystemGroup/systemgroup.com.apple.installcoordinationd/Library',
            'private/var/containers/Shared/SystemGroup/systemgroup.com.apple.installcoordinationd/Library/InstallCoordination',
        ):
            add_ustar_dir(tf, sgdir, mode=0o777, uid=501, gid=501)

        if args.dsc_stubs_dir:
            stub_files = sorted(fn for fn in os.listdir(args.dsc_stubs_dir)
                                if fn.startswith('dyld_shared_cache_arm64e.'))
            if not stub_files:
                raise RuntimeError(f'no dyld_shared_cache_arm64e.* files in {args.dsc_stubs_dir}')
            for fn in stub_files:
                src = os.path.join(args.dsc_stubs_dir, fn)
                sys_dst = f'System/Library/Caches/com.apple.dyld/{fn}'
                crypt_dst = f'private/preboot/Cryptexes/OS/System/Library/Caches/com.apple.dyld/{fn}'
                with open(src, 'rb') as f:
                    add_ustar_bytes(tf, sys_dst, f.read(), mode=0o644)
                add_ustar_symlink(tf, crypt_dst, '/' + sys_dst)

        if args.hotmetal2:
            for req in ('HotMetal2', 'libvulkan_freedreno.dylib', 'Info.plist'):
                p = os.path.join(args.hotmetal2, req)
                if not os.path.isfile(p):
                    raise FileNotFoundError(f'incomplete HotMetal2.bundle, missing {p}')
            add_tree(tf, args.hotmetal2, 'System/Library/Extensions/HotMetal2.bundle',
                     force_exec_names=('HotMetal2', 'libvulkan_freedreno.dylib'))

        if args.userland_dir:
            app_dir = os.path.join(args.userland_dir, 'Applications', 'HotStore.app')
            if os.path.isdir(app_dir):
                add_tree(tf, app_dir, 'Applications/HotStore.app',
                         force_exec_names=('HotStore', 'HotWebAppStub'))
            auth_app_dir = os.path.join(args.userland_dir, 'Applications', 'HotAuth.app')
            if os.path.isdir(auth_app_dir):
                add_tree(tf, auth_app_dir, 'Applications/HotAuth.app',
                         force_exec_names=('HotAuth',))
            hal_dir = os.path.join(args.userland_dir, 'HAL', 'HotAudio.driver')
            if os.path.isdir(hal_dir):
                add_ustar_dir(tf, 'System/Library/Audio/Plug-Ins/HAL', 0o755)
                add_tree(tf, hal_dir, 'System/Library/Audio/Plug-Ins/HAL/HotAudio.driver',
                         force_exec_names=('HotAudio',))
                if va_disabled_files:
                    va_dst = 'Library/Audio/Plug-Ins/HAL/VirtualAudio.plugin'
                    add_ustar_dir(tf, 'Library/Audio/Plug-Ins/HAL', 0o755)
                    add_ustar_dir(tf, va_dst, 0o755)
                    for vfn, vdata, vmode, vuid, vgid in va_disabled_files:
                        add_ustar_bytes(tf, f'{va_dst}/{vfn}', vdata, mode=vmode, uid=vuid, gid=vgid)
                    for sl_name in ('VirtualAudio', '_CodeSignature', 'en.lproj'):
                        add_ustar_symlink(tf, f'{va_dst}/{sl_name}',
                                          f'../../HAL.disabled/VirtualAudio.plugin/{sl_name}')
            lib_dir = os.path.join(args.userland_dir, 'lib')
            if os.path.isdir(lib_dir):
                add_ustar_dir(tf, 'usr/local', 0o755)
                add_ustar_dir(tf, 'usr/local/lib', 0o755)
                for fn in sorted(os.listdir(lib_dir)):
                    p = os.path.join(lib_dir, fn)
                    if os.path.isfile(p):
                        with open(p, 'rb') as f:
                            add_ustar_bytes(tf, f'usr/local/lib/{fn}', f.read(), mode=0o755)
            bin_dir = os.path.join(args.userland_dir, 'bin')
            if os.path.isdir(bin_dir):
                add_ustar_dir(tf, 'private/var/local/bin', 0o755)
                for fn in sorted(os.listdir(bin_dir)):
                    if fn in SKIP_USERLAND_BINS:
                        continue
                    p = os.path.join(bin_dir, fn)
                    if os.path.isfile(p):
                        with open(p, 'rb') as f:
                            add_ustar_bytes(tf, f'private/var/local/bin/{fn}', f.read(), mode=0o755)
                if os.path.isfile(os.path.join(bin_dir, 'dropbearmulti')):
                    for link in ('dropbear', 'dbclient', 'dropbearkey', 'scp'):
                        add_ustar_symlink(tf, f'private/var/local/bin/{link}', 'dropbearmulti')
                for legacy_bin, hot_bin in (
                    ('sweetdiag', 'hotdiag'),
                    ('sweet-bootprep', 'hot-bootprep'),
                    ('sweet-idle-xpc', 'hot-idle-xpc'),
                    ('sweet-audiotest', 'hot-audiotest'),
                ):
                    if os.path.isfile(os.path.join(bin_dir, hot_bin)):
                        add_ustar_symlink(tf, f'private/var/local/bin/{legacy_bin}', hot_bin)
                idle_xpc_path = os.path.join(bin_dir, 'hot-idle-xpc')
                if not os.path.isfile(idle_xpc_path):
                    idle_xpc_path = os.path.join(bin_dir, 'sweet-idle-xpc')
                if os.path.isfile(idle_xpc_path):
                    with open(idle_xpc_path, 'rb') as f:
                        idle_xpc_bytes = f.read()
                    for bd_bin in (
                        'System/Library/PrivateFrameworks/MessagesBlastDoorSupport.framework/XPCServices/MessagesBlastDoorService.xpc/MessagesBlastDoorService',
                        'System/Library/PrivateFrameworks/IDSBlastDoorSupport.framework/XPCServices/IDSBlastDoorService.xpc/IDSBlastDoorService',
                    ):
                        add_ustar_bytes(tf, bd_bin, idle_xpc_bytes, mode=0o755)

        for bd_rel, bd_bytes, bd_mode, bd_uid, bd_gid in blastdoor_plists:
            add_ustar_bytes(tf, bd_rel, bd_bytes, mode=bd_mode, uid=bd_uid, gid=bd_gid)

        if args.mtl2_clients is not None:
            add_ustar_bytes(tf, 'private/var/local/mtl2-clients',
                            (args.mtl2_clients.strip() + '\n').encode(), mode=0o644)
        add_ustar_bytes(
            tf,
            'private/var/local/mtl2-primelist',
            b'/System/Library/Frameworks/QuartzCore.framework/default.metallib\n'
            b'/System/Library/PrivateFrameworks/RenderBox.framework/default.metallib\n'
            b'/System/Library/PrivateFrameworks/CoreUI.framework/default.metallib\n'
            b'/System/Library/PrivateFrameworks/MediaCoreUI.framework/default.metallib\n',
            mode=0o644,
        )
        add_ustar_dir(tf, 'private/var/Keychains', mode=0o775, uid=64, gid=64)
        add_ustar_dir(tf, 'private/var/Keychains/Analytics', mode=0o775, uid=64, gid=64)
        add_ustar_symlink(tf, 'private/var/keychains', 'Keychains')
        add_ustar_dir(tf, 'private/var/mobile/Library/Keychains', mode=0o775, uid=MOBILE_UID, gid=64)
        add_ustar_dir(tf, 'private/var/protected', mode=0o755, uid=0, gid=0)
        add_ustar_dir(tf, 'private/var/protected/sfanalytics', mode=0o775, uid=64, gid=64)
        add_ustar_dir(tf, 'private/var/local/ipa', mode=0o777, uid=MOBILE_UID, gid=MOBILE_UID)
        add_ustar_dir(tf, 'private/var/local/hotauth', mode=0o700, uid=0, gid=0)
        add_ustar_bytes(tf, 'private/var/local/mtl2.log', b'', mode=0o666)
        add_ustar_bytes(tf, 'private/var/local/appfix.log', b'', mode=0o666)
        add_ustar_bytes(tf, 'private/var/local/hotauth.log', b'', mode=0o666)
        add_ustar_bytes(
            tf,
            'private/var/local/inject.conf',
            b'/usr/local/lib/libhot_media.dylib mediaserverd\n'
            b'/usr/local/lib/libhot_sysfix.dylib containermanagerd\n'
            b'/usr/local/lib/libhot_sysfix.dylib containermanagerd_system\n'
            b'/usr/local/lib/libhot_sysfix.dylib backboardd\n'
            b'/usr/local/lib/libhot_sysfix.dylib SpringBoard\n'
            b'/usr/local/lib/libhot_sysfix.dylib akd\n'
            b'/usr/local/lib/libhot_sysfix.dylib cdpd\n'
            b'/usr/local/lib/libhot_appfix.dylib @userapps\n',
            mode=0o644,
        )

        rm_paths = [
            'Library/Audio/Plug-Ins/HAL/HakiosAudio.driver/Info.plist',
            'Library/Audio/Plug-Ins/HAL/HakiosAudio.driver/HakiosAudio',
            'System/Library/Audio/Plug-Ins/HAL/HakiosAudio.driver/Info.plist',
            'System/Library/Audio/Plug-Ins/HAL/HakiosAudio.driver/HakiosAudio',
            'System/Library/Extensions/SweetMetal2.bundle/Info.plist',
            'System/Library/Extensions/SweetMetal2.bundle/SweetMetal2',
            'Applications/SweetStore.app/Info.plist',
            'Applications/SweetStore.app/SweetStore',
            'Applications/SweetAuth.app/Info.plist',
            'Applications/SweetAuth.app/SweetAuth',
        ]
        if wp_assets is not None:
            rm_paths.extend([
                'private/var/mobile/Library/SpringBoard/OriginalLockBackground.cpbitmap',
                'private/var/mobile/Library/SpringBoard/OriginalHomeBackground.cpbitmap',
            ])
        if args.selftest_bin:
            with open(args.selftest_bin, 'rb') as f:
                add_ustar_bytes(tf, 'private/var/local/bin/sweet-selftest', f.read(), mode=0o755)
            add_ustar_bytes(tf, 'private/var/local/selftest.log', b'', mode=0o666)
            add_ustar_bytes(tf, 'private/var/local/mcm.log', b'', mode=0o666)
        elif args.selftest_script:
            with open(args.selftest_script, 'rb') as f:
                add_ustar_bytes(tf, 'private/var/local/bin/sweet-selftest.sh',
                                f.read().replace(b'\r\n', b'\n'), mode=0o755)
            add_ustar_bytes(tf, 'private/var/local/selftest.log', b'', mode=0o666)
            add_ustar_bytes(tf, 'private/var/local/mcm.log', b'', mode=0o666)
        else:
            rm_paths.extend([
                'private/var/local/bin/sweet-selftest',
                'private/var/local/bin/sweet-selftest.sh',
                'private/var/local/selftest.log',
                'private/var/local/mcm.log',
            ])
        if rm_paths:
            add_ustar_bytes(tf, '.sweet-rm', ('\n'.join(rm_paths) + '\n').encode('ascii'), mode=0o644)

        for spec in args.extra:
            parts = spec.split(':')
            if len(parts) not in (2, 3):
                raise ValueError(f'invalid --extra {spec!r}, expected SRC:DST[:OCTAL_MODE]')
            src, dst = parts[0], parts[1]
            fmode = int(parts[2], 8) if len(parts) == 3 else (0o755 if (os.stat(src).st_mode & 0o111) else 0o644)
            with open(src, 'rb') as f:
                add_ustar_bytes(tf, dst, f.read(), mode=fmode)

    print(f'launchd.plist: {before_count} -> {after_count} LaunchDaemons '
          f'({renamed} _PanicOnCrash renamed, {len(removed)} stripped)')
    if idle_svcs:
        print(f'idlexpc claimed MachServices: {idle_svcs}')
    if tz_name:
        print(f'timezone staged: {tz_name}')
    print(f'kept intact: {[os.path.basename(k) for k in KEEP_DAEMON_KEYS]}')
    print(f'wrote {args.output} ({os.path.getsize(args.output)} bytes)')


if __name__ == '__main__':
    main()
