package io.kestra.plugin.doom.engine;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * BSP-based software renderer for Doom maps.
 * Uses the original Doom rendering algorithm: BSP tree traversal with
 * front-to-back rendering and a solid segments clipper for occlusion.
 */
public class BspRenderer {
    private final GameMap map;
    private final int width;
    private final int height;
    private final double focalLength;

    // Player state
    private double playerX, playerY, playerZ;
    private double playerAngle; // radians

    // Rendering state
    private int[] frameBuffer;
    private int[] ceilingClip;  // lowest visible row for each column (top clip)
    private int[] floorClip;    // highest visible row for each column (bottom clip)
    private boolean[] columnDrawn; // fully occluded columns
    private int columnsRemaining;

    // Sky color
    private static final int SKY_COLOR = 0xFF2040A0;

    public BspRenderer(GameMap map, int width, int height) {
        this.map = map;
        this.width = width;
        this.height = height;
        this.focalLength = (width / 2.0) / Math.tan(Math.toRadians(45)); // 90 degree FOV
    }

    public void setPlayerPosition(double x, double y, double z, double angle) {
        this.playerX = x;
        this.playerY = y;
        this.playerZ = z;
        this.playerAngle = angle;
    }

    public BufferedImage renderFrame() {
        frameBuffer = new int[width * height];
        ceilingClip = new int[width];
        floorClip = new int[width];
        columnDrawn = new boolean[width];
        columnsRemaining = width;

        Arrays.fill(floorClip, height - 1);
        // ceilingClip is 0 by default

        // Draw sky gradient on top half, dark floor on bottom half
        int halfH = height / 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (y < halfH) {
                    double t = (double) y / halfH;
                    int r = (int) (0x10 + t * 0x30);
                    int g = (int) (0x20 + t * 0x20);
                    int b = (int) (0x60 + t * 0x40);
                    frameBuffer[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                } else {
                    double t = (double) (y - halfH) / halfH;
                    int gray = (int) (0x30 + t * 0x20);
                    frameBuffer[y * width + x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                }
            }
        }

        GameMap.Node[] nodes = map.getNodes();
        if (nodes != null && nodes.length > 0) {
            renderBspNode(nodes.length - 1);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, frameBuffer, 0, width);
        return image;
    }

    private void renderBspNode(int nodeIndex) {
        if (columnsRemaining <= 0) return;

        if ((nodeIndex & GameMap.NF_SUBSECTOR) != 0) {
            int ssIndex = nodeIndex & 0x7FFF;
            renderSubSector(ssIndex);
            return;
        }

        GameMap.Node node = map.getNodes()[nodeIndex];
        // Determine which side of the partition line the player is on
        double dx = playerX - node.partitionX();
        double dy = playerY - node.partitionY();
        boolean onRight = (dx * node.dy() - dy * node.dx()) >= 0;

        // Render near side first (front-to-back)
        if (onRight) {
            renderBspNode(node.rightChild());
            renderBspNode(node.leftChild());
        } else {
            renderBspNode(node.leftChild());
            renderBspNode(node.rightChild());
        }
    }

    private void renderSubSector(int ssIndex) {
        GameMap.SubSector ss = map.getSubSectors()[ssIndex];
        for (int i = 0; i < ss.segCount(); i++) {
            if (columnsRemaining <= 0) return;
            int segIdx = ss.firstSeg() + i;
            if (segIdx < map.getSegs().length) {
                renderSeg(map.getSegs()[segIdx]);
            }
        }
    }

