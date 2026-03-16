package io.kestra.plugin.doom.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Doom map data structures and loader.
 * Loads all map lumps (THINGS, LINEDEFS, SIDEDEFS, VERTEXES, SEGS, SSECTORS, NODES, SECTORS).
 */
public class GameMap {
    public record Vertex(double x, double y) {}
    public record Thing(double x, double y, double angle, int type, int flags) {}
    public record LineDef(int startVertex, int endVertex, int flags, int special, int tag,
                          int rightSideDef, int leftSideDef) {}
    public record SideDef(int xOffset, int yOffset, String upperTexture, String middleTexture,
                          String lowerTexture, int sector) {}
    public record Sector(double floorHeight, double ceilingHeight, String floorTexture,
                         String ceilingTexture, int lightLevel, int special, int tag) {}
    public record Seg(int startVertex, int endVertex, double angle, int lineDef, int direction, int offset) {}
    public record SubSector(int segCount, int firstSeg) {}
    public record Node(double partitionX, double partitionY, double dx, double dy,
                       short[] rightBBox, short[] leftBBox, int rightChild, int leftChild) {}

    public static final int NF_SUBSECTOR = 0x8000;

    private Vertex[] vertices;
    private Thing[] things;
    private LineDef[] lineDefs;
    private SideDef[] sideDefs;
    private Sector[] sectors;
    private Seg[] segs;
    private SubSector[] subSectors;
    private Node[] nodes;

    public void load(WadFile wad, String mapName) throws IOException {
        int mapIndex = wad.findLump(mapName);
        if (mapIndex < 0) {
            throw new IOException("Map not found: " + mapName);
        }

        loadVertices(wad, mapIndex);
        loadThings(wad, mapIndex);
        loadLineDefs(wad, mapIndex);
        loadSideDefs(wad, mapIndex);
        loadSectors(wad, mapIndex);
        loadSegs(wad, mapIndex);
        loadSubSectors(wad, mapIndex);
        loadNodes(wad, mapIndex);
    }

    private void loadVertices(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("VERTEXES", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        vertices = new Vertex[data.length / 4];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = new Vertex(bb.getShort(), bb.getShort());
        }
    }

    private void loadThings(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("THINGS", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        things = new Thing[data.length / 10];
        for (int i = 0; i < things.length; i++) {
            things[i] = new Thing(bb.getShort(), bb.getShort(),
                bb.getShort() * Math.PI / 180.0, bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF);
        }
    }

    private void loadLineDefs(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("LINEDEFS", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        lineDefs = new LineDef[data.length / 14];
        for (int i = 0; i < lineDefs.length; i++) {
            lineDefs[i] = new LineDef(
                bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF,
                bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF,
                bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF
            );
        }
    }

    private void loadSideDefs(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("SIDEDEFS", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        sideDefs = new SideDef[data.length / 30];
        for (int i = 0; i < sideDefs.length; i++) {
            short xOff = bb.getShort();
            short yOff = bb.getShort();
            String upper = readTextureName(bb);
            String lower = readTextureName(bb);
            String middle = readTextureName(bb);
            int sector = bb.getShort() & 0xFFFF;
            sideDefs[i] = new SideDef(xOff, yOff, upper, middle, lower, sector);
        }
    }

    private void loadSectors(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("SECTORS", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        sectors = new Sector[data.length / 26];
        for (int i = 0; i < sectors.length; i++) {
            sectors[i] = new Sector(
                bb.getShort(), bb.getShort(),
                readTextureName(bb), readTextureName(bb),
                bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF
            );
        }
    }

    private void loadSegs(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("SEGS", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        segs = new Seg[data.length / 12];
        for (int i = 0; i < segs.length; i++) {
            segs[i] = new Seg(
                bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF,
                (bb.getShort() & 0xFFFF) * Math.PI / 32768.0,
                bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF
            );
        }
    }

    private void loadSubSectors(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("SSECTORS", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        subSectors = new SubSector[data.length / 4];
        for (int i = 0; i < subSectors.length; i++) {
            subSectors[i] = new SubSector(bb.getShort() & 0xFFFF, bb.getShort() & 0xFFFF);
        }
    }

    private void loadNodes(WadFile wad, int mapIndex) throws IOException {
        byte[] data = wad.readLump(wad.findLumpAfter("NODES", mapIndex));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        nodes = new Node[data.length / 28];
        for (int i = 0; i < nodes.length; i++) {
            short px = bb.getShort(), py = bb.getShort(), pdx = bb.getShort(), pdy = bb.getShort();
            short[] rbox = new short[]{bb.getShort(), bb.getShort(), bb.getShort(), bb.getShort()};
            short[] lbox = new short[]{bb.getShort(), bb.getShort(), bb.getShort(), bb.getShort()};
            int rChild = bb.getShort() & 0xFFFF;
            int lChild = bb.getShort() & 0xFFFF;
            nodes[i] = new Node(px, py, pdx, pdy, rbox, lbox, rChild, lChild);
        }
    }

    private String readTextureName(ByteBuffer bb) {
        byte[] nameBytes = new byte[8];
        bb.get(nameBytes);
        return new String(nameBytes, StandardCharsets.US_ASCII).trim().replace("\0", "").toUpperCase();
    }

    /** Find the player 1 start position (thing type 1). */
    public Thing findPlayerStart() {
        for (Thing t : things) {
            if (t.type() == 1) return t;
        }
        return things.length > 0 ? things[0] : new Thing(0, 0, 0, 1, 0);
    }

    public Vertex[] getVertices() { return vertices; }
    public Thing[] getThings() { return things; }
    public LineDef[] getLineDefs() { return lineDefs; }
    public SideDef[] getSideDefs() { return sideDefs; }
    public Sector[] getSectors() { return sectors; }
    public Seg[] getSegs() { return segs; }
    public SubSector[] getSubSectors() { return subSectors; }
    public Node[] getNodes() { return nodes; }
}
