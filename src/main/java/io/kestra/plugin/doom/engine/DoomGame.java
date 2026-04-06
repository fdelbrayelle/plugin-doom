package io.kestra.plugin.doom.engine;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Doom game coordinator. Loads a WAD, initializes the map,
 * runs demo playback or auto-walk, and captures rendered frames.
 */
public class DoomGame implements AutoCloseable {
    private final WadFile wad;
    private final GameMap map;
    private final Palette palette;
    private final BspRenderer renderer;
    private final int screenWidth;
    private final int screenHeight;

    // Player state
    private double playerX, playerY, playerZ;
    private double playerAngle; // radians
    private static final double PLAYER_VIEW_HEIGHT = 41.0;
    private static final double MOVE_SPEED = 8.0;
    private static final double TURN_SPEED = Math.toRadians(3.0);

    // Demo data
    private byte[] demoData;
    private int demoPos;
    private int demoPlayerCount;

    public DoomGame(Path wadPath, String mapName, int width, int height) throws IOException {
        this.wad = new WadFile(wadPath);
        this.map = new GameMap();
        this.palette = Palette.fromWad(wad);
        this.screenWidth = width;
        this.screenHeight = height;

        map.load(wad, mapName);

        GameMap.Thing start = map.findPlayerStart();
        this.playerX = start.x();
        this.playerY = start.y();
        this.playerAngle = start.angle();

        // Set Z to sector floor height + view height
        this.playerZ = findFloorHeight(playerX, playerY) + PLAYER_VIEW_HEIGHT;

        this.renderer = new BspRenderer(map, width, height);

        // Try to load a demo
        loadDemo();
    }

    private void loadDemo() {
        try {
            // Try DEMO1 first
            demoData = wad.readLump("DEMO1");
            if (demoData != null && demoData.length > 13) {
                // Doom 1.9 demo header (version 109), 13 bytes:
                //   0: version, 1: skill, 2: episode, 3: map,
                //   4: deathmatch, 5: respawn, 6: fast, 7: nomonsters,
                //   8: consoleplayer, 9-12: playeringame[0..3]
                int version = demoData[0] & 0xFF;
                if (version == 109) {
                    demoPlayerCount = 0;
                    for (int i = 9; i < 13; i++) {
                        if (demoData[i] != 0) demoPlayerCount++;
                    }
                    if (demoPlayerCount == 0) demoPlayerCount = 1;
                    demoPos = 13;
                } else {
                    // Older demo format (7 byte header)
                    demoPlayerCount = 1;
                    demoPos = 7;
                }
            } else {
                demoData = null;
            }
        } catch (IOException e) {
            demoData = null;
        }
    }

    /**
     * Run the game for the specified number of frames and return all rendered frames.
     */
    public List<BufferedImage> run(int frameCount, int captureEveryN) {
        List<BufferedImage> frames = new ArrayList<>();

        for (int tic = 0; tic < frameCount; tic++) {
            // Process movement
            if (demoData != null) {
                processDemoTic();
            } else {
                processAutoWalk(tic);
            }

            // Update Z to floor height
            playerZ = findFloorHeight(playerX, playerY) + PLAYER_VIEW_HEIGHT;

            // Render frame
            if (tic % captureEveryN == 0) {
                renderer.setPlayerPosition(playerX, playerY, playerZ, playerAngle);
                frames.add(renderer.renderFrame());
            }
        }

        return frames;
    }

    private void processDemoTic() {
        if (demoData == null || demoPos + 4 > demoData.length) {
            demoData = null; // demo ended
            return;
        }

        // Read tic command for player 0
        byte forwardMove = demoData[demoPos];
        byte sideMove = demoData[demoPos + 1];
        byte angleTurn = demoData[demoPos + 2];
        // byte buttons = demoData[demoPos + 3]; // unused for rendering

        // Advance past all player tics
        demoPos += 4 * demoPlayerCount;

        // Check for demo end marker (0x80)
        if (forwardMove == (byte) 0x80) {
            demoData = null;
            return;
        }

        // Apply turning: byte value * (2*PI / 256) per tic
        playerAngle += angleTurn * (Math.PI * 2.0 / 256.0);

        // Apply movement
        double cos = Math.cos(playerAngle);
        double sin = Math.sin(playerAngle);

        playerX += forwardMove * cos + sideMove * sin;
        playerY += forwardMove * sin - sideMove * cos;
    }

    private void processAutoWalk(int tic) {
        // Simple auto-walk: move forward slowly and turn gradually
        // This creates a nice tour of the level
        double cos = Math.cos(playerAngle);
        double sin = Math.sin(playerAngle);

        playerX += MOVE_SPEED * cos;
        playerY += MOVE_SPEED * sin;

        // Gentle turning
        playerAngle += TURN_SPEED * 0.3;

        // Every 100 tics, turn more
        if (tic % 100 < 5) {
            playerAngle += TURN_SPEED;
        }
    }

    private double findFloorHeight(double x, double y) {
        // Walk the BSP tree to find the subsector containing (x, y)
        GameMap.Node[] nodes = map.getNodes();
        if (nodes == null || nodes.length == 0) return 0;

        int nodeIndex = nodes.length - 1;
        while ((nodeIndex & GameMap.NF_SUBSECTOR) == 0) {
            if (nodeIndex >= nodes.length) return 0;
            GameMap.Node node = nodes[nodeIndex];
            double dx = x - node.partitionX();
            double dy = y - node.partitionY();
            boolean onRight = (dx * node.dy() - dy * node.dx()) >= 0;
            nodeIndex = onRight ? node.rightChild() : node.leftChild();
        }

        int ssIndex = nodeIndex & 0x7FFF;
        if (ssIndex >= map.getSubSectors().length) return 0;

        GameMap.SubSector ss = map.getSubSectors()[ssIndex];
        if (ss.firstSeg() >= map.getSegs().length) return 0;

        GameMap.Seg seg = map.getSegs()[ss.firstSeg()];
        if (seg.lineDef() >= map.getLineDefs().length) return 0;

        GameMap.LineDef line = map.getLineDefs()[seg.lineDef()];
        int sideIdx = (seg.direction() == 0) ? line.rightSideDef() : line.leftSideDef();
        if (sideIdx == 0xFFFF || sideIdx >= map.getSideDefs().length) return 0;

        GameMap.SideDef side = map.getSideDefs()[sideIdx];
        if (side.sector() >= map.getSectors().length) return 0;

        return map.getSectors()[side.sector()].floorHeight();
    }

    @Override
    public void close() throws IOException {
        wad.close();
    }
}
