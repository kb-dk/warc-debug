/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package dk.kb.warc;

import java.util.List;
import java.util.Locale;

/**
 *
 */
public class Report {
    public enum STATUS {
        /**
         * The input stream was not Gzip-compressed.
         */
        uncompressed,
        /**
         * The input stream was a single Gzip entry.
         */
        singleCompressed,
        /**
         * The input stream contained multiple GZip entries.
         */
        multiCompressed,
        /**
         * The input stream contained one or more Gzip entries, but was at least one was not valid.
         */
        faultyCompressed,
        /**
         * The input stream contained one or more valid GZip entries, folowed by something that was not GZip
         */
        garbageAtEnd,
        /**
         * The input stream was a single GZip entry, but contained a stream with multiple GZip entries when uncompressed.
         */
        recompressed;
        // TODO: Add truncated

        public String getRecommendation(String filename) {
            switch (this) {
                case uncompressed: {
                    if (filename != null && filename.toLowerCase(Locale.ENGLISH).endsWith(".gz")) {
                        return "The WARC file is multi-entry uncompressed, but the filename ends with '.gz'. " +
                               "Remove the .gz-extension or compress the WARC.";
                    }
                    return "Consider compressing the WARC";
                }
                case singleCompressed: {
                    return "The WARC file is a single Gzip block, so random access will not work." +
                           "Recompress the WARC to consist of independently compressed entries.";
                }
                case multiCompressed: {
                    if (filename != null && !filename.toLowerCase(Locale.ENGLISH).endsWith(".gz")) {
                        return "The WARC file is multi-entry compressed, but the filename does not end with '.gz'. " +
                               "Add the .gz-extension to the filename.";
                    }
                    return "Everything seems to be in order (multi-entry compressed WARC file)";
                }
                case faultyCompressed: {
                    if (filename != null && !filename.toLowerCase(Locale.ENGLISH).endsWith(".gz")) {
                        return "The WARC file is compressed but contains compression errors." +
                               "Furthermore the WARC filename does not have a .gz-extension.";
                    }
                    return "The WARC file is compressed but contains compression errors.";
                }
                // TODO: Extend CountingGZIPOInputStream to register this
                case garbageAtEnd: {
                    if (filename != null && !filename.toLowerCase(Locale.ENGLISH).endsWith(".gz")) {
                        return "The WARC file is compressed but contains extra uncompressed content." +
                               "Furthermore the WARC filename does not have a .gz-extension.";
                    }
                    return "The WARC file is compressed but contains extra uncompressed content";
                }
                case recompressed: {
                    return "The whole stream was a single Gzip block, but when uncompressed it contained multiple" +
                           "compressed blocks. Uncompress the stream once and ensure the result has a .gz extension.";
                }
                default: throw new UnsupportedOperationException("Not implemented yet");
            }
        }
    }

    ;

    private final String filename;
    private final STATUS status;
    private final List<CountingGZIPInputStream.Entry> entries;
    private final Exception exception;

    public Report(STATUS status, List<CountingGZIPInputStream.Entry> entries) {
        this(null, status, entries, null);
    }

    public Report(String filename, STATUS status, List<CountingGZIPInputStream.Entry> entries) {
        this(filename, status, entries, null);
    }

    public Report(String filename, STATUS status, List<CountingGZIPInputStream.Entry> entries, Exception exception) {
        this.filename = filename;
        this.status = status;
        this.entries = entries;
        this.exception = exception;
    }

    public STATUS getStatus() {
        return status;
    }

    public String getRecommendation() {
        return status.getRecommendation(filename);
    }

    public List<CountingGZIPInputStream.Entry> getEntries() {
        return entries;
    }

    public Exception getException() {
        return exception;
    }

    /**
     * @return the number of entries in the (potentially multi-part) Gzip-stream.
     */
    public long getEntryCount() {
        return entries.size();
    }

    /**
     * @return the size in bytes of the compressed input stream data that has been processed so far.
     * Note: This value is only updated when a Gzip entry has been fully processed.
     */
    public long getTotalCompressedSize() {
        return entries.stream().mapToLong(entry -> entry.compressedSize).sum();
    }

    /**
     * @return the size in bytes of the uncompressed output stream data that has been delivered so far.
     * Note: This value is only updated when a Gzip entry has been fully processed.
     */
    public long getTotalUncompressedSize() {
        return entries.size() == 0 ? 0 :
                entries.get(entries.size() - 1).offset + entries.get(entries.size() - 1).uncompressedSize;
    }

    public String toString() {
        return String.format(
                Locale.ENGLISH,
                "GzipReport(status=%s, #entries=%,d, compressed=%,d bytes, uncompressed=%,d bytes, exception=%s)",
                status, entries.size(), getTotalCompressedSize(), getTotalUncompressedSize(), exception);

    }

    public String toString(boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append(toString()).append("\n");
        sb.append("Advice: ").append(getRecommendation()).append("\n");
        if (verbose) {
            sb.append("\nAll entries:");
            entries.forEach(entry -> sb.append("\n").append(entry));
        }
        return sb.toString();
    }

}
