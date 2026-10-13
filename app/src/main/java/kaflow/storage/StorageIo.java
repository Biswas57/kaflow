package kaflow.storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Package-private positional I/O seam for short transfers and failure injection. */
interface StorageIo {
    enum Mode { READ, EXISTING, CREATE }

    interface File extends AutoCloseable {
        int read(ByteBuffer target, long position) throws IOException;
        int write(ByteBuffer source, long position) throws IOException;
        long size() throws IOException;
        @Override void close() throws IOException;
    }

    File open(Path path, Mode mode) throws IOException;

    static StorageIo system() {
        return (path, mode) -> {
            FileChannel channel = switch (mode) {
                case READ -> FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                case EXISTING -> FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                case CREATE -> FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            };
            return new File() {
                @Override public int read(ByteBuffer target, long position) throws IOException { return channel.read(target, position); }
                @Override public int write(ByteBuffer source, long position) throws IOException { return channel.write(source, position); }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public void close() throws IOException { channel.close(); }
            };
        };
    }

    static void readFully(File file, ByteBuffer target, long position, int zeroLimit) throws IOException {
        transfer(file, target, position, zeroLimit, false);
    }

    static void writeFully(File file, ByteBuffer source, long position, int zeroLimit) throws IOException {
        transfer(file, source, position, zeroLimit, true);
    }

    private static void transfer(File file, ByteBuffer buffer, long position, int zeroLimit, boolean write) throws IOException {
        if (position < 0 || position > Long.MAX_VALUE - buffer.remaining()) {
            throw new StorageException(StorageException.Code.INVALID_ARGUMENT, "I/O position overflow");
        }
        int zeroes = 0;
        while (buffer.hasRemaining()) {
            int count = write ? file.write(buffer, position) : file.read(buffer, position);
            if (count < 0) throw new EOFException("incomplete storage transfer at " + position);
            if (count == 0) {
                if (++zeroes >= zeroLimit) {
                    throw new StorageException(StorageException.Code.IO_STALLED, "storage transfer made no progress");
                }
            } else {
                position = Math.addExact(position, count);
                zeroes = 0;
            }
        }
    }
}
