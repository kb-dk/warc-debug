package dk.kb.warc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Takes a file as input and analyzes its compression. Currently only GZip is supported.
 * The report will state how many parts of the overall file that are compressed.
 *
 * Intended use: WARC-files are typically Gzipped in chunks as GZipping a whole WARC as a single chunk
 * makes it impossible to perform direct seeks to records inside of the WARC. Noty all tools does this
 * correctly and it can be hard to check if a .warc.gz-file is properly compressed without performing
 * a test-run with a large tool such as webarchive_discovery.
 */
public class ProbeCompression {
    private static final Logger log = LoggerFactory.getLogger(ProbeCompression.class);

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Please provide at least one file as argument");
        }
        for (String file: args) {
            System.out.println("\nProcessing '" + file + "'...");
            Report report = analyse(Path.of(file));
            System.out.println(report.toString(true));
        }

        //String applicationConfig = System.getProperty("dk.kb.applicationConfig");
        //System.out.println("Application config can be found in: '" + applicationConfig + "'");

        //System.out.println("Arguments passed by commandline is: " + Arrays.asList(args));

        //YAML config = new YAML(applicationConfig);
        //String speaker = config.getString("config.speaker");

    }

    /**
     * Reads through all entries in the given input and returns a report over offsets and sizes.
     * @param input a Gzipped file.
     * @return a report of the file.
     * @throws IOException if the file could not be read.
     */
    public static Report analyse(Path input) throws IOException {
        if (!input.toFile().exists()) {
            throw new FileNotFoundException("The file " + input + " could not be located");

        }
        Report report = analyse(input.getFileName().toString(), Files.newInputStream(input));
        if (report.getStatus().equals(Report.STATUS.singleCompressed)) {
            Report innerReport =
                    analyse(input.getFileName().toString(), new GZIPInputStream(Files.newInputStream(input)));
            if (innerReport.getStatus().equals(Report.STATUS.multiCompressed)) {
                return new Report(input.getFileName().toString(), Report.STATUS.recompressed, innerReport.getEntries());
            }
        }
        return report;
    }

    /**
     * Reads through all entries in the given input and returns a report over offsets and sizes.
     * Important: This call is streamed and cannot examine the case where a entry-level Gzipped WARC file is also
     * Gzipped overall. It is recommended to use {@link #analyse(Path)}.
     * @param input a Gzipped file.
     * @return a report of the file.
     * @throws IOException if the file could not be read.
     */
    static Report analyse(String filename, InputStream input) throws IOException {
        try (input ; CountingGZIPInputStream gis = new CountingGZIPInputStream(input, true)) {
            try {
                gis.empty();
            } catch (ZipException e) {
                return CountingGZIPInputStream.GARBAGE_AFTER.equals(e.getMessage()) ?
                        new Report(filename, Report.STATUS.garbageAtEnd, gis.getEntries(), e) :
                        new Report(filename, Report.STATUS.faultyCompressed, gis.getEntries(), e);
            } catch (IOException e) {
                throw new IllegalStateException("IOException reading '" + filename + "'", e);
            }
            // TODO: Check if all data has been read from input (needs extra code in CountingGZIPInputStream)
            return new Report(
                    filename,
                    gis.getEntries().size() == 1 ? Report.STATUS.singleCompressed : Report.STATUS.multiCompressed,
                    gis.getEntries());
        } catch (ZipException e) {
            return new Report(filename, Report.STATUS.uncompressed, Collections.emptyList(), e);
        }
    }
}
