/*
 * Copyright (c) 1996, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package dk.kb.warc;

import java.io.SequenceInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * This class implements a stream filter for reading compressed data in
 * the GZIP file format.
 *
 * Extended in 2020 by Toke Eskildsen to provide information on concatenated Gzip-streams.
 *
 * @see         InflaterInputStream
 * @author      David Connelly
 * @since 1.1
 *
 */
public
class CountingGZIPInputStreamOld extends InflaterInputStream {
    /**
     * CRC-32 for uncompressed data.
     */
    protected CRC32 crc = new CRC32();

    /**
     * Indicates end of input stream.
     */
    protected boolean eos;

    private boolean closed = false;

    /**
     * Entry counter (starting from 0) for multi-part Gzip-streams.
     */
    int currentEntryID = 0;
    long currentEntryOrigo = 0;
    long currentEntryInputSize = 0;
    long currentEntryOutputSize = 0;
    List<Entry> entries = new ArrayList<>();

    static final long TRAILER_SIZE = 8; // Always 8 bytes: http://www.zlib.org/rfc-gzip.html

    long currentHeaderSize = 0;

    /**
     * Check to make sure that this stream has not been closed
     */
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    /**
     * Creates a new input stream with the specified buffer size.
     * @param in the input stream
     * @param size the input buffer size
     *
     * @exception ZipException if a GZIP format error has occurred or the
     *                         compression method used is unsupported
     * @exception IOException if an I/O error has occurred
     * @exception IllegalArgumentException if {@code size <= 0}
     */
    public CountingGZIPInputStreamOld(InputStream in, int size) throws IOException {
        super(in, new Inflater(true), size);
//        usesDefaultInflater = true;
        readHeader(in);
    }

    /**
     * Creates a new input stream with a default buffer size.
     * @param in the input stream
     *
     * @exception ZipException if a GZIP format error has occurred or the
     *                         compression method used is unsupported
     * @exception IOException if an I/O error has occurred
     */
    public CountingGZIPInputStreamOld(InputStream in) throws IOException {
        this(in, 512);
    }

    /**
     * Reads uncompressed data into an array of bytes. If <code>len</code> is not
     * zero, the method will block until some input can be decompressed; otherwise,
     * no bytes are read and <code>0</code> is returned.
     * @param buf the buffer into which the data is read
     * @param off the start offset in the destination array <code>b</code>
     * @param len the maximum number of bytes read
     * @return  the actual number of bytes read, or -1 if the end of the
     *          compressed input stream is reached
     *
     * @exception  NullPointerException If <code>buf</code> is <code>null</code>.
     * @exception  IndexOutOfBoundsException If <code>off</code> is negative,
     * <code>len</code> is negative, or <code>len</code> is greater than
     * <code>buf.length - off</code>
     * @exception ZipException if the compressed input data is corrupt.
     * @exception IOException if an I/O error has occurred.
     *
     */
    public int read(byte[] buf, int off, int len) throws IOException {
        ensureOpen();
        if (eos) {
            return -1;
        }
        int n = super.read(buf, off, len);
        if (n == -1) {
            currentEntryInputSize = inf.getBytesRead(); // inf is reset for each separate Gzip entry
            // The header and the trailer are not recorded in the InflaterInputStream as they are not DEFLATE data
            long totalEntrySize = currentHeaderSize + currentEntryInputSize + TRAILER_SIZE;
            Entry finished = new Entry(currentEntryID, currentEntryOrigo, totalEntrySize, currentEntryOutputSize);
            entries.add(finished);
//            System.out.println(finished);
            if (readTrailer()) {
                eos = true;
            } else {
                // There are more entries
                currentEntryID++;
                currentEntryOrigo = currentEntryOrigo + totalEntrySize;
                currentEntryOutputSize = 0;

                return this.read(buf, off, len);
            }
        } else {
            currentEntryOutputSize += n;
            crc.update(buf, off, n);
        }
        return n;
    }

    /**
     * Closes this input stream and releases any system resources associated
     * with the stream.
     * @exception IOException if an I/O error has occurred
     */
    public void close() throws IOException {
        if (!closed) {
            superCloseAdjusted();
            eos = true;
            closed = true;
        }
    }

    public void superCloseAdjusted() throws IOException {
        if (!closed) {
            // usesDefaultInflater is package private so we cannot set it to true and thus we need to work around it
//            if (usesDefaultInflater)
//                inf.end();
            in.close();
            closed = true;
        }
    }

    /**
     * GZIP header magic number.
     */
    public static final int GZIP_MAGIC = 0x8b1f;

    /*
     * File header flags.
     */
    private static final int FTEXT      = 1;    // Extra text
    private static final int FHCRC      = 2;    // Header CRC
    private static final int FEXTRA     = 4;    // Extra field
    private static final int FNAME      = 8;    // File name
    private static final int FCOMMENT   = 16;   // File comment

