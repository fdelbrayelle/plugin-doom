package io.kestra.plugin.doom;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a minimal valid Doom WAD file for testing.
 * Creates a simple rectangular room with a player start.
 */
public class TestWadGenerator {

    /**
     * Generate a minimal IWAD with a single box room map at E1M1.
     */
    public static Path generate() throws IOException {
        Path wadPath = Files.createTempFile("test-doom-", ".wad");

        try (RandomAccessFile raf = new RandomAccessFile(wadPath.toFile(), "rw")) {
            ByteArrayOutputStream lumps = new ByteArrayOutputStream();
            var directory = new java.util.ArrayList<DirEntry>();

            // --- PLAYPAL (color palette) ---
            byte[] playpal = generatePalette();
            int playpalOffset = 12; // right after header
            directory.add(new DirEntry(playpalOffset, playpal.length, "PLAYPAL"));
            lumps.write(playpal);

            // --- Map marker E1M1 ---
            int mapOffset = 12 + lumps.size();
            directory.add(new DirEntry(mapOffset, 0, "E1M1"));

            // Room: 512x512 units, centered at origin
            // Vertices: 4 corners
            short[][] verts = {
                {-256, -256}, // 0: SW
                { 256, -256}, // 1: SE
                { 256,  256}, // 2: NE
                {-256,  256}, // 3: NW
                // BSP split vertices (split room at x=0)
                {   0, -256}, // 4: S-mid
                {   0,  256}, // 5: N-mid
            };

            // --- THINGS ---
            byte[] things = buildThings();
            int thingsOffset = 12 + lumps.size();
            directory.add(new DirEntry(thingsOffset, things.length, "THINGS"));
            lumps.write(things);

            // --- LINEDEFS (4 outer walls, all one-sided) ---
            byte[] linedefs = buildLineDefs();
            int linedefsOffset = 12 + lumps.size();
            directory.add(new DirEntry(linedefsOffset, linedefs.length, "LINEDEFS"));
            lumps.write(linedefs);

            // --- SIDEDEFS ---
            byte[] sidedefs = buildSideDefs();
            int sidedefsOffset = 12 + lumps.size();
            directory.add(new DirEntry(sidedefsOffset, sidedefs.length, "SIDEDEFS"));
            lumps.write(sidedefs);

            // --- VERTEXES ---
            byte[] vertexes = buildVertexes(verts);
            int vertexesOffset = 12 + lumps.size();
            directory.add(new DirEntry(vertexesOffset, vertexes.length, "VERTEXES"));
            lumps.write(vertexes);

            // --- SEGS ---
            byte[] segs = buildSegs();
            int segsOffset = 12 + lumps.size();
            directory.add(new DirEntry(segsOffset, segs.length, "SEGS"));
            lumps.write(segs);

            // --- SSECTORS ---
            byte[] ssectors = buildSubSectors();
            int ssectorsOffset = 12 + lumps.size();
            directory.add(new DirEntry(ssectorsOffset, ssectors.length, "SSECTORS"));
            lumps.write(ssectors);

            // --- NODES ---
            byte[] nodes = buildNodes();
            int nodesOffset = 12 + lumps.size();
            directory.add(new DirEntry(nodesOffset, nodes.length, "NODES"));
            lumps.write(nodes);

            // --- SECTORS ---
            byte[] sectors = buildSectors();
            int sectorsOffset = 12 + lumps.size();
            directory.add(new DirEntry(sectorsOffset, sectors.length, "SECTORS"));
            lumps.write(sectors);

            // --- REJECT ---
            byte[] reject = new byte[1]; // minimal
            int rejectOffset = 12 + lumps.size();
            directory.add(new DirEntry(rejectOffset, reject.length, "REJECT"));
            lumps.write(reject);

            // --- BLOCKMAP ---
            byte[] blockmap = buildBlockmap();
            int blockmapOffset = 12 + lumps.size();
            directory.add(new DirEntry(blockmapOffset, blockmap.length, "BLOCKMAP"));
            lumps.write(blockmap);

            // Write WAD
            int dirOffset = 12 + lumps.size();
            byte[] lumpData = lumps.toByteArray();

            // Header
            raf.write("IWAD".getBytes(StandardCharsets.US_ASCII));
            writeInt(raf, directory.size());
            writeInt(raf, dirOffset);

            // Lump data
            raf.write(lumpData);

            // Directory
            for (DirEntry entry : directory) {
                writeInt(raf, entry.offset);
                writeInt(raf, entry.size);
                byte[] name = new byte[8];
                byte[] nameBytes = entry.name.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(nameBytes, 0, name, 0, Math.min(nameBytes.length, 8));
                raf.write(name);
            }
        }

        return wadPath;
    }

