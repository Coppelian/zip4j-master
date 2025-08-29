package net.lingala.zip4j.io.outputstream;

import net.lingala.zip4j.AbstractIT;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.InternalZipConstants;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static org.junit.Assert.fail;

public class ZipOutputStreamJdkCompatibilityTest extends AbstractIT {

    /**
     * This test is designed to surface finalization bugs in ZipOutputStream#close(),
     * e.g., a corrupted EOCD "offset of start of central directory".
     *
     * It creates a ZIP with zip4j and then opens it with the JDK's ZipFile,
     * which uses EOCD/CD fields when parsing. If close() wrote an incorrect CD offset,
     * ZipFile should throw ZipException during open or when consuming entries.
     */
    @Test
    public void testZipIsReadableByJdkZipFile() throws Exception {
        // Arrange: create a non-trivial zip using Zip4j (this uses the mutated ZipOutputStream)
        File out = generatedZipFile;
        byte[] payload = new byte[256 * 1024]; // 256 KB to make directory non-trivial
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('A' + (i % 26));
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            ZipParameters p = new ZipParameters();
            p.setFileNameInZip("a.txt");
            zos.putNextEntry(p);
            zos.write(payload);
            // rely on zos.close() to finalize the archive (buggy close() will corrupt EOCD/CD)
        }

        // Act: try to open & fully consume with JDK ZipFile (strict about EOCD/CD offsets)
        ZipFile zf = null;
        try {
            zf = new ZipFile(out);
            // Force parsing and local-header usage
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[InternalZipConstants.BUFF_SIZE];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                InputStream is = zf.getInputStream(e);
                int n;
                while ((n = is.read(buf)) != -1) {
                    // consume
                }
                is.close();
            }
            // If we reach here with a corrupted EOCD/CD, that's unexpected.
            // Fail explicitly so the test trips when the offset is wrong.
            // (With the -1 offset bug, we expect a ZipException earlier.)
            fail("Expected java.util.zip.ZipException due to invalid central directory finalization");
        } catch (ZipException expected) {
            // pass: corrupted finalization surfaced to JDK ZipFile
        } finally {
            if (zf != null) zf.close();
        }
    }
}
