/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package dk.kb.warc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.utils.ByteUtils;
import org.apache.commons.compress.utils.CharsetNames;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.InputStreamStatistics;

/**
 * Input stream that decompresses .gz files.
 *
 * <p>This supports decompressing concatenated .gz files which is important
 * when decompressing standalone .gz files.</p>
 *
 * <p>
 * {@link java.util.zip.GZIPInputStream} doesn't decompress concatenated .gz
 * files: it stops after the first member and silently ignores the rest.
 * It doesn't leave the read position to point to the beginning of the next
 * member, which makes it difficult workaround the lack of concatenation
 * support.
 * </p>
 *
 * <p>
 * Instead of using <code>GZIPInputStream</code>, this class has its own .gz
 * container format decoder. The actual decompression is done with
 * {@link java.util.zip.Inflater}.
 * </p>
 *
 * <p>If you use the constructor {@code G3b(in)}
 * or {@code G3b(in, false)} with some {@code
 * InputStream} {@code in} then {@link #read} will return -1 as soon
 * as the first internal member has been read completely. The stream
 * {@code in} will be positioned at the start of the second gzip
 * member if there is one.</p>
 *
 * <p>If you use the constructor {@code G3b(in,
 * true)} with some {@code InputStream} {@code in} then {@link #read}
 * will return -1 once the stream {@code in} has been exhausted. The
 * data read from a stream constructed this way will consist of the
 * concatenated data of all gzip members contained inside {@code
 * in}.</p>
 *
 * @see "https://tools.ietf.org/html/rfc1952"
 *
 * Adapted from org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
 */
public class CountingGZIPInputStream extends CompressorInputStream implements InputStreamStatistics {

    // Header flags
    // private static final int FTEXT = 0x01; // Uninteresting for us
    private static final int FHCRC = 0x02;
    private static final int FEXTRA = 0x04;
    private static final int FNAME = 0x08;
    private static final int FCOMMENT = 0x10;
    private static final int FRESERVED = 0xE0;

    public static final String GARBAGE_AFTER = "Garbage after a valid .gz stream";

    // Changed from CountingInputStream as that implementation does not handle mark/reset properly
    private final PositionInputStream countingStream;

    // Compressed input stream, possibly wrapped in a
    // BufferedInputStream, always wrapped in countingStream above
    private final InputStream in;

    // True if decompressing multi member streams.
    private final boolean decompressConcatenated;

    // Buffer to hold the input data
    private final byte[] buf = new byte[100]; // Old value 8192 meant skipped entries in the debug output

    // Amount of data in buf.
    private int bufUsed;

    // Decompressor
    private Inflater inf = new Inflater(true);

    // CRC32 from uncompressed data
    private final CRC32 crc = new CRC32();

    // True once everything has been decompressed
    private boolean endReached = false;

    // used in no-arg read method
    private final byte[] oneByte = new byte[1];

    private final GzipParameters parameters = new GzipParameters();

    /*
     * Debug information added 2020-08 by Toke Eskildsen <toes@kb.dk>.
     */
    int currentEntryID = -1;
    long currentEntryOrigo = 0;
    long currentEntryInputSize = 0;
    List<Entry> entries = new ArrayList<>();
    static final long TRAILER_SIZE = 8; // Always 8 bytes: http://www.zlib.org/rfc-gzip.html
    long currentHeaderSize = 0;
    private boolean closed = false;
    public static final int DEFAULT_CONTENT_SIZE = 30;
    final byte[] content;
    int contentPos = 0;


    /**
     * Constructs a new input stream that decompresses gzip-compressed data
     * from the specified input stream.
     * <p>
     * This is equivalent to
     * <code>G3b(inputStream, false)</code> and thus
     * will not decompress concatenated .gz files.
     *
     * @param inputStream  the InputStream from which this object should
     *                     be created of
     *
     * @throws IOException if the stream could not be created
     */
    public CountingGZIPInputStream(final InputStream inputStream) throws IOException {
        this(inputStream, false);
    }