    private static byte[] generatePalette() {
        // Generate a basic Doom-like palette (256 colors * 3 bytes * 14 palettes)
        // Only the first palette matters for us
        byte[] pal = new byte[768 * 14];
        for (int i = 0; i < 256; i++) {
            // Simple gradient palette
            int r, g, b;
            if (i < 16) { // Blacks/dark grays
                r = g = b = i * 8;
            } else if (i < 32) { // Browns
                r = 80 + (i - 16) * 8; g = 50 + (i - 16) * 4; b = 20 + (i - 16) * 2;
            } else if (i < 48) { // Greens
                r = 20 + (i - 32) * 2; g = 80 + (i - 32) * 8; b = 20 + (i - 32) * 2;
            } else if (i < 64) { // Reds
                r = 80 + (i - 48) * 10; g = 20 + (i - 48) * 2; b = 20 + (i - 48) * 2;
            } else if (i < 128) { // Grays
                r = g = b = i * 2;
            } else { // Light colors
                r = 128 + (i - 128); g = 128 + (i - 128); b = 128 + (i - 128);
            }
            pal[i * 3] = (byte) Math.min(255, r);
            pal[i * 3 + 1] = (byte) Math.min(255, g);
            pal[i * 3 + 2] = (byte) Math.min(255, b);
        }
        // Copy first palette to remaining 13
        for (int p = 1; p < 14; p++) {
            System.arraycopy(pal, 0, pal, p * 768, 768);
        }
        return pal;
    }

