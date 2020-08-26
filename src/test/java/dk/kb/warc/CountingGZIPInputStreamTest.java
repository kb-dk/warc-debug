package dk.kb.warc;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class CountingGZIPInputStreamTest {
    private static final Logger log = LoggerFactory.getLogger(CountingGZIPInputStreamTest.class);

    @Test
    void singleCompressed() throws IOException {
        verifyEntries("compressed.txt.gz");
    }

    @Test
    void multiCompressed() throws IOException {
        verifyEntries("compressed_multi.txt.gz");
    }

    /*
     * Extracts all entries, then verify if the extraction data matches.
     */
    private void verifyEntries(String gzipFile) throws IOException {
        try (InputStream compressed =
                     Thread.currentThread().getContextClassLoader().getResourceAsStream(gzipFile);
             CountingGZIPInputStream gis = new CountingGZIPInputStream(compressed, true)) {
            gis.empty();
            verifyEntries(gzipFile, gis.getEntries());
            log.info(gzipFile + ": " + gis.toString());
        };
    }


    /*
     * Iterates all entries and for each, cut the corresponding part of the gzipFile and try to decompress it.
     */
    private void verifyEntries(String gzipFile, List<CountingGZIPInputStream.Entry> entries) throws IOException {
        URL resource = Thread.currentThread().getContextClassLoader().getResource(gzipFile);
        assertNotNull(resource, "The resource '" + gzipFile + "' should be resolvable");
        CountingGZIPInputStream.Entry last = entries.get(entries.size()-1);
        assertEquals(last.offset + last.compressedSize, Files.size(Path.of(resource.getPath())),
                     "The size of the compressed resource file " + gzipFile + " should match the offset " +
                     "(" + last.offset + ") + compressed size (" + last.compressedSize + ") of the last entry");

        for (CountingGZIPInputStream.Entry entry: entries) {
            try (InputStream in = resource.openStream()) {
                assertEquals(entry.offset, in.skip(entry.offset),
                             "Skipping in the raw input stream should jump the right distance");
                byte[] entryBytes = new byte[(int) entry.compressedSize];
                assertEquals(entry.compressedSize, in.read(entryBytes, 0, (int) entry.compressedSize),
                             "Entry #" + entry.id + ": The number of bytes read from offset " + entry.offset +
                             " and up to compressedSize should be as expected");

                ByteArrayInputStream bin = new ByteArrayInputStream(entryBytes);
                try (GZIPInputStream gin = new GZIPInputStream(bin)) {
                    try {
                        assertEquals(entry.uncompressedSize, gin.skip(entry.uncompressedSize),
                                     "It should be possible to uncompress the expected number of bytes");
                    } catch (EOFException e) {
                        fail(String.format(Locale.ROOT, "Reached EOF for entry #%d (offset=%d, " +
                                                        "compressed=%s bytes) while skipping uncompressed %d bytes",
                                           entry.id, entry.offset, entry.compressedSize, entry.uncompressedSize));
                    }
                    assertEquals(gin.read(), -1,
                                 "After reading the expected content, the gzip entry should be depleted");
                }
            }
        }
    }
}