    /**
     * Constructs a new input stream that decompresses gzip-compressed data
     * from the specified input stream.
     * <p>
     * If <code>decompressConcatenated</code> is {@code false}:
     * This decompressor might read more input than it will actually use.
     * If <code>inputStream</code> supports <code>mark</code> and
     * <code>reset</code>, then the input position will be adjusted
     * so that it is right after the last byte of the compressed stream.
     * If <code>mark</code> isn't supported, the input position will be
     * undefined.
     *
     * @param inputStream  the InputStream from which this object should
     *                     be created of
     * @param decompressConcatenated
     *                     if true, decompress until the end of the input;
     *                     if false, stop after the first .gz member
     *
     * @throws IOException if the stream could not be created
     */
    public CountingGZIPInputStream(final InputStream inputStream,
                                   final boolean decompressConcatenated)
            throws IOException {
        this(inputStream, decompressConcatenated, DEFAULT_CONTENT_SIZE);
    }
    /**
     * Constructs a new input stream that decompresses gzip-compressed data
     * from the specified input stream.
     * <p>
     * If <code>decompressConcatenated</code> is {@code false}:
     * This decompressor might read more input than it will actually use.
     * If <code>inputStream</code> supports <code>mark</code> and
     * <code>reset</code>, then the input position will be adjusted
     * so that it is right after the last byte of the compressed stream.
     * If <code>mark</code> isn't supported, the input position will be
     * undefined.
     *
     * @param inputStream  the InputStream from which this object should
     *                     be created of
     * @param decompressConcatenated
     *                     if true, decompress until the end of the input;
     *                     if false, stop after the first .gz member
     *
     * @param entryContentSize the number of leading bytes to copy from the content to the created {@link Entry}.
     * @throws IOException if the stream could not be created
     */
    public CountingGZIPInputStream(final InputStream inputStream,
                                   final boolean decompressConcatenated,
                                   int entryContentSize)
            throws IOException {

        in = new PositionInputStream(inputStream.markSupported() ? inputStream : new BufferedInputStream(inputStream));
        countingStream = (PositionInputStream) in;
        content = new byte[entryContentSize]; // Should this be configurable?
/*        countingStream = new PositionInputStream(inputStream);
        // Mark support is strictly needed for concatenated files only,
        // but it's simpler if it is always available.
        if (countingStream.markSupported()) {
            in = countingStream;
        } else {
            in = new BufferedInputStream(countingStream);
        }
  */
        this.decompressConcatenated = decompressConcatenated;
        init(true);
    }

    /**
     * Provides the stream's meta data - may change with each stream
     * when decompressing concatenated streams.
     * @return the stream's meta data
     * @since 1.8
     */
    public GzipParameters getMetaData() {
        return parameters;
    }

