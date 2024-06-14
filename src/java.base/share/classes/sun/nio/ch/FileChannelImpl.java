/*
 * Copyright (c) 2000, 2020, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner.Cleanable;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.ExtendedMapMode;
import jdk.internal.misc.Unsafe;
import jdk.internal.misc.VM;
import jdk.internal.misc.VM.BufferPool;
import jdk.internal.ref.Cleaner;
import jdk.internal.ref.CleanerFactory;

import jdk.internal.access.foreign.UnmapperProxy;

public class FileChannelImpl
    extends FileChannel
{
    // Memory allocation size for mapping buffers
    private static final long allocationGranularity;

    // Access to FileDescriptor internals
    private static final JavaIOFileDescriptorAccess fdAccess =
        SharedSecrets.getJavaIOFileDescriptorAccess();

    // Used to make native read and write calls
    private final FileDispatcher nd;

    // File descriptor
    private final FileDescriptor fd;

    // File access mode (immutable)
    private final boolean writable;
    private final boolean readable;

    // Required to prevent finalization of creating stream (immutable)
    private final Object parent;

    // The path of the referenced file
    // (null if the parent stream is created with a file descriptor)
    private final String path;

    // Thread-safe set of IDs of native threads, for signalling
    private final NativeThreadSet threads = new NativeThreadSet(2);

    // Lock for operations involving position and size
    private final Object positionLock = new Object();

    // blocking operations are not interruptible
    private volatile boolean uninterruptible;

    // DirectIO flag
    private final boolean direct;

    // IO alignment value for DirectIO
    private final int alignment;

    // Cleanable with an action which closes this channel's file descriptor
    private final Cleanable closer;

    private static class Closer implements Runnable {
        private final FileDescriptor fd;

        Closer(FileDescriptor fd) {
            this.fd = fd;
        }

        public void run() {
            try {
                fdAccess.close(fd);
            } catch (IOException ioe) {
                // Rethrow as unchecked so the exception can be propagated as needed
                throw new UncheckedIOException("close", ioe);
            }
        }
    }

    private FileChannelImpl(FileDescriptor fd, String path, boolean readable,
                            boolean writable, boolean direct, Object parent)
    {
        this.fd = fd;
        this.readable = readable;
        this.writable = writable;
        this.parent = parent;
        this.path = path;
        this.direct = direct;
        this.nd = new FileDispatcherImpl();
        if (direct) {
            assert path != null;
            this.alignment = nd.setDirectIO(fd, path);
        } else {
            this.alignment = -1;
        }

        // Register a cleaning action if and only if there is no parent
        // as the parent will take care of closing the file descriptor.
        // FileChannel is used by the LambdaMetaFactory so a lambda cannot
        // be used here hence we use a nested class instead.
        this.closer = parent != null ? null :
            CleanerFactory.cleaner().register(this, new Closer(fd));
    }

    // Used by FileInputStream.getChannel(), FileOutputStream.getChannel
    // and RandomAccessFile.getChannel()
    public static FileChannel open(FileDescriptor fd, String path,
                                   boolean readable, boolean writable,
                                   boolean direct, Object parent)
    {
        return new FileChannelImpl(fd, path, readable, writable, direct, parent);
    }

    private void ensureOpen() throws IOException {
        if (!isOpen())
            throw new ClosedChannelException();
    }

    public void setUninterruptible() {
        uninterruptible = true;
    }

    private void beginBlocking() {
        if (!uninterruptible) begin();
    }

    private void endBlocking(boolean completed) throws AsynchronousCloseException {
        if (!uninterruptible) end(completed);
    }

    // -- Standard channel operations --

    protected void implCloseChannel() throws IOException {
        if (!fd.valid())
            return; // nothing to do

        // Release and invalidate any locks that we still hold
        if (fileLockTable != null) {
            for (FileLock fl: fileLockTable.removeAll()) {
                synchronized (fl) {
                    if (fl.isValid()) {
                        nd.release(fd, fl.position(), fl.size());
                        ((FileLockImpl)fl).invalidate();
                    }
                }
            }
        }

        // signal any threads blocked on this channel
        threads.signalAndWait();

        if (parent != null) {

            // Close the fd via the parent stream's close method.  The parent
            // will reinvoke our close method, which is defined in the
            // superclass AbstractInterruptibleChannel, but the isOpen logic in
            // that method will prevent this method from being reinvoked.
            //
            ((java.io.Closeable)parent).close();
        } else if (closer != null) {
            // Perform the cleaning action so it is not redone when
            // this channel becomes phantom reachable.
            try {
                closer.clean();
            } catch (UncheckedIOException uioe) {
                throw uioe.getCause();
            }
        } else {
            fdAccess.close(fd);
        }

    }

    public int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        if (!readable)
            throw new NonReadableChannelException();
        synchronized (positionLock) {
            if (direct)
                Util.checkChannelPositionAligned(position(), alignment);
            int n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return 0;
                do {
                    n = IOUtil.read(fd, dst, -1, direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    public long read(ByteBuffer[] dsts, int offset, int length)
        throws IOException
    {
        Objects.checkFromIndexSize(offset, length, dsts.length);
        ensureOpen();
        if (!readable)
            throw new NonReadableChannelException();
        synchronized (positionLock) {
            if (direct)
                Util.checkChannelPositionAligned(position(), alignment);
            long n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return 0;
                do {
                    n = IOUtil.read(fd, dsts, offset, length,
                            direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    public int write(ByteBuffer src) throws IOException {
        ensureOpen();
        if (!writable)
            throw new NonWritableChannelException();
        synchronized (positionLock) {
            if (direct)
                Util.checkChannelPositionAligned(position(), alignment);
            int n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return 0;
                do {
                    n = IOUtil.write(fd, src, -1, direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    public long write(ByteBuffer[] srcs, int offset, int length)
        throws IOException
    {
        Objects.checkFromIndexSize(offset, length, srcs.length);
        ensureOpen();
        if (!writable)
            throw new NonWritableChannelException();
        synchronized (positionLock) {
            if (direct)
                Util.checkChannelPositionAligned(position(), alignment);
            long n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return 0;
                do {
                    n = IOUtil.write(fd, srcs, offset, length,
                            direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    // -- Other operations --

    public long position() throws IOException {
        ensureOpen();
        synchronized (positionLock) {
            long p = -1;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return 0;
                boolean append = fdAccess.getAppend(fd);
                do {
                    // in append-mode then position is advanced to end before writing
                    p = (append) ? nd.size(fd) : nd.seek(fd, -1);
                } while ((p == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(p);
            } finally {
                threads.remove(ti);
                endBlocking(p > -1);
                assert IOStatus.check(p);
            }
        }
    }

    public FileChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0)
            throw new IllegalArgumentException();
        synchronized (positionLock) {
            long p = -1;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return null;
                do {
                    p = nd.seek(fd, newPosition);
                } while ((p == IOStatus.INTERRUPTED) && isOpen());
                return this;
            } finally {
                threads.remove(ti);
                endBlocking(p > -1);
                assert IOStatus.check(p);
            }
        }
    }

    public long size() throws IOException {
        ensureOpen();
        synchronized (positionLock) {
            long s = -1;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return -1;
                do {
                    s = nd.size(fd);
                } while ((s == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(s);
            } finally {
                threads.remove(ti);
                endBlocking(s > -1);
                assert IOStatus.check(s);
            }
        }
    }

    public FileChannel truncate(long newSize) throws IOException {
        ensureOpen();
        if (newSize < 0)
            throw new IllegalArgumentException("Negative size");
        if (!writable)
            throw new NonWritableChannelException();
        synchronized (positionLock) {
            int rv = -1;
            long p = -1;
            int ti = -1;
            long rp = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return null;

                // get current size
                long size;
                do {
                    size = nd.size(fd);
                } while ((size == IOStatus.INTERRUPTED) && isOpen());
                if (!isOpen())
                    return null;

                // get current position
                do {
                    p = nd.seek(fd, -1);
                } while ((p == IOStatus.INTERRUPTED) && isOpen());
                if (!isOpen())
                    return null;
                assert p >= 0;

                // truncate file if given size is less than the current size
                if (newSize < size) {
                    do {
                        rv = nd.truncate(fd, newSize);
                    } while ((rv == IOStatus.INTERRUPTED) && isOpen());
                    if (!isOpen())
                        return null;
                }

                // if position is beyond new size then adjust it
                if (p > newSize)
                    p = newSize;
                do {
                    rp = nd.seek(fd, p);
                } while ((rp == IOStatus.INTERRUPTED) && isOpen());
                return this;
            } finally {
                threads.remove(ti);
                endBlocking(rv > -1);
                assert IOStatus.check(rv);
            }
        }
    }

    public void force(boolean metaData) throws IOException {
        ensureOpen();
        int rv = -1;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return;
            do {
                // metaData = true  调用 fsync
                // metaData = false 调用 fdatasync
                rv = nd.force(fd, metaData);
            } while ((rv == IOStatus.INTERRUPTED) && isOpen());
        } finally {
            threads.remove(ti);
            endBlocking(rv > -1);
            assert IOStatus.check(rv);
        }
    }

    // Assume at first that the underlying kernel supports sendfile();
    // set this to false if we find out later that it doesn't
    //
    private static volatile boolean transferSupported = true;

    // Assume that the underlying kernel sendfile() will work if the target
    // fd is a pipe; set this to false if we find out later that it doesn't
    //
    private static volatile boolean pipeSupported = true;

    // Assume that the underlying kernel sendfile() will work if the target
    // fd is a file; set this to false if we find out later that it doesn't
    //
    private static volatile boolean fileSupported = true;

    private long transferToDirectlyInternal(long position, int icount,
                                            WritableByteChannel target,
                                            FileDescriptor targetFD)
        throws IOException
    {
        assert !nd.transferToDirectlyNeedsPositionLock() ||
               Thread.holdsLock(positionLock);

        long n = -1;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return -1;
            do {
                n = transferTo0(fd, position, icount, targetFD);
            } while ((n == IOStatus.INTERRUPTED) && isOpen());
            if (n == IOStatus.UNSUPPORTED_CASE) {
                if (target instanceof SinkChannelImpl)
                    pipeSupported = false;
                if (target instanceof FileChannelImpl)
                    fileSupported = false;
                return IOStatus.UNSUPPORTED_CASE;
            }
            if (n == IOStatus.UNSUPPORTED) {
                // Don't bother trying again
                transferSupported = false;
                return IOStatus.UNSUPPORTED;
            }
            return IOStatus.normalize(n);
        } finally {
            threads.remove(ti);
            end (n > -1);
        }
    }

    private long transferToDirectly(long position, int icount,
                                    WritableByteChannel target)
        throws IOException
    {
        if (!transferSupported)
            return IOStatus.UNSUPPORTED;

        FileDescriptor targetFD = null;
        if (target instanceof FileChannelImpl) {
            if (!fileSupported)
                return IOStatus.UNSUPPORTED_CASE;
            targetFD = ((FileChannelImpl)target).fd;
        } else if (target instanceof SelChImpl) {
            // Direct transfer to pipe causes EINVAL on some configurations
            if ((target instanceof SinkChannelImpl) && !pipeSupported)
                return IOStatus.UNSUPPORTED_CASE;

            // Platform-specific restrictions. Now there is only one:
            // Direct transfer to non-blocking channel could be forbidden
            SelectableChannel sc = (SelectableChannel)target;
            if (!nd.canTransferToDirectly(sc))
                return IOStatus.UNSUPPORTED_CASE;

            targetFD = ((SelChImpl)target).getFD();
        }

        if (targetFD == null)
            return IOStatus.UNSUPPORTED;
        int thisFDVal = IOUtil.fdVal(fd);
        int targetFDVal = IOUtil.fdVal(targetFD);
        if (thisFDVal == targetFDVal) // Not supported on some configurations
            return IOStatus.UNSUPPORTED;

        if (nd.transferToDirectlyNeedsPositionLock()) {
            synchronized (positionLock) {
                long pos = position();
                try {
                    return transferToDirectlyInternal(position, icount,
                                                      target, targetFD);
                } finally {
                    position(pos);
                }
            }
        } else {
            return transferToDirectlyInternal(position, icount, target, targetFD);
        }
    }

    // Maximum size to map when using a mapped buffer
    private static final long MAPPED_TRANSFER_SIZE = 8L*1024L*1024L;

    private long transferToTrustedChannel(long position, long count,
                                          WritableByteChannel target)
        throws IOException
    {
        boolean isSelChImpl = (target instanceof SelChImpl);
        if (!((target instanceof FileChannelImpl) || isSelChImpl))
            return IOStatus.UNSUPPORTED;

        // Trusted target: Use a mapped buffer
        long remaining = count;
        while (remaining > 0L) {
            long size = Math.min(remaining, MAPPED_TRANSFER_SIZE);
            try {
                MappedByteBuffer dbb = map(MapMode.READ_ONLY, position, size);
                try {
                    // ## Bug: Closing this channel will not terminate the write
                    int n = target.write(dbb);
                    assert n >= 0;
                    remaining -= n;
                    if (isSelChImpl) {
                        // one attempt to write to selectable channel
                        break;
                    }
                    assert n > 0;
                    position += n;
                } finally {
                    unmap(dbb);
                }
            } catch (ClosedByInterruptException e) {
                // target closed by interrupt as ClosedByInterruptException needs
                // to be thrown after closing this channel.
                assert !target.isOpen();
                try {
                    close();
                } catch (Throwable suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } catch (IOException ioe) {
                // Only throw exception if no bytes have been written
                if (remaining == count)
                    throw ioe;
                break;
            }
        }
        return count - remaining;
    }

    private long transferToArbitraryChannel(long position, int icount,
                                            WritableByteChannel target)
        throws IOException
    {
        // Untrusted target: Use a newly-erased buffer
        int c = Math.min(icount, TRANSFER_SIZE);
        ByteBuffer bb = ByteBuffer.allocate(c);
        long tw = 0;                    // Total bytes written
        long pos = position;
        try {
            while (tw < icount) {
                bb.limit(Math.min((int)(icount - tw), TRANSFER_SIZE));
                int nr = read(bb, pos);
                if (nr <= 0)
                    break;
                bb.flip();
                // ## Bug: Will block writing target if this channel
                // ##      is asynchronously closed
                int nw = target.write(bb);
                tw += nw;
                if (nw != nr)
                    break;
                pos += nw;
                bb.clear();
            }
            return tw;
        } catch (IOException x) {
            if (tw > 0)
                return tw;
            throw x;
        }
    }

    public long transferTo(long position, long count,
                           WritableByteChannel target)
        throws IOException
    {
        ensureOpen();
        if (!target.isOpen())
            throw new ClosedChannelException();
        if (!readable)
            throw new NonReadableChannelException();
        if (target instanceof FileChannelImpl &&
            !((FileChannelImpl)target).writable)
            throw new NonWritableChannelException();
        if ((position < 0) || (count < 0))
            throw new IllegalArgumentException();
        long sz = size();
        if (position > sz)
            return 0;
        int icount = (int)Math.min(count, Integer.MAX_VALUE);
        if ((sz - position) < icount)
            icount = (int)(sz - position);

        long n;

        // Attempt a direct transfer, if the kernel supports it
        if ((n = transferToDirectly(position, icount, target)) >= 0)
            return n;

        // Attempt a mapped transfer, but only to trusted channel types
        if ((n = transferToTrustedChannel(position, icount, target)) >= 0)
            return n;

        // Slow path for untrusted targets
        return transferToArbitraryChannel(position, icount, target);
    }

    private long transferFromFileChannel(FileChannelImpl src,
                                         long position, long count)
        throws IOException
    {
        if (!src.readable)
            throw new NonReadableChannelException();
        synchronized (src.positionLock) {
            long pos = src.position();
            long max = Math.min(count, src.size() - pos);

            long remaining = max;
            long p = pos;
            while (remaining > 0L) {
                long size = Math.min(remaining, MAPPED_TRANSFER_SIZE);
                // ## Bug: Closing this channel will not terminate the write
                MappedByteBuffer bb = src.map(MapMode.READ_ONLY, p, size);
                try {
                    long n = write(bb, position);
                    assert n > 0;
                    p += n;
                    position += n;
                    remaining -= n;
                } catch (IOException ioe) {
                    // Only throw exception if no bytes have been written
                    if (remaining == max)
                        throw ioe;
                    break;
                } finally {
                    unmap(bb);
                }
            }
            long nwritten = max - remaining;
            src.position(pos + nwritten);
            return nwritten;
        }
    }

    private static final int TRANSFER_SIZE = 8192;

    private long transferFromArbitraryChannel(ReadableByteChannel src,
                                              long position, long count)
        throws IOException
    {
        // Untrusted target: Use a newly-erased buffer
        int c = (int)Math.min(count, TRANSFER_SIZE);
        ByteBuffer bb = ByteBuffer.allocate(c);
        long tw = 0;                    // Total bytes written
        long pos = position;
        try {
            while (tw < count) {
                bb.limit((int)Math.min((count - tw), (long)TRANSFER_SIZE));
                // ## Bug: Will block reading src if this channel
                // ##      is asynchronously closed
                int nr = src.read(bb);
                if (nr <= 0)
                    break;
                bb.flip();
                int nw = write(bb, pos);
                tw += nw;
                if (nw != nr)
                    break;
                pos += nw;
                bb.clear();
            }
            return tw;
        } catch (IOException x) {
            if (tw > 0)
                return tw;
            throw x;
        }
    }

    public long transferFrom(ReadableByteChannel src,
                             long position, long count)
        throws IOException
    {
        ensureOpen();
        if (!src.isOpen())
            throw new ClosedChannelException();
        if (!writable)
            throw new NonWritableChannelException();
        if ((position < 0) || (count < 0))
            throw new IllegalArgumentException();
        if (position > size())
            return 0;
        if (src instanceof FileChannelImpl)
           return transferFromFileChannel((FileChannelImpl)src,
                                          position, count);

        return transferFromArbitraryChannel(src, position, count);
    }

    public int read(ByteBuffer dst, long position) throws IOException {
        if (dst == null)
            throw new NullPointerException();
        if (position < 0)
            throw new IllegalArgumentException("Negative position");
        ensureOpen();
        if (!readable)
            throw new NonReadableChannelException();
        if (direct)
            Util.checkChannelPositionAligned(position, alignment);
        if (nd.needsPositionLock()) {
            synchronized (positionLock) {
                return readInternal(dst, position);
            }
        } else {
            return readInternal(dst, position);
        }
    }

    private int readInternal(ByteBuffer dst, long position) throws IOException {
        assert !nd.needsPositionLock() || Thread.holdsLock(positionLock);
        int n = 0;
        int ti = -1;

        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return -1;
            do {
                n = IOUtil.read(fd, dst, position, direct, alignment, nd);
            } while ((n == IOStatus.INTERRUPTED) && isOpen());
            return IOStatus.normalize(n);
        } finally {
            threads.remove(ti);
            endBlocking(n > 0);
            assert IOStatus.check(n);
        }
    }

    public int write(ByteBuffer src, long position) throws IOException {
        if (src == null)
            throw new NullPointerException();
        if (position < 0)
            throw new IllegalArgumentException("Negative position");
        ensureOpen();
        if (!writable)
            throw new NonWritableChannelException();
        if (direct)
            Util.checkChannelPositionAligned(position, alignment);
        if (nd.needsPositionLock()) {
            synchronized (positionLock) {
                return writeInternal(src, position);
            }
        } else {
            return writeInternal(src, position);
        }
    }

    private int writeInternal(ByteBuffer src, long position) throws IOException {
        assert !nd.needsPositionLock() || Thread.holdsLock(positionLock);
        int n = 0;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return -1;
            do {
                n = IOUtil.write(fd, src, position, direct, alignment, nd);
            } while ((n == IOStatus.INTERRUPTED) && isOpen());
            return IOStatus.normalize(n);
        } finally {
            threads.remove(ti);
            endBlocking(n > 0);
            assert IOStatus.check(n);
        }
    }


    // -- Memory-mapped buffers --

    private static abstract class Unmapper
        implements Runnable, UnmapperProxy
    {
        // may be required to close file
        private static final NativeDispatcher nd = new FileDispatcherImpl();

        // 通过 mmap 系统调用在进程地址空间中映射出来的虚拟内存区域的起始地址
        private volatile long address;
        // mmap 映射出来的虚拟内存区域大小
        protected final long size;
        // MappedByteBuffer 的容量 cap （由 FileChannel#map 参数 size 指定）
        protected final long cap;
        private final FileDescriptor fd;
        // 我们指定的 position 距离其所在文件页起始位置的距离。
        private final int pagePosition;

        private Unmapper(long address, long size, long cap,
                         FileDescriptor fd, int pagePosition)
        {
            assert (address != 0);
            this.address = address;
            this.size = size;
            this.cap = cap;
            this.fd = fd;
            this.pagePosition = pagePosition;
        }

        @Override
        public long address() {
            return address + pagePosition;
        }

        @Override
        public FileDescriptor fileDescriptor() {
            return fd;
        }

        @Override
        public void run() {
            unmap();
        }

        public void unmap() {
            if (address == 0)
                return;
            unmap0(address, size);
            address = 0;

            // if this mapping has a valid file descriptor then we close it
            if (fd.valid()) {
                try {
                    nd.close(fd);
                } catch (IOException ignore) {
                    // nothing we can do
                }
            }

            decrementStats();
        }
        protected abstract void incrementStats();
        protected abstract void decrementStats();
    }

    private static class DefaultUnmapper extends Unmapper {

        // keep track of non-sync mapped buffer usage
        // jvm 调用 mmap 进行内存文件映射的总次数
        static volatile int count;
        // jvm 在进程地址空间中映射出来的虚拟内存总大小（内核角度的虚拟内存占用）
        static volatile long totalSize;
        // jvm 所占用的 MappedByteBuffer 总大小（jvm角度的虚拟内存占用）
        static volatile long totalCapacity;

        public DefaultUnmapper(long address, long size, long cap,
                               FileDescriptor fd, int pagePosition) {
            // 封装映射出来的虚拟内存区域 MappedByteBuffer 相关信息，比如，起始映射地址，映射长度 等等
            super(address, size, cap, fd, pagePosition);
            incrementStats();
        }

        protected void incrementStats() {
            synchronized (DefaultUnmapper.class) {
                count++;
                totalSize += size;
                totalCapacity += cap;
            }
        }
        protected void decrementStats() {
            synchronized (DefaultUnmapper.class) {
                count--;
                totalSize -= size;
                totalCapacity -= cap;
            }
        }

        public boolean isSync() {
            return false;
        }
    }

    private static class SyncUnmapper extends Unmapper {

        // keep track of mapped buffer usage
        static volatile int count;
        static volatile long totalSize;
        static volatile long totalCapacity;

        public SyncUnmapper(long address, long size, long cap,
                            FileDescriptor fd, int pagePosition) {
            super(address, size, cap, fd, pagePosition);
            incrementStats();
        }

        protected void incrementStats() {
            synchronized (SyncUnmapper.class) {
                count++;
                totalSize += size;
                totalCapacity += cap;
            }
        }
        protected void decrementStats() {
            synchronized (SyncUnmapper.class) {
                count--;
                totalSize -= size;
                totalCapacity -= cap;
            }
        }

        public boolean isSync() {
            return true;
        }
    }

    private static void unmap(MappedByteBuffer bb) {
        Cleaner cl = ((DirectBuffer)bb).cleaner();
        if (cl != null)
            cl.clean();
    }

    private static final int MAP_INVALID = -1;
    private static final int MAP_RO = 0;
    private static final int MAP_RW = 1;
    private static final int MAP_PV = 2;

    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        // 映射长度不能超过 Integer.MAX_VALUE，最大映射 2G 大小的内存
        if (size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Size exceeds Integer.MAX_VALUE");
        // 当 MapMode 设置了 READ_WRITE_SYNC 或者 READ_ONLY_SYNC（这两个标志只适用于共享映射，不能用于私有映射），isSync 会为 true
        // isSync = true 表示 MappedByteBuffer 背后直接映射的是 non-volatile memory 而不是普通磁盘上的文件
        // isSync = true 提供的语义当 MappedByteBuffer 在 force 回写数据的时候是通过 CPU 指令完成的而不是 msync 系统调用
        // 并且可以保证在文件映射区 MappedByteBuffer 进行写入之前，文件的 metadata 已经被刷新，文件始终处于一致性的状态
        // isSync 的开启需要依赖底层 CPU 硬件体系架构的支持
        boolean isSync = isSync(Objects.requireNonNull(mode, "Mode is null"));
        // MapMode 转换成相关 prot 常量
        int prot = toProt(mode);
        // 进行内存映射，映射成功之后，相关映射区的信息，比如映射起始地址，映射长度，映射文件等等会封装在 Unmapper 里返回
        // MappedByteBuffer 的释放也封装在 Unmapper中
        Unmapper unmapper = mapInternal(mode, position, size, prot, isSync);
        // 根据 Unmapper 中的信息创建  MappedByteBuffer
        // 当映射 size 指定为 0 时，unmapper = null，随后会返回一个空的 MappedByteBuffer
        if (unmapper == null) {
            // a valid file descriptor is not required
            FileDescriptor dummy = new FileDescriptor();
            if ((!writable) || (prot == MAP_RO))
                return Util.newMappedByteBufferR(0, 0, dummy, null, isSync);
            else
                return Util.newMappedByteBuffer(0, 0, dummy, null, isSync);
        } else if ((!writable) || (prot == MAP_RO)) {
            return Util.newMappedByteBufferR((int)unmapper.cap,
                    unmapper.address + unmapper.pagePosition,
                    unmapper.fd,
                    unmapper, isSync);
        } else {
            return Util.newMappedByteBuffer((int)unmapper.cap,
                    unmapper.address + unmapper.pagePosition,
                    unmapper.fd,
                    unmapper, isSync);
        }
    }

    public Unmapper mapInternal(MapMode mode, long position, long size) throws IOException {
        boolean isSync = isSync(Objects.requireNonNull(mode, "Mode is null"));
        int prot = toProt(mode);
        return mapInternal(mode, position, size, prot, isSync);
    }

    private Unmapper    mapInternal(MapMode mode, long position, long size, int prot, boolean isSync)
        throws IOException
    {
        // 确保文件处于 open 状态
        ensureOpen();
        // 对相关映射参数进行校验
        if (mode == null)
            throw new NullPointerException("Mode is null");
        if (position < 0L)
            throw new IllegalArgumentException("Negative position");
        if (size < 0L)
            throw new IllegalArgumentException("Negative size");
        if (position + size < 0)
            throw new IllegalArgumentException("Position + size overflow");
        // 如果 mode 设置了 READ_ONLY，但文件并没有以读的模式打开，则会抛出 NonReadableChannelExceptio
        // 如果 mode 设置了 READ_WRITE 或者 PRIVATE ，那么文件必须要以读写的模式打开，否则会抛出 NonWritableChannelException
        // 如果 isSync 为 true，但是对应 CPU 体系架构不支持 cache line write back 指令，那么就会抛出 UnsupportedOperationException
        checkMode(mode, prot, isSync);
        long addr = -1;
        int ti = -1;
        try {
            // 这里不要被命名误导，beginBlocking 并不会阻塞当前线程，只是标记一下表示当前线程下面会执行一个 IO 操作可能会无限期阻塞
            // 而这个 IO 操作是可以被中断的，这里会设置中断的回调函数 interruptor，在线程被中断的时候回调
            beginBlocking();
            // threads 是一个 NativeThread 的集合，用于暂存阻塞在该 channel 上的 NativeThread，用于后续统一唤醒
            ti = threads.add();
            // 如果当前 channel 已经关闭，则不能进行 mmap 操作
            if (!isOpen())
                return null;
            // 映射文件大小，同 mmap 系统调用中的 length 参数
            long mapSize;
            // position 距离其所在文件页起始位置的距离,OS 内核以 page 为单位进行内存管理
            // 内存映射的单位也应该按照 page 进行，pagePosition 用于后续将 position,size 与 page 大小对齐
            int pagePosition;
            // 确保线程串行操作文件的 position
            synchronized (positionLock) {
                long filesize;
                do {
                    // 底层通过 fstat 系统调用获取文件大小
                    filesize = nd.size(fd);
                    // 如果系统调用被中断则一直重试
                } while ((filesize == IOStatus.INTERRUPTED) && isOpen());
                if (!isOpen())
                    return null;
                // 如果要映射的文件区域已经超过了 filesize 则需要扩展文件
                if (filesize < position + size) { // Extend file size
                    if (!writable) {
                        throw new IOException("Channel not open for writing " +
                            "- cannot extend file to required size");
                    }
                    int rv;
                    do {
                        // 底层通过 ftruncate 系统调用将文件大小扩展至 （position + size）
                        rv = nd.truncate(fd, position + size);
                    } while ((rv == IOStatus.INTERRUPTED) && isOpen());
                    if (!isOpen())
                        return null;
                }
                // 映射大小为 0 则直接返回 null，随后会创建一个空的 MappedByteBuffer
                if (size == 0) {
                    return null;
                }
                // OS 内核是按照内存页 page 为单位来对内存进行管理的，因此我们内存映射的粒度也应该按照 page 的单位进行
                // allocationGranularity 表示内存分配的粒度，这里是内存页的大小 4K
                // 我们指定的映射 offset 也就是这里的 position 应该是与 4K 对齐的，同理映射长度 size 也应该与 4K 对齐
                // 指定 position 距离其所在文件页起始位置的距离
                pagePosition = (int)(position % allocationGranularity);
                // mapPosition 为映射的文件内容在磁盘文件中的偏移，同 mmap 系统调用中的 offset 参数
                // 这里的 mapPosition 为 position 所属文件页的起始位置
                long mapPosition = position - pagePosition;
                // 映射位置 mapPosition 减去了 pagePosition，所以这里的映射长度 mapSize 需要把 pagePosition 加回来
                mapSize = size + pagePosition;
                try {
                    // If map0 did not throw an exception, the address is valid
                    // native 方法，底层调用 mmap 进行内存文件映射，返回值 addr 为 mmap 系统调用在进程地址空间真实映射出来的虚拟内存区域起始地址
                    addr = map0(prot, mapPosition, mapSize, isSync);
                } catch (OutOfMemoryError x) {
                    // An OutOfMemoryError may indicate that we've exhausted
                    // memory so force gc and re-attempt map
                    // 如果内存不足导致 mmap 失败，这里触发 Full GC 进行内存回收，前提是没有设置 -XX:+DisableExplicitGC
                    // 默认情况下在调用 System.gc() 之后，JVM 马上会执行 Full GC，并且等到 Full GC 完成之后才返回的。
                    // 只有使用 CMS ， G1， Shenandoah 时，并且配置 -XX:+ExplicitGCInvokesConcurrent 的情况下
                    // 调用 System.gc() 会触发 Concurrent Full GC，java 线程在触发了 Concurrent Full GC 之后立马返回
                    System.gc();
                    try {
                        // see https://stackoverflow.com/questions/77941747/why-do-we-need-thread-sleep-after-calling-system-gc-in-jdk-native-memory-usage-s/77943108#77943108
                        // 这里不是等待 gc 结束，而是等待 cleaner thread 运行 directBuffer 的 cleaner,在 cleaner 中释放 native memory
                        // 等待 cleaner 释放 native memory
                        Thread.sleep(100);
                    } catch (InterruptedException y) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        // 重新进行内存映射
                        addr = map0(prot, mapPosition, mapSize, isSync);
                    } catch (OutOfMemoryError y) {
                        // After a second OOME, fail
                        throw new IOException("Map failed", y);
                    }
                }
            } // synchronized

            // On Windows, and potentially other platforms, we need an open
            // file descriptor for some mapping operations.
            FileDescriptor mfd;
            try {
                mfd = nd.duplicateForMapping(fd);
            } catch (IOException ioe) {
                unmap0(addr, mapSize);
                throw ioe;
            }
            // 检查 mmap 调用是否成功，失败的话错误信息会放在 addr 中
            assert (IOStatus.checkAll(addr));
            // addr 需要与文件页尺寸对齐
            assert (addr % allocationGranularity == 0);
            // Unmapper 用于调用 unmmap 释放映射出来的虚拟内存以及物理内存
            // 并统计整个 JVM 进程调用 mmap 的总次数以及映射的内存总大小
            // 本次 mmap 映射出来的内存区域信息都会封装在 Unmapper 中
            Unmapper um = (isSync
                           ? new SyncUnmapper(addr, mapSize, size, mfd, pagePosition)
                           : new DefaultUnmapper(addr, mapSize, size, mfd, pagePosition));
            return um;
        } finally {
            // IO 操作完毕，从 threads 集合中删除当前线程
            threads.remove(ti);
            // IO 操作完毕,清空线程的中断回调函数，如果此时线程已被中断则抛出 closedByInterruptException 异常
            endBlocking(IOStatus.checkAll(addr));
        }
    }

    private boolean isSync(MapMode mode) {
        // Do not want to initialize ExtendedMapMode until
        // after the module system has been initialized
        return !VM.isModuleSystemInited() ? false :
            (mode == ExtendedMapMode.READ_ONLY_SYNC ||
                mode == ExtendedMapMode.READ_WRITE_SYNC);
    }

    private int toProt(MapMode mode) {
        int prot;
        if (mode == MapMode.READ_ONLY) {
            // 共享只读
            prot = MAP_RO;
        } else if (mode == MapMode.READ_WRITE) {
            // 共享读写
            prot = MAP_RW;
        } else if (mode == MapMode.PRIVATE) {
            // 私有读写
            prot = MAP_PV;
        } else if (mode == ExtendedMapMode.READ_ONLY_SYNC) {
            // 共享 non-volatile memory 只读
            prot = MAP_RO;
        } else if (mode == ExtendedMapMode.READ_WRITE_SYNC) {
            // 共享 non-volatile memory 读写
            prot = MAP_RW;
        } else {
            prot = MAP_INVALID;
        }
        return prot;
    }

    private void checkMode(MapMode mode, int prot, boolean isSync) {
        if (prot == MAP_INVALID) {
            throw new UnsupportedOperationException();
        }
        if ((mode != MapMode.READ_ONLY) && mode != ExtendedMapMode.READ_ONLY_SYNC && !writable)
            throw new NonWritableChannelException();
        if (!readable)
            throw new NonReadableChannelException();
        // reject SYNC request if writeback is not enabled for this platform
        if (isSync && !Unsafe.isWritebackEnabled()) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Invoked by sun.management.ManagementFactoryHelper to create the management
     * interface for mapped buffers.
     */
    public static BufferPool getMappedBufferPool() {
        return new BufferPool() {
            @Override
            public String getName() {
                return "mapped";
            }
            @Override
            public long getCount() {
                return DefaultUnmapper.count;
            }
            @Override
            public long getTotalCapacity() {
                return DefaultUnmapper.totalCapacity;
            }
            @Override
            public long getMemoryUsed() {
                return DefaultUnmapper.totalSize;
            }
        };
    }

    /**
     * Invoked by sun.management.ManagementFactoryHelper to create the management
     * interface for sync mapped buffers.
     */
    public static BufferPool getSyncMappedBufferPool() {
        return new BufferPool() {
            @Override
            public String getName() {
                return "mapped - 'non-volatile memory'";
            }
            @Override
            public long getCount() {
                return SyncUnmapper.count;
            }
            @Override
            public long getTotalCapacity() {
                return SyncUnmapper.totalCapacity;
            }
            @Override
            public long getMemoryUsed() {
                return SyncUnmapper.totalSize;
            }
        };
    }

    // -- Locks --

    // keeps track of locks on this file
    private volatile FileLockTable fileLockTable;

    private FileLockTable fileLockTable() throws IOException {
        if (fileLockTable == null) {
            synchronized (this) {
                if (fileLockTable == null) {
                    int ti = threads.add();
                    try {
                        ensureOpen();
                        fileLockTable = new FileLockTable(this, fd);
                    } finally {
                        threads.remove(ti);
                    }
                }
            }
        }
        return fileLockTable;
    }

    public FileLock lock(long position, long size, boolean shared)
        throws IOException
    {
        ensureOpen();
        if (shared && !readable)
            throw new NonReadableChannelException();
        if (!shared && !writable)
            throw new NonWritableChannelException();
        FileLockImpl fli = new FileLockImpl(this, position, size, shared);
        FileLockTable flt = fileLockTable();
        flt.add(fli);
        boolean completed = false;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return null;
            int n;
            do {
                n = nd.lock(fd, true, position, size, shared);
            } while ((n == FileDispatcher.INTERRUPTED) && isOpen());
            if (isOpen()) {
                if (n == FileDispatcher.RET_EX_LOCK) {
                    assert shared;
                    FileLockImpl fli2 = new FileLockImpl(this, position, size,
                                                         false);
                    flt.replace(fli, fli2);
                    fli = fli2;
                }
                completed = true;
            }
        } finally {
            if (!completed)
                flt.remove(fli);
            threads.remove(ti);
            try {
                endBlocking(completed);
            } catch (ClosedByInterruptException e) {
                throw new FileLockInterruptionException();
            }
        }
        return fli;
    }

    public FileLock tryLock(long position, long size, boolean shared)
        throws IOException
    {
        ensureOpen();
        if (shared && !readable)
            throw new NonReadableChannelException();
        if (!shared && !writable)
            throw new NonWritableChannelException();
        FileLockImpl fli = new FileLockImpl(this, position, size, shared);
        FileLockTable flt = fileLockTable();
        flt.add(fli);
        int result;

        int ti = threads.add();
        try {
            try {
                ensureOpen();
                result = nd.lock(fd, false, position, size, shared);
            } catch (IOException e) {
                flt.remove(fli);
                throw e;
            }
            if (result == FileDispatcher.NO_LOCK) {
                flt.remove(fli);
                return null;
            }
            if (result == FileDispatcher.RET_EX_LOCK) {
                assert shared;
                FileLockImpl fli2 = new FileLockImpl(this, position, size,
                                                     false);
                flt.replace(fli, fli2);
                return fli2;
            }
            return fli;
        } finally {
            threads.remove(ti);
        }
    }

    void release(FileLockImpl fli) throws IOException {
        int ti = threads.add();
        try {
            ensureOpen();
            nd.release(fd, fli.position(), fli.size());
        } finally {
            threads.remove(ti);
        }
        assert fileLockTable != null;
        fileLockTable.remove(fli);
    }

    // -- Native methods --

    // Creates a new mapping
    private native long map0(int prot, long position, long length, boolean isSync)
        throws IOException;

    // Removes an existing mapping
    private static native int unmap0(long address, long length);

    // Transfers from src to dst, or returns -2 if kernel can't do that
    private native long transferTo0(FileDescriptor src, long position,
                                    long count, FileDescriptor dst);

    // Caches fieldIDs
    private static native long initIDs();

    static {
        IOUtil.load();
        allocationGranularity = initIDs();
    }
}
