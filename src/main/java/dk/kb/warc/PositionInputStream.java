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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Low-cost wrapper that keeps track of the position in the InputStream.
 * This supports mark/reset if the backing InputStream does.
 */
public class PositionInputStream extends InputStream {
    final InputStream in;
    long markPosition = 0;
    long position = 0;
    boolean closed = false;

    public PositionInputStream(InputStream in) {
        this.in = in;
    }

    public long getPosition() {
        return position;
    }

    public boolean isClosed() {
        return closed;
    }

    /* Delegated methods below */

    public static InputStream nullInputStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int read() throws IOException {
        int i = in.read();
        if (i != -1) {
            position++;
        }
        return i;
    }

    @Override
    public int read(byte[] b) throws IOException {
        int n = in.read(b);
        if (n != -1) {
            position += n;
        }
        return n;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n != -1) {
            position += n;
        }
        return n;
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        byte[] result = in.readAllBytes();
        position += result.length;
        return result;
    }

    @Override
    public byte[] readNBytes(int len) throws IOException {
        byte[] result = in.readNBytes(len);
        position += result.length;
        return result;
    }

    @Override
    public int readNBytes(byte[] b, int off, int len) throws IOException {
        int n = in.readNBytes(b, off, len);
        if (n != -1) {
            position += n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long s = in.skip(n);
        position += s; // Never < 0
        return s;
    }

    @Override
    public void close() throws IOException {
        in.close();
        closed = true;
    }

    @Override
    public void mark(int readlimit) {
        in.mark(readlimit);
        markPosition = position;
    }

    @Override
    public void reset() throws IOException {
        in.reset();
        position = markPosition;
    }

    @Override
    public long transferTo(OutputStream out) throws IOException {
        long n = in.transferTo(out);
        if (n != -1) {
            position += n;
        }
        return n;
    }
}