    // THINGS: player 1 start at (0, 0) facing east
    private static byte[] buildThings() {
        ByteBuffer bb = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0);   // x
        bb.putShort((short) 0);   // y
        bb.putShort((short) 90);  // angle (north)
        bb.putShort((short) 1);   // type: player 1 start
        bb.putShort((short) 7);   // flags: appears in all skills
        return bb.array();
    }

    // 4 linedefs forming a box: v0→v1, v1→v2, v2→v3, v3→v0
    // All one-sided with right sidedef
    private static byte[] buildLineDefs() {
        ByteBuffer bb = ByteBuffer.allocate(4 * 14).order(ByteOrder.LITTLE_ENDIAN);
        int[][] lines = {{0, 1, 0}, {1, 2, 1}, {2, 3, 2}, {3, 0, 3}};
        for (int[] line : lines) {
            bb.putShort((short) line[0]); // start vertex
            bb.putShort((short) line[1]); // end vertex
            bb.putShort((short) 1);       // flags: blocks
            bb.putShort((short) 0);       // special
            bb.putShort((short) 0);       // tag
            bb.putShort((short) line[2]); // right sidedef
            bb.putShort((short) 0xFFFF);  // left sidedef (none)
        }
        return bb.array();
    }

    // 4 sidedefs, all pointing to sector 0
    private static byte[] buildSideDefs() {
        ByteBuffer bb = ByteBuffer.allocate(4 * 30).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            bb.putShort((short) 0); // x offset
            bb.putShort((short) 0); // y offset
            bb.put(padName("STARTAN3")); // upper texture
            bb.put(padName("STARTAN3")); // lower texture
            bb.put(padName("STARTAN3")); // middle texture
            bb.putShort((short) 0); // sector
        }
        return bb.array();
    }

    private static byte[] buildVertexes(short[][] verts) {
        ByteBuffer bb = ByteBuffer.allocate(verts.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (short[] v : verts) {
            bb.putShort(v[0]);
            bb.putShort(v[1]);
        }
        return bb.array();
    }

    // Segs: same as linedefs for this simple case
    // Split into 2 subsectors using the BSP partition at x=0
    // Left subsector (segs 0, 1): south wall west half, north wall west half + west wall
    // Actually, let's keep it simple: 2 segs per subsector
    // SS0 (left/west): segs along west side: linedef 3 (v3→v0) and part of linedef 0, 2
    // This is getting complex. Let's just put all 4 segs in one subsector
    // and have the BSP node point both children to it.
    private static byte[] buildSegs() {
        ByteBuffer bb = ByteBuffer.allocate(4 * 12).order(ByteOrder.LITTLE_ENDIAN);
        // Seg for each linedef
        int[][] segData = {
            {0, 1, 0, 0},  // v0→v1 (south wall), linedef 0, direction 0
            {1, 2, 0, 1},  // v1→v2 (east wall), linedef 1, direction 0
            {2, 3, 0, 2},  // v2→v3 (north wall), linedef 2, direction 0
            {3, 0, 0, 3},  // v3→v0 (west wall), linedef 3, direction 0
        };
        for (int[] seg : segData) {
            bb.putShort((short) seg[0]); // start vertex
            bb.putShort((short) seg[1]); // end vertex
            // angle: compute from vertices
            bb.putShort((short) 0);      // angle (simplified)
            bb.putShort((short) seg[3]); // linedef
            bb.putShort((short) seg[2]); // direction (0 = same as linedef)
            bb.putShort((short) 0);      // offset
        }
        return bb.array();
    }

    // One subsector containing all 4 segs
    private static byte[] buildSubSectors() {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 4); // seg count
        bb.putShort((short) 0); // first seg
        return bb.array();
    }

    // One BSP node: partition at x=0 horizontal
    // Both children point to subsector 0 (with NF_SUBSECTOR flag)
    private static byte[] buildNodes() {
        ByteBuffer bb = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0);    // partition x
        bb.putShort((short) 0);    // partition y
        bb.putShort((short) 1);    // dx (partition direction)
        bb.putShort((short) 0);    // dy
        // Right child bounding box (top, bottom, left, right)
        bb.putShort((short) 256);  // top
        bb.putShort((short) -256); // bottom
        bb.putShort((short) 0);    // left
        bb.putShort((short) 256);  // right
        // Left child bounding box
        bb.putShort((short) 256);  // top
        bb.putShort((short) -256); // bottom
        bb.putShort((short) -256); // left
        bb.putShort((short) 0);    // right
        // Children (both point to subsector 0)
        bb.putShort((short) (0x8000 | 0)); // right child = subsector 0
        bb.putShort((short) (0x8000 | 0)); // left child = subsector 0
        return bb.array();
    }

    // One sector: floor=0, ceiling=128, light=192
    private static byte[] buildSectors() {
        ByteBuffer bb = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0);    // floor height
        bb.putShort((short) 128);  // ceiling height
        bb.put(padName("FLOOR4_8")); // floor texture
        bb.put(padName("CEIL3_5"));  // ceiling texture
        bb.putShort((short) 192);  // light level
        bb.putShort((short) 0);    // special
        bb.putShort((short) 0);    // tag
        return bb.array();
    }

    private static byte[] buildBlockmap() {
        // Minimal blockmap header
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) -256); // x origin
        bb.putShort((short) -256); // y origin
        bb.putShort((short) 4);    // columns
        bb.putShort((short) 4);    // rows
        return bb.array();
    }

    private static byte[] padName(String name) {
        byte[] result = new byte[8];
        byte[] src = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, result, 0, Math.min(src.length, 8));
        return result;
    }

    private static void writeInt(RandomAccessFile raf, int value) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(value);
        raf.write(bb.array());
    }

    record DirEntry(int offset, int size, String name) {}
}