    private boolean init(final boolean isFirstMember) throws IOException {
        assert isFirstMember || decompressConcatenated;
        currentEntryID++;
        currentEntryOrigo = countingStream.getPosition();
        currentEntryInputSize = 0;
        contentPos = 0;
        Arrays.fill(content, (byte) 0);

        // Check the magic bytes without a possibility of EOFException.
        final int magic0 = in.read();

        // If end of input was reached after decompressing at least
        // one .gz member, we have reached the end of the file successfully.
        if (magic0 == -1 && !isFirstMember) {
            return false;
        }

        if (magic0 != 31 || in.read() != 139) {
            throw new ZipException(isFirstMember
                                  ? "Input is not in the .gz format"
                                  : GARBAGE_AFTER);
        }

        // Parsing the rest of the header may throw EOFException.
        final DataInput inData = new DataInputStream(in);
        final int method = inData.readUnsignedByte();
        if (method != Deflater.DEFLATED) {
            throw new ZipException("Unsupported compression method "
                                  + method + " in the .gz header");
        }

        final int flg = inData.readUnsignedByte();
        if ((flg & FRESERVED) != 0) {
            throw new ZipException(
                    "Reserved flags are set in the .gz header");
        }

        parameters.setModificationTime(ByteUtils.fromLittleEndian(inData, 4) * 1000);
        switch (inData.readUnsignedByte()) { // extra flags
        case 2:
            parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
            break;
        case 4:
            parameters.setCompressionLevel(Deflater.BEST_SPEED);
            break;
        default:
            // ignored for now
            break;
        }
        parameters.setOperatingSystem(inData.readUnsignedByte());

        // Extra field, ignored
        if ((flg & FEXTRA) != 0) {
            int xlen = inData.readUnsignedByte();
            xlen |= inData.readUnsignedByte() << 8;

            // This isn't as efficient as calling in.skip would be,
            // but it's lazier to handle unexpected end of input this way.
            // Most files don't have an extra field anyway.
            while (xlen-- > 0) {
                inData.readUnsignedByte();
            }
        }

        // Original file name
        if ((flg & FNAME) != 0) {
            parameters.setFilename(new String(readToNull(inData),
                                              CharsetNames.ISO_8859_1));
        }

        // Comment
        if ((flg & FCOMMENT) != 0) {
            parameters.setComment(new String(readToNull(inData),
                                             CharsetNames.ISO_8859_1));
        }

        // Header "CRC16" which is actually a truncated CRC32 (which isn't
        // as good as real CRC16). I don't know if any encoder implementation
        // sets this, so it's not worth trying to verify it. GNU gzip 1.4
        // doesn't support this field, but zlib seems to be able to at least
        // skip over it.
        if ((flg & FHCRC) != 0) {
            inData.readShort();
        }

        // Reset
        inf.reset();
        crc.reset();
        return true;
    }