    private void renderSeg(GameMap.Seg seg) {
        GameMap.Vertex v1 = map.getVertices()[seg.startVertex()];
        GameMap.Vertex v2 = map.getVertices()[seg.endVertex()];

        // Transform to view space
        double cos = Math.cos(playerAngle);
        double sin = Math.sin(playerAngle);

        double dx1 = v1.x() - playerX, dy1 = v1.y() - playerY;
        double dx2 = v2.x() - playerX, dy2 = v2.y() - playerY;

        // View space: depth = forward, rx = rightward
        double depth1 = dx1 * cos + dy1 * sin;
        double rx1 = dx1 * sin - dy1 * cos;
        double depth2 = dx2 * cos + dy2 * sin;
        double rx2 = dx2 * sin - dy2 * cos;

        // Clip behind the player
        if (depth1 < 1 && depth2 < 1) return;

        // Clip near plane
        if (depth1 < 1 || depth2 < 1) {
            double t = (1 - depth1) / (depth2 - depth1);
            if (depth1 < 1) {
                rx1 = rx1 + t * (rx2 - rx1);
                depth1 = 1;
            } else {
                t = (1 - depth2) / (depth1 - depth2);
                rx2 = rx2 + t * (rx1 - rx2);
                depth2 = 1;
            }
        }

        // Project to screen X
        int screenX1 = (int) (width / 2.0 - rx1 * focalLength / depth1);
        int screenX2 = (int) (width / 2.0 - rx2 * focalLength / depth2);

        // Ensure left-to-right ordering
        if (screenX1 > screenX2) {
            int tmpX = screenX1; screenX1 = screenX2; screenX2 = tmpX;
            double tmpD = depth1; depth1 = depth2; depth2 = tmpD;
        }

        if (screenX2 < 0 || screenX1 >= width) return;
        screenX1 = Math.max(0, screenX1);
        screenX2 = Math.min(width - 1, screenX2);

        if (screenX1 >= screenX2) return;

        // Get sector info from the linedef
        GameMap.LineDef line = map.getLineDefs()[seg.lineDef()];
        int sideIdx = (seg.direction() == 0) ? line.rightSideDef() : line.leftSideDef();
        if (sideIdx == 0xFFFF || sideIdx >= map.getSideDefs().length) return;

        GameMap.SideDef side = map.getSideDefs()[sideIdx];
        if (side.sector() >= map.getSectors().length) return;
        GameMap.Sector frontSector = map.getSectors()[side.sector()];

        // Check for two-sided line (has back sector)
        GameMap.Sector backSector = null;
        int backSideIdx = (seg.direction() == 0) ? line.leftSideDef() : line.rightSideDef();
        if (backSideIdx != 0xFFFF && backSideIdx < map.getSideDefs().length) {
            GameMap.SideDef backSide = map.getSideDefs()[backSideIdx];
            if (backSide.sector() < map.getSectors().length) {
                backSector = map.getSectors()[backSide.sector()];
            }
        }

        // Wall color from texture name and light level
        int wallColor = Palette.colorForTexture(side.middleTexture(), frontSector.lightLevel());
        int ceilColor = Palette.colorForTexture(frontSector.ceilingTexture(), frontSector.lightLevel());
        int floorColor = Palette.colorForTexture(frontSector.floorTexture(), frontSector.lightLevel());

        // Draw columns
        for (int x = screenX1; x <= screenX2; x++) {
            if (columnDrawn[x]) continue;

            // Interpolate depth
            double t = (screenX2 == screenX1) ? 0 : (double) (x - screenX1) / (screenX2 - screenX1);
            double depth = depth1 + t * (depth2 - depth1);
            if (depth < 1) depth = 1;

            // Calculate wall top and bottom on screen
            double frontCeilScreenY = height / 2.0 - (frontSector.ceilingHeight() - playerZ) * focalLength / depth;
            double frontFloorScreenY = height / 2.0 - (frontSector.floorHeight() - playerZ) * focalLength / depth;

            int ceilY = Math.max(ceilingClip[x], (int) frontCeilScreenY);
            int floorY = Math.min(floorClip[x], (int) frontFloorScreenY);

            // Draw ceiling
            boolean isSky = frontSector.ceilingTexture().equals("F_SKY1");
            if (ceilY > ceilingClip[x]) {
                for (int y = ceilingClip[x]; y < ceilY && y < floorClip[x]; y++) {
                    if (y >= 0 && y < height) {
                        frameBuffer[y * width + x] = isSky ? skyColor(y) : ceilColor;
                    }
                }
            }

            // Draw floor
            if (floorY < floorClip[x]) {
                for (int y = Math.max(floorY + 1, ceilingClip[x]); y <= floorClip[x]; y++) {
                    if (y >= 0 && y < height) {
                        frameBuffer[y * width + x] = floorColor;
                    }
                }
            }

            if (backSector == null) {
                // One-sided line: solid wall
                drawWallColumn(x, ceilY, floorY, wallColor, depth);
                columnDrawn[x] = true;
                columnsRemaining--;
            } else {
                // Two-sided line: upper and lower walls with opening
                double backCeilScreenY = height / 2.0 - (backSector.ceilingHeight() - playerZ) * focalLength / depth;
                double backFloorScreenY = height / 2.0 - (backSector.floorHeight() - playerZ) * focalLength / depth;

                int backCeilY = (int) backCeilScreenY;
                int backFloorY = (int) backFloorScreenY;

                // Upper wall (front ceiling to back ceiling)
                if (frontSector.ceilingHeight() > backSector.ceilingHeight()) {
                    int upperColor = Palette.colorForTexture(side.upperTexture(), frontSector.lightLevel());
                    int upperTop = Math.max(ceilY, ceilingClip[x]);
                    int upperBot = Math.min(backCeilY, floorClip[x]);
                    drawWallColumn(x, upperTop, upperBot, upperColor, depth);
                    ceilingClip[x] = Math.max(ceilingClip[x], upperBot);
                }

                // Lower wall (back floor to front floor)
                if (frontSector.floorHeight() < backSector.floorHeight()) {
                    int lowerColor = Palette.colorForTexture(side.lowerTexture(), frontSector.lightLevel());
                    int lowerTop = Math.max(backFloorY, ceilingClip[x]);
                    int lowerBot = Math.min(floorY, floorClip[x]);
                    drawWallColumn(x, lowerTop, lowerBot, lowerColor, depth);
                    floorClip[x] = Math.min(floorClip[x], lowerTop);
                }

                // Update clips for the opening
                ceilingClip[x] = Math.max(ceilingClip[x], Math.max(ceilY, backCeilY));
                floorClip[x] = Math.min(floorClip[x], Math.min(floorY, backFloorY));
            }
        }
    }

    private void drawWallColumn(int x, int top, int bottom, int color, double depth) {
        // Add depth-based darkening for atmosphere
        double depthFade = Math.max(0.15, 1.0 - depth / 2000.0);
        int r = (int) (((color >> 16) & 0xFF) * depthFade);
        int g = (int) (((color >> 8) & 0xFF) * depthFade);
        int b = (int) ((color & 0xFF) * depthFade);
        int fadedColor = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);

        for (int y = Math.max(0, top); y <= Math.min(height - 1, bottom); y++) {
            frameBuffer[y * width + x] = fadedColor;
        }
    }

    private int skyColor(int y) {
        double t = (double) y / (height / 2.0);
        int r = (int) (0x20 + t * 0x40);
        int g = (int) (0x40 + t * 0x30);
        int b = (int) (0xA0 + t * 0x40);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