    /*
     * Reads GZIP member header and returns the total byte number
     * of this member header.
     */
    private int readHeader(InputStream this_in) throws IOException {
        CheckedInputStream in = new CheckedInputStream(this_in, crc);
        crc.reset();
        // Check header magic
        if (readUShort(in) != GZIP_MAGIC) {
            throw new ZipException("Not in GZIP format");
        }
        // Check compression method
        if (readUByte(in) != 8) {
            throw new ZipException("Unsupported compression method");
        }
        // Read flags
        int flg = readUByte(in);
        // Skip MTIME, XFL, and OS fields
        skipBytes(in, 6);
        int n = 2 + 2 + 6;
        // Skip optional extra field
        if ((flg & FEXTRA) == FEXTRA) {
            int m = readUShort(in);
            skipBytes(in, m);
            n += m + 2;
        }
        // Skip optional file name
        if ((flg & FNAME) == FNAME) {
            do {
                n++;
            } while (readUByte(in) != 0);
        }
        // Skip optional file comment
        if ((flg & FCOMMENT) == FCOMMENT) {
            do {
                n++;
            } while (readUByte(in) != 0);
        }
        // Check optional header CRC
        if ((flg & FHCRC) == FHCRC) {
            int v = (int)crc.getValue() & 0xffff;
            if (readUShort(in) != v) {
                throw new ZipException("Corrupt GZIP header");
            }
            n += 2;
        }
        crc.reset();
        currentHeaderSize = n;
        return n;
    }

    /*
     * Reads GZIP member trailer and returns true if the eos
     * reached, false if there are more (concatenated gzip
     * data set)
     */
    private boolean readTrailer() throws IOException {
        InputStream in = this.in;
        int n = inf.getRemaining();
        if (n > 0) {
            in = new SequenceInputStream(
                        new ByteArrayInputStream(buf, len - n, n),
                        new FilterInputStream(in) {
                            public void close() throws IOException {}
                        });
        }
        // Uses left-to-right evaluation order
        if ((readUInt(in) != crc.getValue()) ||
            // rfc1952; ISIZE is the input size modulo 2^32
            (readUInt(in) != (inf.getBytesWritten() & 0xffffffffL)))
            throw new ZipException("Corrupt GZIP trailer");

        // If there are more bytes available in "in" or
        // the leftover in the "inf" is > 26 bytes:
        // this.trailer(8) + next.header.min(10) + next.trailer(8)
        // try concatenated case
        if (this.in.available() > 0 || n > 26) {
            int m = 8;                  // this.trailer
            try {
                m += readHeader(in);    // next.header
            } catch (IOException ze) {
                return true;  // ignore any malformed, do nothing
            }
            inf.reset();
            if (n > m)
                inf.setInput(buf, len - n + m, n - m);
            return false;
        }
        return true;
    }

    /*
     * Reads unsigned integer in Intel byte order.
     */
    private long readUInt(InputStream in) throws IOException {
        long s = readUShort(in);
        return ((long)readUShort(in) << 16) | s;
    }

    /*
     * Reads unsigned short in Intel byte order.
     */
    private int readUShort(InputStream in) throws IOException {
        int b = readUByte(in);
        return (readUByte(in) << 8) | b;
    }

    /*
     * Reads unsigned byte.
     */
    private int readUByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException();
        }
        if (b < -1 || b > 255) {
            // Report on this.in, not argument in; see read{Header, Trailer}.
            throw new IOException(this.in.getClass().getName()
                + ".read() returned value out of range -1..255: " + b);
        }
        return b;
    }

    private byte[] tmpbuf = new byte[128];

    /*
     * Skips bytes of input data blocking until all bytes are skipped.
     * Does not assume that the input stream is capable of seeking.
     */
    private void skipBytes(InputStream in, int n) throws IOException {
        while (n > 0) {
            int len = in.read(tmpbuf, 0, n < tmpbuf.length ? n : tmpbuf.length);
            if (len == -1) {
                throw new EOFException();
            }
            n -= len;
        }
    }

    /* Extensions to the original GZIPInputStream */

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


        public Entry(long id, long offset, long compressedSize, long uncompressedSize) {
            this.id = id;
            this.offset = offset;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
        }

        public String toString() {
            return String.format(Locale.ROOT, "Entry #%d: source(%d->%d), compressed=%d bytes, uncompressed=%d bytes",
                                 id, offset, offset + compressedSize, compressedSize, uncompressedSize);
        }
    }

    public String toString() {
        return String.format(Locale.ROOT,
                             "CountingGZIPInputStream(closed=%b, compressed=%d bytes, uncompressed=%d bytes, entries=%d)",
                closed, getTotalCompressedSize(), getTotalUncompressedSize(), entries.size());
    }

}