    private static byte[] readToNull(final DataInput inData) throws IOException {
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            int b = 0;
            while ((b = inData.readUnsignedByte()) != 0x00) { // NOPMD NOSONAR
                bos.write(b);
            }
            return bos.toByteArray();
        }
    }

    @Override
    public int read() throws IOException {
        return read(oneByte, 0, 1) == -1 ? -1 : oneByte[0] & 0xFF;
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.1
     */
    @Override
    public int read(final byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (endReached) {
            return -1;
        }

        int size = 0;

        while (len > 0) {
            if (inf.needsInput()) {
                // Remember the current position because we may need to
                // rewind after reading too much input.
                in.mark(buf.length);

                bufUsed = in.read(buf);
                if (bufUsed == -1) {
                    throw new EOFException();
                }

                inf.setInput(buf, 0, bufUsed);
            }

            int ret;
            try {
                ret = inf.inflate(b, off, len);
            } catch (final DataFormatException e) { // NOSONAR
                throw new IOException("Gzip-compressed data is corrupt");
            }

            // Update snippet
            if (ret > 0 && contentPos < content.length) {
                int copyLength = Math.min(content.length-contentPos, ret);
                System.arraycopy(b, off, content, contentPos, copyLength);
                contentPos += copyLength;
            }

            crc.update(b, off, ret);
            off += ret;
            len -= ret;
            size += ret;
            count(ret);

            if (inf.finished()) {
                // We may have read too many bytes. Rewind the read
                // position to match the actual amount used.
                in.reset();
                final int skipAmount = bufUsed - inf.getRemaining();
                if (IOUtils.skip(in, skipAmount) != skipAmount) {
                    throw new IOException();
                }

                bufUsed = 0;

                final DataInput inData = new DataInputStream(in);

                // CRC32
                final long crcStored = ByteUtils.fromLittleEndian(inData, 4);

                if (crcStored != crc.getValue()) {
                    throw new IOException("Gzip-compressed data is corrupt "
                                          + "(CRC32 error)");
                }

                // Uncompressed size modulo 2^32 (ISIZE in the spec)
                final long isize = ByteUtils.fromLittleEndian(inData, 4);

                if (isize != (inf.getBytesWritten() & 0xffffffffL)) {
                    throw new IOException("Gzip-compressed data is corrupt"
                                          + "(uncompressed size mismatch)");
                }
                currentEntryInputSize = countingStream.getPosition()-currentEntryOrigo;
                byte[] trimmedCopy = new byte[contentPos];
                System.arraycopy(content, 0, trimmedCopy, 0, contentPos);
                entries.add(new Entry(currentEntryID, currentEntryOrigo, currentEntryInputSize, isize,
                                      trimmedCopy));

                // See if this is the end of the file.
                if (!decompressConcatenated || !init(false)) {
                    inf.end();
                    inf = null;
                    endReached = true;
                    return size == 0 ? -1 : size;
                }
            }
        }
        return size;
    }

    /**
     * Checks if the signature matches what is expected for a .gz file.
     *
     * @param signature the bytes to check
     * @param length    the number of bytes to check
     * @return          true if this is a .gz stream, false otherwise
     *
     * @since 1.1
     */
    public static boolean matches(final byte[] signature, final int length) {
        return length >= 2 && signature[0] == 31 && signature[1] == -117;
    }

    /**
     * Closes the input stream (unless it is System.in).
     *
     * @since 1.2
     */
    @Override
    public void close() throws IOException {
        if (inf != null) {
            inf.end();
            inf = null;
        }

        if (this.in != System.in) {
            this.in.close();
        }
        closed = true;
    }

    /**
     * @since 1.17
     */
    @Override
    public long getCompressedCount() {
        return countingStream.getPosition();
    }

    /* Extensions to the original GzipCompressorInputStream */

    /**
     * Empty the stream and return the number of read (uncompressed) bytes.
     * The stream will be closed after being emptied.
     * @return the number of extracted uncompressed bytes during the empty process.
     * @throws IOException if a stream problem occurred.
     */
    public long empty() throws IOException {
        long readBytes = 0;
        long skipped;
        while ((skipped = skip(1000)) > 0) {
            readBytes += skipped;
        }
        close();
        return readBytes;
    }

    /**
     * @return all the entries encountered in this Gzip-stream.
     */
    public List<Entry> getEntries() {
        return entries;
    }

    /**
     * @return the size in bytes of the compressed input stream data that has been processed so far.
     *         Note: This value is only updated when a Gzip entry has been fully processed.
     */
    public long getTotalCompressedSize() {
        return entries.stream().mapToLong(entry -> entry.compressedSize).sum();
    }

    /**
     * @return the size in bytes of the uncompressed output stream data that has been delivered so far.
     *         Note: This value is only updated when a Gzip entry has been fully processed.
     */
    public long getTotalUncompressedSize() {
        return entries.size() == 0 ? 0 :
                entries.get(entries.size()-1).offset + entries.get(entries.size()-1).uncompressedSize;
    }

    public static class Entry {
        final public long id;
        final public long offset;
        final public long compressedSize;
        final public long uncompressedSize;
        final public byte[] contentSnippet;

        public Entry(long id, long offset, long compressedSize, long uncompressedSize, byte[] contentSnippet) {
            this.id = id;
            this.offset = offset;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.contentSnippet = contentSnippet;
        }

        public String getASCIIContentSnippet() {
            StringBuilder sb = new StringBuilder(contentSnippet.length * 3);
            for (byte b : contentSnippet) {
                int c = 0xFF & b;
                if (c > 0 && c <= 127) {
                    sb.append(Character.toString(c));
                }
            }
            return sb.toString();
        }
        public String getContentSnippet() {
            StringBuilder sb = new StringBuilder(contentSnippet.length * 3);
            for (byte b : contentSnippet) {
                int c = 0xFF & b;
                if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c < 32 || c > 127) {
                    sb.append("\\x").append(Integer.toHexString(c).toUpperCase(Locale.ENGLISH));
                } else {
                    sb.append(Character.toString(c));
                }
            }
            return sb.toString();
        }

        public String toString() {
            return String.format(Locale.ENGLISH, "Entry #%d: source(%,d->%,d), " +
                                              "compressed=%,d bytes, uncompressed=%,d bytes",
                                 id, offset, offset + compressedSize, compressedSize, uncompressedSize);
        }
    }

    public String toString() {
        return String.format(Locale.ENGLISH,
                             "CountingGZIPInputStream(closed=%b, compressed=%,d bytes, " +
                             "uncompressed=%,d bytes, entries=%,d)",
                closed, getTotalCompressedSize(), getTotalUncompressedSize(), entries.size());
    }

}
