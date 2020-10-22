package dk.kb.warc;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ProbeCompressionTest {
    private static final Logger log = LoggerFactory.getLogger(ProbeCompressionTest.class);

    @Test
    void uncompressed() throws IOException {
        Report report = makeReport("uncompressed.txt");
        assertEquals(Report.STATUS.uncompressed, report.getStatus());
    }

    @Test
    void singleCompressed() throws IOException {
        Report report = makeReport("compressed.txt.gz");
        assertEquals(Report.STATUS.singleCompressed, report.getStatus());
    }

    @Test
    void multiCompressed() throws IOException {
        Report report = makeReport("compressed_multi.txt.gz");
        assertEquals(Report.STATUS.multiCompressed, report.getStatus());
        assertEquals(4, report.getEntryCount());
    }

    // TODO: Add test for faulty checksum gzip (faultyCompressed)

    // TODO: Add test for truncated gzip

    @Test
    void productionWARC() throws IOException {
        String warc = "/home/te/projects/warc-debug/346076-343-20200928145842335-00071-kb-prod-har-004.kb.dk.warc.gz";
        Path warcPath = Paths.get(warc);
        if (!Files.exists(warcPath)) {
            log.info("Unable to run productionWARC-test as the input file is not present: " + warc);
            return;
        }
        System.out.println(makeReport(warcPath).toString(true));
    }

    @Test
    void faultyFirst() throws IOException {
        Report report = makeReport("partial_first.txt.gz");
        assertEquals(Report.STATUS.garbageAtEnd, report.getStatus());
        assertEquals(1, report.getEntryCount());
    }

    @Test
    void faultySecond() throws IOException {
        Report report = makeReport("partial_second.txt.gz");
        assertEquals(Report.STATUS.uncompressed, report.getStatus());
        assertEquals(0, report.getEntryCount());
    }

    @Test
    void recompressed() throws IOException {
        Report report = makeReport("recompressed_compressed_multi.txt.gz.gz");
        assertEquals(Report.STATUS.recompressed, report.getStatus());
        assertEquals(4, report.getEntryCount());
    }



    public Report makeReport(String resource) throws IOException {
        return ProbeCompression.analyse(Path.of(Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResource(resource)).getPath()));
    }
    public Report makeReport(Path resource) throws IOException {
        return ProbeCompression.analyse(resource);
    }

}