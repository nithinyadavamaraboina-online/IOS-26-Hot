package com.realme.samurai.helper;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Root-side DEX/APK/Seed/SQLite helper executed via:
 *   CLASSPATH=/system/framework/samurai-mod-helper.jar /system/bin/app_process64 /system/bin \
 *     com.realme.samurai.helper.SamuraiModHelper <subcommand> [args...]
 *
 * Subcommands:
 *   1. unpack-seed <seed.gz> <adblock_dir>
 *   2. merge-dex <stock_apk> <dex_zip> <out_apk>
 *   3. patch-meta <in_base_apk> <out_patched_apk> <pkg_name>
 *   4. detach-playstore <pkg1> [pkg2 ...]
 */
public final class SamuraiModHelper {

    private static final int ZIPALIGN_EXTRA_HEADER_ID = 0xD935;

    // Dalvik opcode widths in 16-bit code units for opcodes 0x00..0xFF (0 = unused/invalid)
    private static final int[] INSN_WIDTH = new int[256];
    static {
        // 0x00..0x0d: 10x, 12x, 22x, 32x, 12x, 22x, 32x, 12x, 22x, 32x, 11x..11x
        setWidths(0x00, 1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1);
        // 0x0e..0x11: return-void, return, return-wide, return-object
        setWidths(0x0e, 1, 1, 1, 1);
        // 0x12..0x1c: const/4, const/16, const, const/high16, const-wide/16, const-wide/32,
        //             const-wide, const-wide/high16, const-string, const-string/jumbo, const-class
        setWidths(0x12, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2);
        // 0x1d..0x27: monitor-*, check-cast, instance-of, array-length, new-instance, new-array,
        //             filled-new-array, filled-new-array/range, fill-array-data, throw
        setWidths(0x1d, 1, 1, 2, 2, 1, 2, 2, 3, 3, 3, 1);
        // 0x28..0x2c: goto, goto/16, goto/32, packed-switch, sparse-switch
        setWidths(0x28, 1, 2, 3, 3, 3);
        // 0x2d..0x31: cmpl/cmpg/cmp-long (23x)
        setWidths(0x2d, 2, 2, 2, 2, 2);
        // 0x32..0x37: if-* (22t), 0x38..0x3d: if-*z (21t)
        setWidths(0x32, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        // 0x3e..0x43: unused
        // 0x44..0x51: aget*/aput* (23x = 2)
        for (int op = 0x44; op <= 0x51; op++) INSN_WIDTH[op] = 2;
        // 0x52..0x5f: iget*/iput* (22c = 2)
        for (int op = 0x52; op <= 0x5f; op++) INSN_WIDTH[op] = 2;
        // 0x60..0x6d: sget*/sput* (21c = 2)
        for (int op = 0x60; op <= 0x6d; op++) INSN_WIDTH[op] = 2;
        // 0x6e..0x72: invoke-* (35c = 3)
        for (int op = 0x6e; op <= 0x72; op++) INSN_WIDTH[op] = 3;
        // 0x73: unused
        // 0x74..0x78: invoke-*/range (3rc = 3)
        for (int op = 0x74; op <= 0x78; op++) INSN_WIDTH[op] = 3;
        // 0x79..0x7a: unused
        // 0x7b..0x8f: unop (12x = 1)
        for (int op = 0x7b; op <= 0x8f; op++) INSN_WIDTH[op] = 1;
        // 0x90..0xaf: binop (23x = 2)
        for (int op = 0x90; op <= 0xaf; op++) INSN_WIDTH[op] = 2;
        // 0xb0..0xcf: binop/2addr (12x = 1)
        for (int op = 0xb0; op <= 0xcf; op++) INSN_WIDTH[op] = 1;
        // 0xd0..0xd7: binop/lit16 (22s = 2)
        for (int op = 0xd0; op <= 0xd7; op++) INSN_WIDTH[op] = 2;
        // 0xd8..0xe2: binop/lit8 (22b = 2)
        for (int op = 0xd8; op <= 0xe2; op++) INSN_WIDTH[op] = 2;
        // 0xfa..0xfd: invoke-polymorphic (45cc=4), invoke-polymorphic/range (4rcc=4),
        //             invoke-custom (35c=3), invoke-custom/range (3rc=3)
        INSN_WIDTH[0xfa] = 4;
        INSN_WIDTH[0xfb] = 4;
        INSN_WIDTH[0xfc] = 3;
        INSN_WIDTH[0xfd] = 3;
        // 0xfe..0xff: const-method-handle (21c=2), const-method-type (21c=2)
        INSN_WIDTH[0xfe] = 2;
        INSN_WIDTH[0xff] = 2;
    }

    private static void setWidths(int startOp, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            INSN_WIDTH[startOp + i] = widths[i];
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count = 0;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: SamuraiModHelper <unpack-seed|merge-dex|patch-meta|detach-playstore> ...");
            System.exit(2);
        }
        try {
            switch (args[0]) {
                case "unpack-seed":
                    if (args.length < 3) throw new IllegalArgumentException("unpack-seed <seed.gz> <adblock_dir>");
                    unpackSeed(new File(args[1]), new File(args[2]));
                    break;
                case "merge-dex":
                    if (args.length < 4) throw new IllegalArgumentException("merge-dex <stock_apk> <dex_zip> <out_apk>");
                    mergeDex(new File(args[1]), new File(args[2]), new File(args[3]));
                    break;
                case "patch-meta":
                    if (args.length < 4) throw new IllegalArgumentException("patch-meta <in_apk> <out_apk> <pkg>");
                    int patched = patchMetaApk(new File(args[1]), new File(args[2]), args[3]);
                    if (patched <= 0) {
                        System.err.println("[SamuraiModHelper] No matching ad hooks found in " + args[3]);
                        System.exit(3);
                    }
                    System.out.println("[SamuraiModHelper] Patched " + patched + " ad hooks in " + args[3]);
                    break;
                case "detach-playstore":
                    if (args.length < 2) throw new IllegalArgumentException("detach-playstore <pkg...>");
                    List<String> pkgs = new ArrayList<>();
                    for (int i = 1; i < args.length; i++) pkgs.add(args[i]);
                    detachPlayStore(pkgs);
                    break;
                case "extract-zip":
                    if (args.length < 3) throw new IllegalArgumentException("extract-zip <zip> <dest_dir> [prefix] [strip_dirs]");
                    String pfx = (args.length >= 4) ? args[3] : "";
                    boolean stripDirs = (args.length >= 5) && "1".equals(args[4]);
                    extractZip(new File(args[1]), new File(args[2]), pfx, stripDirs);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown subcommand: " + args[0]);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // =========================================================================
    // 1. unpack-seed <seed.gz> <adblock_dir>
    // =========================================================================
    private static void unpackSeed(File seedGz, File outDir) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Cannot mkdir " + outDir);
        }
        File allowTmp = new File(outDir, "allow.txt.tmp");
        File hostsTmp = new File(outDir, "hosts.tmp");
        File domsTmp = new File(outDir, "domains.txt.tmp");

        int allowCnt = 0, hostsCnt = 0, domCnt = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(seedGz), 65536)),
                StandardCharsets.UTF_8));
             BufferedWriter wAllow = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(allowTmp), StandardCharsets.UTF_8), 32768);
             BufferedWriter wHosts = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(hostsTmp), StandardCharsets.UTF_8), 65536);
             BufferedWriter wDoms = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(domsTmp), StandardCharsets.UTF_8), 65536)) {

            wHosts.write("127.0.0.1 localhost\n::1 ip6-localhost\n");
            int section = 0; // 1=ALLOW, 2=HOSTS, 3=DOMAINS
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if ("@ALLOW".equals(s)) {
                    section = 1;
                    continue;
                } else if ("@HOSTS".equals(s)) {
                    section = 2;
                    continue;
                } else if ("@DOMAINS".equals(s)) {
                    section = 3;
                    continue;
                }
                if (section == 1) {
                    wAllow.write(s);
                    wAllow.write('\n');
                    allowCnt++;
                } else if (section == 2) {
                    wHosts.write("0.0.0.0 ");
                    wHosts.write(s);
                    wHosts.write("\n:: ");
                    wHosts.write(s);
                    wHosts.write('\n');
                    hostsCnt++;
                } else if (section == 3) {
                    wDoms.write(s);
                    wDoms.write('\n');
                    domCnt++;
                } else {
                    // Plain hosts/domains list downloaded directly from HaGeZi update
                    String norm = normalizeLineDomain(s);
                    if (!norm.isEmpty()) {
                        wDoms.write(norm);
                        wDoms.write('\n');
                        domCnt++;
                        if (hostsCnt < 16000) {
                            wHosts.write("0.0.0.0 ");
                            wHosts.write(norm);
                            wHosts.write("\n:: ");
                            wHosts.write(norm);
                            wHosts.write('\n');
                            hostsCnt++;
                        }
                    }
                }
            }
        }

        if (domCnt > 0) {
            finalizeFile(domsTmp, new File(outDir, "domains.txt"));
            finalizeFile(hostsTmp, new File(outDir, "hosts"));
        } else {
            domsTmp.delete();
            hostsTmp.delete();
            throw new IOException("No domains unpacked from " + seedGz);
        }
        if (allowCnt > 0) {
            finalizeFile(allowTmp, new File(outDir, "allow.txt"));
        } else {
            allowTmp.delete();
        }
        System.out.println("[SamuraiModHelper] Unpacked seed: domains=" + domCnt + ", hosts=" + hostsCnt + ", allow=" + allowCnt);
    }

    private static String normalizeLineDomain(String line) {
        int hash = line.indexOf('#');
        if (hash >= 0) line = line.substring(0, hash);
        String s = line.trim().toLowerCase();
        if (s.isEmpty() || s.startsWith("!")) return "";
        if (s.startsWith("0.0.0.0 ") || s.startsWith("127.0.0.1 ") || s.startsWith(":: ")) {
            String[] parts = s.split("\\s+");
            if (parts.length < 2) return "";
            s = parts[1];
        }
        if (s.startsWith("*.")) s = s.substring(2);
        else if (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.indexOf('.') <= 0 || s.length() > 253 || "localhost".equals(s)) return "";
        return s;
    }

    private static void finalizeFile(File tmp, File dst) throws IOException {
        tmp.setReadable(true, false);
        if (dst.exists() && !dst.delete()) {
            throw new IOException("Cannot replace " + dst);
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("Cannot rename " + tmp + " -> " + dst);
        }
        dst.setReadable(true, false);
    }

    // =========================================================================
    // 2. merge-dex <stock_apk> <dex_zip> <out_apk>
    // =========================================================================
    private static void mergeDex(File stockApk, File dexZip, File outApk) throws IOException {
        File parent = outApk.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmpOut = new File(outApk.getAbsolutePath() + ".tmp");

        byte[] copyBuf = new byte[65536];
        try (ZipFile zfStock = new ZipFile(stockApk);
             ZipFile zfDex = new ZipFile(dexZip);
             CountingOutputStream cos = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(tmpOut), 131072));
             ZipOutputStream zos = new ZipOutputStream(cos)) {

            Set<String> seen = new HashSet<>();
            // 1. Copy all non-DEX entries from stock APK preserving STORED 4B/4KB alignment
            Enumeration<? extends ZipEntry> enStock = zfStock.entries();
            while (enStock.hasMoreElements()) {
                ZipEntry ze = enStock.nextElement();
                String name = ze.getName();
                if (isClassesDex(name)) continue;
                seen.add(name);
                copyZipEntryAligned(zfStock, ze, zos, cos, copyBuf);
            }

            // 2. Inject patched classes*.dex from dexZip
            Enumeration<? extends ZipEntry> enDex = zfDex.entries();
            int dexCount = 0;
            while (enDex.hasMoreElements()) {
                ZipEntry ze = enDex.nextElement();
                String name = ze.getName();
                if (!isClassesDex(name) || seen.contains(name)) continue;
                seen.add(name);
                copyZipEntryAligned(zfDex, ze, zos, cos, copyBuf);
                dexCount++;
            }
            if (dexCount == 0) {
                throw new IOException("No classes*.dex found inside " + dexZip);
            }
        }
        finalizeFile(tmpOut, outApk);
        System.out.println("[SamuraiModHelper] Merged patched DEX -> " + outApk);
    }

    private static boolean isClassesDex(String name) {
        if (!name.startsWith("classes") || !name.endsWith(".dex") || name.indexOf('/') >= 0) return false;
        String mid = name.substring(7, name.length() - 4);
        for (int i = 0; i < mid.length(); i++) {
            if (!Character.isDigit(mid.charAt(i))) return false;
        }
        return true;
    }

    private static void copyZipEntryAligned(
            ZipFile srcZip,
            ZipEntry srcEntry,
            ZipOutputStream zos,
            CountingOutputStream cos,
            byte[] buf
    ) throws IOException {
        String name = srcEntry.getName();
        ZipEntry outEntry = new ZipEntry(name);
        outEntry.setTime(1230768000000L); // deterministic timestamp (2009-01-01)

        if (srcEntry.isDirectory()) {
            outEntry.setMethod(ZipEntry.STORED);
            outEntry.setSize(0);
            outEntry.setCompressedSize(0);
            outEntry.setCrc(0);
            zos.putNextEntry(outEntry);
            zos.closeEntry();
            return;
        }

        if (srcEntry.getMethod() == ZipEntry.STORED) {
            long size = srcEntry.getSize();
            long crc = srcEntry.getCrc();
            byte[] preloaded = null;
            if (size < 0 || crc < 0) {
                preloaded = readAllBytes(srcZip.getInputStream(srcEntry), buf);
                size = preloaded.length;
                CRC32 c = new CRC32();
                c.update(preloaded);
                crc = c.getValue();
            }
            outEntry.setMethod(ZipEntry.STORED);
            outEntry.setSize(size);
            outEntry.setCompressedSize(size);
            outEntry.setCrc(crc);
            applyStoredAlignmentExtra(outEntry, name, cos.count);

            zos.putNextEntry(outEntry);
            if (preloaded != null) {
                zos.write(preloaded);
            } else {
                try (InputStream is = srcZip.getInputStream(srcEntry)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                }
            }
            zos.closeEntry();
        } else {
            outEntry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(outEntry);
            try (InputStream is = srcZip.getInputStream(srcEntry)) {
                int n;
                while ((n = is.read(buf)) > 0) {
                    zos.write(buf, 0, n);
                }
            }
            zos.closeEntry();
        }
    }

    private static void writeBytesEntryAligned(
            String name,
            byte[] data,
            boolean stored,
            ZipOutputStream zos,
            CountingOutputStream cos
    ) throws IOException {
        ZipEntry outEntry = new ZipEntry(name);
        outEntry.setTime(1230768000000L);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            outEntry.setMethod(ZipEntry.STORED);
            outEntry.setSize(data.length);
            outEntry.setCompressedSize(data.length);
            outEntry.setCrc(crc.getValue());
            applyStoredAlignmentExtra(outEntry, name, cos.count);
        } else {
            outEntry.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(outEntry);
        zos.write(data);
        zos.closeEntry();
    }

    private static void applyStoredAlignmentExtra(ZipEntry entry, String name, long lfhOffset) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int align = (name.startsWith("lib/") && name.endsWith(".so")) ? 4096 : 4;
        long baseDataStart = lfhOffset + 30L + nameBytes.length;
        int pad = (int) ((align - (baseDataStart % align)) % align);
        if (pad == 0) {
            entry.setExtra(null);
            return;
        }
        if (pad < 4) {
            pad += align;
        }
        byte[] extra = new byte[pad];
        extra[0] = (byte) (ZIPALIGN_EXTRA_HEADER_ID & 0xFF);
        extra[1] = (byte) ((ZIPALIGN_EXTRA_HEADER_ID >>> 8) & 0xFF);
        int dataLen = pad - 4;
        extra[2] = (byte) (dataLen & 0xFF);
        extra[3] = (byte) ((dataLen >>> 8) & 0xFF);
        entry.setExtra(extra);
    }

    private static byte[] readAllBytes(InputStream is, byte[] buf) throws IOException {
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // =========================================================================
    // 3. patch-meta <in_base_apk> <out_patched_apk> <pkg_name>
    // =========================================================================
    private static int patchMetaApk(File inApk, File outApk, String pkgName) throws Exception {
        File parent = outApk.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmpOut = new File(outApk.getAbsolutePath() + ".tmp");

        int totalPatched = 0;
        byte[] copyBuf = new byte[65536];

        try (ZipFile zf = new ZipFile(inApk);
             CountingOutputStream cos = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(tmpOut), 131072));
             ZipOutputStream zos = new ZipOutputStream(cos)) {

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (!isClassesDex(name)) {
                    copyZipEntryAligned(zf, ze, zos, cos, copyBuf);
                    continue;
                }

                byte[] dex = readAllBytes(zf.getInputStream(ze), copyBuf);
                int hits = patchDexForMeta(dex, pkgName);
                if (hits > 0) {
                    recomputeDexHashes(dex);
                    totalPatched += hits;
                    writeBytesEntryAligned(name, dex, ze.getMethod() == ZipEntry.STORED, zos, cos);
                } else {
                    copyZipEntryAligned(zf, ze, zos, cos, copyBuf);
                }
            }
        }

        if (totalPatched > 0) {
            finalizeFile(tmpOut, outApk);
        } else {
            tmpOut.delete();
        }
        return totalPatched;
    }

    private static int patchDexForMeta(byte[] dex, String pkg) {
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') {
            return 0;
        }

        Set<String> wantedStrings = new HashSet<>();
        wantedStrings.add("");
        if ("com.instagram.android".equals(pkg)) {
            wantedStrings.add("SponsoredContentController.insertItem");
            wantedStrings.add("/logging_client_events");
            wantedStrings.add("Z");
            wantedStrings.add("Ljava/lang/String;");
        } else if ("com.facebook.katana".equals(pkg)) {
            wantedStrings.add("__redex_internal_original_name");
            wantedStrings.add("AdBucketDataSourceUtil$attemptAdsInsertion$1");
            wantedStrings.add("AdBucketDataSourceUtil$attemptFetchMoreAds$1");
            wantedStrings.add("Lcom/facebook/graphql/model/GraphQLFBMultiAdsFeedUnit;");
            wantedStrings.add("run");
            wantedStrings.add("V");
        } else if ("com.facebook.orca".equals(pkg)) {
            wantedStrings.add("Lcom/facebook/messaging/business/inboxads/plugins/inboxads/itemsupplier/InboxAdsItemSupplierImplementation;");
            wantedStrings.add("ads_load_begin");
            wantedStrings.add("inbox_ads_fetch_start");
            wantedStrings.add("V");
        } else {
            return 0;
        }

        int stringIdsSize = u32(dex, 56);
        int stringIdsOff = u32(dex, 60);
        int typeIdsSize = u32(dex, 64);
        int typeIdsOff = u32(dex, 68);
        int protoIdsSize = u32(dex, 72);
        int protoIdsOff = u32(dex, 76);
        int fieldIdsSize = u32(dex, 80);
        int fieldIdsOff = u32(dex, 84);
        int methodIdsSize = u32(dex, 88);
        int methodIdsOff = u32(dex, 92);
        int classDefsSize = u32(dex, 96);
        int classDefsOff = u32(dex, 100);

        // Fast string_ids scan (only decodes strings matching candidate UTF-16 lengths!)
        Map<String, Integer> strIdxMap = findWantedStrings(dex, stringIdsSize, stringIdsOff, wantedStrings);

        // Quick bailout if this DEX file contains none of our package's anchor strings
        if ("com.instagram.android".equals(pkg)) {
            if (!strIdxMap.containsKey("SponsoredContentController.insertItem")
                    && !strIdxMap.containsKey("/logging_client_events")) {
                return 0;
            }
        } else if ("com.facebook.katana".equals(pkg)) {
            if (!strIdxMap.containsKey("AdBucketDataSourceUtil$attemptAdsInsertion$1")
                    && !strIdxMap.containsKey("AdBucketDataSourceUtil$attemptFetchMoreAds$1")
                    && !strIdxMap.containsKey("Lcom/facebook/graphql/model/GraphQLFBMultiAdsFeedUnit;")) {
                return 0;
            }
        } else if ("com.facebook.orca".equals(pkg)) {
            if (!strIdxMap.containsKey("Lcom/facebook/messaging/business/inboxads/plugins/inboxads/itemsupplier/InboxAdsItemSupplierImplementation;")) {
                return 0;
            }
        }

        int hits = 0;
        int emptyStrIdx = strIdxMap.getOrDefault("", -1);
        int boolTypeStrIdx = strIdxMap.getOrDefault("Z", -1);
        int voidTypeStrIdx = strIdxMap.getOrDefault("V", -1);
        int jStringTypeStrIdx = strIdxMap.getOrDefault("Ljava/lang/String;", -1);

        int igInsertStrIdx = strIdxMap.getOrDefault("SponsoredContentController.insertItem", -1);
        int igLogStrIdx = strIdxMap.getOrDefault("/logging_client_events", -1);

        int fbRedexNameStrIdx = strIdxMap.getOrDefault("__redex_internal_original_name", -1);
        int fbInsert1StrIdx = strIdxMap.getOrDefault("AdBucketDataSourceUtil$attemptAdsInsertion$1", -1);
        int fbFetch1StrIdx = strIdxMap.getOrDefault("AdBucketDataSourceUtil$attemptFetchMoreAds$1", -1);
        int fbMultiAdsClsStrIdx = strIdxMap.getOrDefault("Lcom/facebook/graphql/model/GraphQLFBMultiAdsFeedUnit;", -1);
        int runNameStrIdx = strIdxMap.getOrDefault("run", -1);

        int orcaInboxClsStrIdx = strIdxMap.getOrDefault(
                "Lcom/facebook/messaging/business/inboxads/plugins/inboxads/itemsupplier/InboxAdsItemSupplierImplementation;", -1);
        int orcaAdsLoadStrIdx = strIdxMap.getOrDefault("ads_load_begin", -1);
        int orcaFetchStartStrIdx = strIdxMap.getOrDefault("inbox_ads_fetch_start", -1);

        for (int c = 0; c < classDefsSize; c++) {
            int cdOff = classDefsOff + c * 32;
            int classIdx = u32(dex, cdOff);
            int classDataOff = u32(dex, cdOff + 24);
            int staticValuesOff = u32(dex, cdOff + 28);
            if (classDataOff == 0 || classIdx >= typeIdsSize) continue;

            int classDescStrIdx = u32(dex, typeIdsOff + classIdx * 4);

            if ("com.facebook.orca".equals(pkg) && classDescStrIdx != orcaInboxClsStrIdx) {
                continue;
            }

            boolean isFbMultiAdsUnit = ("com.facebook.katana".equals(pkg) && classDescStrIdx == fbMultiAdsClsStrIdx);
            boolean isFbAdBucketRunnable = false;

            int[] cur = new int[]{classDataOff};
            int staticFieldsSize = readUleb128(dex, cur);
            int instanceFieldsSize = readUleb128(dex, cur);
            int directMethodsSize = readUleb128(dex, cur);
            int virtualMethodsSize = readUleb128(dex, cur);

            int redexStaticFieldIndex = -1;
            int fIdx = 0;
            for (int i = 0; i < staticFieldsSize; i++) {
                fIdx += readUleb128(dex, cur);
                readUleb128(dex, cur); // access_flags
                if ("com.facebook.katana".equals(pkg) && fbRedexNameStrIdx >= 0 && fIdx < fieldIdsSize) {
                    int fNameIdx = u32(dex, fieldIdsOff + fIdx * 8 + 4);
                    if (fNameIdx == fbRedexNameStrIdx) {
                        redexStaticFieldIndex = i;
                    }
                }
            }
            for (int i = 0; i < instanceFieldsSize; i++) {
                readUleb128(dex, cur);
                readUleb128(dex, cur);
            }

            if ("com.facebook.katana".equals(pkg)) {
                if (!isFbMultiAdsUnit) {
                    if (redexStaticFieldIndex >= 0 && staticValuesOff > 0) {
                        int initStrIdx = readStaticFieldStringIdx(dex, staticValuesOff, redexStaticFieldIndex);
                        if (initStrIdx >= 0 && (initStrIdx == fbInsert1StrIdx || initStrIdx == fbFetch1StrIdx)) {
                            isFbAdBucketRunnable = true;
                        }
                    }
                    if (!isFbAdBucketRunnable) continue;
                }
            }

            // Scan direct + virtual methods
            for (int pass = 0; pass < 2; pass++) {
                int mCount = (pass == 0) ? directMethodsSize : virtualMethodsSize;
                int mIdx = 0;
                for (int m = 0; m < mCount; m++) {
                    mIdx += readUleb128(dex, cur);
                    int accessFlags = readUleb128(dex, cur);
                    int codeOff = readUleb128(dex, cur);
                    if (codeOff == 0 || mIdx >= methodIdsSize) continue;

                    int mIdOff = methodIdsOff + mIdx * 8;
                    int protoIdx = u16(dex, mIdOff + 2);
                    int mNameIdx = u32(dex, mIdOff + 4);
                    if (protoIdx >= protoIdsSize) continue;

                    int pOff = protoIdsOff + protoIdx * 12;
                    int retTypeIdx = u32(dex, pOff + 4);
                    int paramsOff = u32(dex, pOff + 8);
                    if (retTypeIdx >= typeIdsSize) continue;
                    int retTypeStrIdx = u32(dex, typeIdsOff + retTypeIdx * 4);

                    if ("com.instagram.android".equals(pkg)) {
                        // Hook 1: SponsoredContentController.insertItem -> return false
                        if (igInsertStrIdx >= 0
                                && retTypeStrIdx == boolTypeStrIdx
                                && (accessFlags & 0x0002) != 0
                                && hasTwoObjectParams(dex, paramsOff, typeIdsSize, typeIdsOff)
                                && codeReferencesString(dex, codeOff, igInsertStrIdx, -1)) {
                            if (overwriteWithReturnZero(dex, codeOff, false)) {
                                hits++;
                            }
                        }
                        // Hook 2: /logging_client_events -> return "" (or null fallback)
                        if (igLogStrIdx >= 0
                                && retTypeStrIdx == jStringTypeStrIdx
                                && codeReferencesString(dex, codeOff, igLogStrIdx, -1)) {
                            if (overwriteWithReturnEmptyString(dex, codeOff, emptyStrIdx)) {
                                hits++;
                            }
                        }
                    } else if ("com.facebook.katana".equals(pkg)) {
                        // Hook 1: AdBucketDataSourceUtil$attemptAdsInsertion$1 / attemptFetchMoreAds$1 .run()V -> return-void
                        if (isFbAdBucketRunnable
                                && mNameIdx == runNameStrIdx
                                && retTypeStrIdx == voidTypeStrIdx
                                && paramsOff == 0) {
                            if (overwriteWithReturnVoid(dex, codeOff)) {
                                hits++;
                            }
                        }
                        // Hook 2: GraphQLFBMultiAdsFeedUnit public final ()L...; 5-insn getter -> return null
                        if (isFbMultiAdsUnit
                                && (accessFlags & 0x0011) == 0x0011
                                && paramsOff == 0
                                && isObjectDescriptor(dex, stringIdsOff, retTypeStrIdx)
                                && matchesFbMultiAdsGetter(dex, codeOff)) {
                            if (overwriteWithReturnZero(dex, codeOff, true)) {
                                hits++;
                            }
                        }
                    } else if ("com.facebook.orca".equals(pkg)) {
                        // Hook 1: InboxAdsItemSupplierImplementation ads loader -> return-void
                        if (retTypeStrIdx == voidTypeStrIdx
                                && codeReferencesString(dex, codeOff, orcaAdsLoadStrIdx, orcaFetchStartStrIdx)) {
                            if (overwriteWithReturnVoid(dex, codeOff)) {
                                hits++;
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    private static Map<String, Integer> findWantedStrings(
            byte[] dex, int stringIdsSize, int stringIdsOff, Set<String> wanted
    ) {
        Set<Integer> wantedLens = new HashSet<>();
        for (String w : wanted) wantedLens.add(w.length());

        Map<String, Integer> found = new HashMap<>();
        int[] cur = new int[1];
        for (int i = 0; i < stringIdsSize; i++) {
            int dataOff = u32(dex, stringIdsOff + i * 4);
            if (dataOff <= 0 || dataOff >= dex.length) continue;
            cur[0] = dataOff;
            int utf16Len = readUleb128(dex, cur);
            if (!wantedLens.contains(utf16Len)) continue;

            // All wanted strings are pure ASCII, so MUTF-8 byte length == utf16Len
            int start = cur[0];
            if (start + utf16Len >= dex.length || dex[start + utf16Len] != 0) continue;
            String s = new String(dex, start, utf16Len, StandardCharsets.US_ASCII);
            if (wanted.contains(s)) {
                found.put(s, i);
                if (found.size() == wanted.size()) break;
            }
        }
        return found;
    }

    private static boolean isObjectDescriptor(byte[] dex, int stringIdsOff, int strIdx) {
        if (strIdx < 0) return false;
        int dataOff = u32(dex, stringIdsOff + strIdx * 4);
        int[] cur = new int[]{dataOff};
        int len = readUleb128(dex, cur);
        return len >= 3 && cur[0] < dex.length && dex[cur[0]] == 'L';
    }

    private static boolean hasTwoObjectParams(byte[] dex, int paramsOff, int typeIdsSize, int typeIdsOff) {
        if (paramsOff == 0 || paramsOff + 8 > dex.length) return false;
        int count = u32(dex, paramsOff);
        if (count != 2) return false;
        int stringIdsOff = u32(dex, 60);
        for (int i = 0; i < 2; i++) {
            int tIdx = u16(dex, paramsOff + 4 + i * 2);
            if (tIdx >= typeIdsSize) return false;
            int sIdx = u32(dex, typeIdsOff + tIdx * 4);
            if (!isObjectDescriptor(dex, stringIdsOff, sIdx)) return false;
        }
        return true;
    }

    private static int readStaticFieldStringIdx(byte[] dex, int staticValuesOff, int targetIndex) {
        int[] cur = new int[]{staticValuesOff};
        int arrSize = readUleb128(dex, cur);
        if (targetIndex >= arrSize) return -1;
        for (int i = 0; i < targetIndex; i++) {
            if (!skipEncodedValue(dex, cur)) return -1;
        }
        if (cur[0] >= dex.length) return -1;
        int hdr = dex[cur[0]++] & 0xFF;
        int valType = hdr & 0x1F;
        int valArg = (hdr >>> 5) & 0x07;
        if (valType != 0x17) return -1; // VALUE_STRING
        int nBytes = valArg + 1;
        if (cur[0] + nBytes > dex.length) return -1;
        int strIdx = 0;
        for (int b = 0; b < nBytes; b++) {
            strIdx |= (dex[cur[0] + b] & 0xFF) << (b * 8);
        }
        return strIdx;
    }

    private static boolean skipEncodedValue(byte[] dex, int[] cur) {
        if (cur[0] >= dex.length) return false;
        int hdr = dex[cur[0]++] & 0xFF;
        int valType = hdr & 0x1F;
        int valArg = (hdr >>> 5) & 0x07;
        switch (valType) {
            case 0x1e: // VALUE_NULL
            case 0x1f: // VALUE_BOOLEAN
                return true;
            case 0x1c: { // VALUE_ARRAY
                int sz = readUleb128(dex, cur);
                for (int i = 0; i < sz; i++) {
                    if (!skipEncodedValue(dex, cur)) return false;
                }
                return true;
            }
            case 0x1d: { // VALUE_ANNOTATION
                readUleb128(dex, cur); // type_idx
                int sz = readUleb128(dex, cur);
                for (int i = 0; i < sz; i++) {
                    readUleb128(dex, cur); // name_idx
                    if (!skipEncodedValue(dex, cur)) return false;
                }
                return true;
            }
            default: {
                int len = valArg + 1;
                if (cur[0] + len > dex.length) return false;
                cur[0] += len;
                return true;
            }
        }
    }

    private static boolean codeReferencesString(byte[] dex, int codeOff, int strIdx1, int strIdx2) {
        if (strIdx1 < 0 && strIdx2 < 0) return false;
        if (codeOff + 16 > dex.length) return false;
        int insnsUnits = u32(dex, codeOff + 12);
        int insnsBase = codeOff + 16;
        if (insnsBase + insnsUnits * 2 > dex.length) return false;

        int pc = 0;
        while (pc < insnsUnits) {
            int unit0 = u16(dex, insnsBase + pc * 2);
            int op = unit0 & 0xFF;
            if (op == 0x00 && unit0 != 0) {
                int step = payloadWidthUnits(dex, insnsBase + pc * 2, unit0);
                if (step <= 0) break;
                pc += step;
                continue;
            }
            int w = INSN_WIDTH[op];
            if (w <= 0 || pc + w > insnsUnits) break;
            if (op == 0x1a) { // const-string vAA, string@BBBB
                int s = u16(dex, insnsBase + (pc + 1) * 2);
                if (s == strIdx1 || (strIdx2 >= 0 && s == strIdx2)) return true;
            } else if (op == 0x1b) { // const-string/jumbo vAA, string@BBBBBBBB
                int s = u32(dex, insnsBase + (pc + 1) * 2);
                if (s == strIdx1 || (strIdx2 >= 0 && s == strIdx2)) return true;
            }
            pc += w;
        }
        return false;
    }

    private static boolean matchesFbMultiAdsGetter(byte[] dex, int codeOff) {
        if (codeOff + 16 > dex.length) return false;
        int insnsUnits = u32(dex, codeOff + 12);
        int insnsBase = codeOff + 16;
        if (insnsUnits < 5 || insnsUnits > 16 || insnsBase + insnsUnits * 2 > dex.length) return false;

        int[] ops = new int[6];
        int count = 0;
        int pc = 0;
        while (pc < insnsUnits) {
            int unit0 = u16(dex, insnsBase + pc * 2);
            int op = unit0 & 0xFF;
            if (op == 0x00 && unit0 != 0) return false;
            int w = INSN_WIDTH[op];
            if (w <= 0 || pc + w > insnsUnits || count >= 6) return false;
            ops[count++] = op;
            pc += w;
        }
        if (count != 5) return false;
        return isConstOp(ops[0])
                && isConstOp(ops[1])
                && (ops[2] == 0x71 || ops[2] == 0x77)
                && ops[3] == 0x0c
                && ops[4] == 0x11;
    }

    private static boolean isConstOp(int op) {
        return op >= 0x12 && op <= 0x19;
    }

    private static int payloadWidthUnits(byte[] dex, int byteOff, int ident) {
        if (ident == 0x0100) { // packed-switch-payload
            int sz = u16(dex, byteOff + 2);
            return sz * 2 + 4;
        } else if (ident == 0x0200) { // sparse-switch-payload
            int sz = u16(dex, byteOff + 2);
            return sz * 4 + 2;
        } else if (ident == 0x0300) { // fill-array-data-payload
            int elemWidth = u16(dex, byteOff + 2);
            int sz = u32(dex, byteOff + 4);
            return (sz * elemWidth + 1) / 2 + 4;
        }
        return -1;
    }

    private static boolean overwriteWithReturnVoid(byte[] dex, int codeOff) {
        int insnsUnits = u32(dex, codeOff + 12);
        if (insnsUnits < 1) return false;
        int base = codeOff + 16;
        // return-void (0x000e) + nop padding (1-unit boundaries everywhere, preserves try_items)
        dex[base] = 0x0e;
        dex[base + 1] = 0x00;
        for (int i = 2; i < insnsUnits * 2; i++) dex[base + i] = 0;
        return true;
    }

    private static boolean overwriteWithReturnZero(byte[] dex, int codeOff, boolean isObject) {
        int regs = u16(dex, codeOff);
        int insnsUnits = u32(dex, codeOff + 12);
        if (regs < 1 || insnsUnits < 2) return false;
        int base = codeOff + 16;
        dex[base] = 0x12;     // const/4 v0, #0
        dex[base + 1] = 0x00;
        dex[base + 2] = (byte) (isObject ? 0x11 : 0x0f); // return-object v0 (0x11) or return v0 (0x0f)
        dex[base + 3] = 0x00;
        for (int i = 4; i < insnsUnits * 2; i++) dex[base + i] = 0;
        return true;
    }

    private static boolean overwriteWithReturnEmptyString(byte[] dex, int codeOff, int emptyStrIdx) {
        int regs = u16(dex, codeOff);
        int triesSize = u16(dex, codeOff + 6);
        int insnsUnits = u32(dex, codeOff + 12);
        if (regs < 1 || insnsUnits < 2) return false;
        int base = codeOff + 16;

        // Only emit const-string (which carries kThrow) when triesSize == 0 so catch blocks stay unreachable
        if (emptyStrIdx >= 0 && emptyStrIdx < 65536 && insnsUnits >= 3 && triesSize == 0) {
            dex[base] = 0x1a; // const-string v0, string@BBBB
            dex[base + 1] = 0x00;
            dex[base + 2] = (byte) (emptyStrIdx & 0xFF);
            dex[base + 3] = (byte) ((emptyStrIdx >>> 8) & 0xFF);
            dex[base + 4] = 0x11; // return-object v0
            dex[base + 5] = 0x00;
            for (int i = 6; i < insnsUnits * 2; i++) dex[base + i] = 0;
            return true;
        }
        if (emptyStrIdx >= 65536 && insnsUnits >= 4 && triesSize == 0) {
            dex[base] = 0x1b; // const-string/jumbo v0, string@BBBBBBBB
            dex[base + 1] = 0x00;
            dex[base + 2] = (byte) (emptyStrIdx & 0xFF);
            dex[base + 3] = (byte) ((emptyStrIdx >>> 8) & 0xFF);
            dex[base + 4] = (byte) ((emptyStrIdx >>> 16) & 0xFF);
            dex[base + 5] = (byte) ((emptyStrIdx >>> 24) & 0xFF);
            dex[base + 6] = 0x11; // return-object v0
            dex[base + 7] = 0x00;
            for (int i = 8; i < insnsUnits * 2; i++) dex[base + i] = 0;
            return true;
        }
        return overwriteWithReturnZero(dex, codeOff, true);
    }

    private static void recomputeDexHashes(byte[] dex) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(dex, 32, dex.length - 32);
        byte[] sig = sha1.digest();
        System.arraycopy(sig, 0, dex, 12, 20);

        Adler32 adler = new Adler32();
        adler.update(dex, 12, dex.length - 12);
        long csum = adler.getValue();
        dex[8] = (byte) (csum & 0xFF);
        dex[9] = (byte) ((csum >>> 8) & 0xFF);
        dex[10] = (byte) ((csum >>> 16) & 0xFF);
        dex[11] = (byte) ((csum >>> 24) & 0xFF);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readUleb128(byte[] b, int[] cur) {
        int result = 0;
        int shift = 0;
        while (cur[0] < b.length) {
            int v = b[cur[0]++] & 0xFF;
            result |= (v & 0x7F) << shift;
            if ((v & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    // =========================================================================
    // 4. detach-playstore <pkg...>
    // =========================================================================
    private static void detachPlayStore(List<String> pkgs) {
        if (pkgs.isEmpty()) return;
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            String p = pkgs.get(i).replace("'", "");
            if (i > 0) inClause.append(',');
            inClause.append('\'').append(p).append('\'');
        }
        String pkgListSql = inClause.toString();

        File dbDir = new File("/data/data/com.android.vending/databases");
        if (!dbDir.isDirectory()) return;

        // 1. localappstate.db: disable auto_update (2) + install persistent trigger
        File localAppState = new File(dbDir, "localappstate.db");
        if (localAppState.isFile()) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    localAppState.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                if (tableExists(db, "appstate")) {
                    db.execSQL("UPDATE appstate SET auto_update = 2 WHERE package_name IN (" + pkgListSql + ")");
                    db.execSQL("DROP TRIGGER IF EXISTS samurai_detach_appstate_ins");
                    db.execSQL("DROP TRIGGER IF EXISTS samurai_detach_appstate_upd");
                    db.execSQL("CREATE TRIGGER samurai_detach_appstate_ins AFTER INSERT ON appstate "
                            + "WHEN NEW.package_name IN (" + pkgListSql + ") BEGIN "
                            + "UPDATE appstate SET auto_update = 2 WHERE package_name = NEW.package_name; END");
                    db.execSQL("CREATE TRIGGER samurai_detach_appstate_upd AFTER UPDATE OF auto_update ON appstate "
                            + "WHEN NEW.package_name IN (" + pkgListSql + ") AND NEW.auto_update != 2 BEGIN "
                            + "UPDATE appstate SET auto_update = 2 WHERE package_name = NEW.package_name; END");
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. library.db: remove Play Store ownership rows + install persistent trigger
        File libraryDb = new File(dbDir, "library.db");
        if (libraryDb.isFile()) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    libraryDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                if (tableExists(db, "ownership")) {
                    db.execSQL("DELETE FROM ownership WHERE doc_id IN (" + pkgListSql + ")");
                    db.execSQL("DROP TRIGGER IF EXISTS samurai_detach_ownership_ins");
                    db.execSQL("CREATE TRIGGER samurai_detach_ownership_ins AFTER INSERT ON ownership "
                            + "WHEN NEW.doc_id IN (" + pkgListSql + ") BEGIN "
                            + "DELETE FROM ownership WHERE doc_id = NEW.doc_id; END");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return c.moveToFirst();
        }
    }

    // =========================================================================
    // 5. extract-zip <zip_file> <dest_dir> [prefix] [strip_dirs]
    // =========================================================================
    private static void extractZip(File zipFile, File destDir, String prefix, boolean stripDirs) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Cannot mkdir " + destDir);
        }
        String canonicalRoot = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (!prefix.isEmpty() && !name.startsWith(prefix)) continue;
                if (ze.isDirectory()) continue;
                String relName = stripDirs ? new File(name).getName() : name;
                if (relName.isEmpty()) continue;
                File out = new File(destDir, relName);
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
                    throw new IOException("Zip Slip blocked: " + name);
                }
                File p = out.getParentFile();
                if (p != null && !p.exists()) p.mkdirs();
                try (InputStream is = zf.getInputStream(ze);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 65536)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                out.setReadable(true, false);
            }
        }
    }
}
