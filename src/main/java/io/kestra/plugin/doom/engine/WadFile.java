package io.kestra.plugin.doom.engine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Parser for Doom WAD (Where's All the Data) files.
 * Supports both IWAD and PWAD formats.
 */
public class WadFile implements AutoCloseable {
    private final RandomAccessFile file;
    private final String type;
    private final List<LumpEntry> directory;
    private final Map<String, List<Integer>> nameIndex;

    public record LumpEntry(String name, int offset, int size) {}

    public WadFile(Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "r");
        this.nameIndex = new HashMap<>();

        byte[] header = new byte[12];
        file.readFully(header);
        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        this.type = new String(header, 0, 4, StandardCharsets.US_ASCII);
        if (!type.equals("IWAD") && !type.equals("PWAD")) {
            throw new IOException("Not a valid WAD file: " + type);
        }

        int numLumps = hb.getInt(4);
        int dirOffset = hb.getInt(8);

        this.directory = new ArrayList<>(numLumps);
        file.seek(dirOffset);
        byte[] dirData = new byte[numLumps * 16];
        file.readFully(dirData);
        ByteBuffer db = ByteBuffer.wrap(dirData).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numLumps; i++) {
            int offset = db.getInt();
            int size = db.getInt();
            byte[] nameBytes = new byte[8];
            db.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.US_ASCII).trim().replace("\0", "").toUpperCase();
            directory.add(new LumpEntry(name, offset, size));
            nameIndex.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
        }
    }

    public byte[] readLump(String name) throws IOException {
        List<Integer> indices = nameIndex.get(name.toUpperCase());
        if (indices == null || indices.isEmpty()) {
            return null;
        }
        return readLump(indices.get(indices.size() - 1));
    }

    public byte[] readLump(int index) throws IOException {
        LumpEntry entry = directory.get(index);
        if (entry.size() == 0) return new byte[0];
        byte[] data = new byte[entry.size()];
        file.seek(entry.offset());
        file.readFully(data);
        return data;
    }

    public int findLump(String name) {
        List<Integer> indices = nameIndex.get(name.toUpperCase());
        return (indices == null || indices.isEmpty()) ? -1 : indices.get(indices.size() - 1);
    }

    public int findLumpAfter(String name, int startIndex) {
        for (int i = startIndex; i < directory.size(); i++) {
            if (directory.get(i).name().equals(name.toUpperCase())) {
                return i;
            }
        }
        return -1;
    }

    public List<LumpEntry> getDirectory() {
        return Collections.unmodifiableList(directory);
    }

    public String getType() {
        return type;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
