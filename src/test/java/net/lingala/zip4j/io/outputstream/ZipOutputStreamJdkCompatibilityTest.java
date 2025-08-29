package net.lingala.zip4j.io.outputstream;

import net.lingala.zip4j.AbstractIT;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that ZipOutputStream#close() auto-finalizes a still-open entry
 * (i.e., when the last entry hasn't been explicitly closeEntry()'d).
 *
 * ORIGINAL close():
 *   if (!entryClosed) closeEntry();   // auto-finalize
 *   ... finalizeZipFile(...)
 *   countingOutputStream.close();
 *
 * BUGGY close() (your change): removes the auto-finalize line above.
 *
 * With the buggy close(), the central directory contains 0 entries,
 * so JDK ZipFile enumerates 0 (no exception). We assert counts/readability.
 */
public class ZipOutputStreamJdkCompatibilityTest extends AbstractIT {

    @Test
    public void closeMustAutoFinalizeSingleOpenEntry() throws Exception {
        File out = generatedZipFile;
        if (out.exists()) out.delete();

        // Arrange: write a single entry; do NOT call closeEntry()
        byte[] payload = new byte[128 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            ZipParameters p = new ZipParameters();
            p.setFileNameInZip("a.bin");
            p.setCompressionMethod(CompressionMethod.DEFLATE);
            zos.putNextEntry(p);
            zos.write(payload);
            // no zos.closeEntry(); close() must auto-finalize
        }

        // JDK view: must see exactly one entry and be able to read it fully
        int jdkCount = 0;
        try (java.util.zip.ZipFile jdkZip = new java.util.zip.ZipFile(out)) {
            Enumeration<? extends ZipEntry> en = jdkZip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                jdkCount++;
                // size in central directory should match original uncompressed size
                assertEquals(payload.length, ze.getSize());

                // fully read to ensure local header/descriptors are consistent
                int total = 0, n;
                byte[] buf = new byte[8192];
                try (InputStream is = jdkZip.getInputStream(ze)) {
                    while ((n = is.read(buf)) != -1) total += n;
                }
                assertEquals("Should read full uncompressed payload", payload.length, total);
            }
        }
        assertEquals("Exactly one entry must be present in central directory", 1, jdkCount);

        // Zip4j view: central directory must also list exactly one header
        ZipFile z4j = new ZipFile(out);
        assertEquals("Zip4j central directory count", 1, z4j.getFileHeaders().size());
    }

    @Test
    public void closeMustAutoFinalizeLastOfTwoEntries() throws Exception {
        File out = generatedZipFile;
        if (out.exists()) out.delete();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            ZipParameters p1 = new ZipParameters();
            p1.setFileNameInZip("first.txt");
            zos.putNextEntry(p1);
            zos.write("hello".getBytes("UTF-8"));
            zos.closeEntry(); // explicit for first

            ZipParameters p2 = new ZipParameters();
            p2.setFileNameInZip("second.txt");
            zos.putNextEntry(p2);
            zos.write("world".getBytes("UTF-8"));
            // no closeEntry(); close() must auto-finalize second
        }

        int jdkCount = 0;
        try (java.util.zip.ZipFile jdkZip = new java.util.zip.ZipFile(out)) {
            Enumeration<? extends ZipEntry> en = jdkZip.entries();
            byte[] buf = new byte[256];
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                jdkCount++;
                int total = 0, n;
                try (InputStream is = jdkZip.getInputStream(ze)) {
                    while ((n = is.read(buf)) != -1) total += n;
                }
                assertTrue("Each entry must be readable", total > 0);
            }
        }
        assertEquals("Both entries must appear in central directory", 2, jdkCount);

        ZipFile z4j = new ZipFile(out);
        assertEquals("Zip4j central directory count", 2, z4j.getFileHeaders().size());
    }
}
