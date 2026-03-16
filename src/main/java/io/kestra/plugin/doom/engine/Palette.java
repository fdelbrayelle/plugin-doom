package io.kestra.plugin.doom.engine;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Doom color palette loaded from the PLAYPAL lump.
 * Also provides texture-name-to-color mapping for simplified rendering.
 */
public class Palette {
    private final int[] colors; // 256 ARGB colors
    private static final Map<String, Integer> TEXTURE_COLORS = new HashMap<>();

    static {
        // Common Doom wall texture approximate colors
        TEXTURE_COLORS.put("STARTAN", 0xFF8B7355);  // tan/brown
        TEXTURE_COLORS.put("STARG",   0xFF6B6B6B);  // gray
        TEXTURE_COLORS.put("STONE",   0xFF808080);  // gray stone
        TEXTURE_COLORS.put("ROCK",    0xFF6B5B4B);  // brown rock
        TEXTURE_COLORS.put("WOOD",    0xFF6B4B2B);  // brown wood
        TEXTURE_COLORS.put("METAL",   0xFF9B9B9B);  // silver
        TEXTURE_COLORS.put("SILVER",  0xFFA0A0A0);  // silver
        TEXTURE_COLORS.put("BRICK",   0xFF8B4B3B);  // red-brown brick
        TEXTURE_COLORS.put("MARBLE",  0xFFC0B8B0);  // white marble
        TEXTURE_COLORS.put("COMP",    0xFFA0A0B0);  // computer panel
        TEXTURE_COLORS.put("LITE",    0xFFD0D0D0);  // light panel
        TEXTURE_COLORS.put("DOOR",    0xFF6B5B4B);  // door brown
        TEXTURE_COLORS.put("BROWN",   0xFF7B5B3B);  // brown
        TEXTURE_COLORS.put("GRAY",    0xFF808080);  // gray
        TEXTURE_COLORS.put("PIPE",    0xFF6B6B5B);  // pipe gray-green
        TEXTURE_COLORS.put("CEMENT",  0xFF909088);  // cement
        TEXTURE_COLORS.put("SUPPORT", 0xFF6B6B6B);  // support structure
        TEXTURE_COLORS.put("STEP",    0xFF7B6B5B);  // step brown
        TEXTURE_COLORS.put("PLAT",    0xFF6B6B7B);  // platform
        TEXTURE_COLORS.put("TEKWALL", 0xFF2B5B3B);  // tech green
        TEXTURE_COLORS.put("TEKGREN", 0xFF2B6B3B);  // tech green
        TEXTURE_COLORS.put("ICKWALL", 0xFF5B7B4B);  // icky green
        TEXTURE_COLORS.put("SKIN",    0xFF8B3B3B);  // red skin
        TEXTURE_COLORS.put("SK_",     0xFF8B3B3B);  // skin variant
        TEXTURE_COLORS.put("SP_",     0xFF7B4B3B);  // special
        TEXTURE_COLORS.put("EXIT",    0xFF5B8B5B);  // exit green
        TEXTURE_COLORS.put("SW1",     0xFF8B8B7B);  // switch
        TEXTURE_COLORS.put("SW2",     0xFF7B8B5B);  // switch active
        // Common flats
        TEXTURE_COLORS.put("FLOOR",   0xFF6B5B4B);  // floor brown
        TEXTURE_COLORS.put("CEIL",    0xFF808080);  // ceiling gray
        TEXTURE_COLORS.put("FLAT",    0xFF6B6B5B);  // flat
        TEXTURE_COLORS.put("NUKAGE",  0xFF2B8B2B);  // green nukage
        TEXTURE_COLORS.put("BLOOD",   0xFF8B2B2B);  // red blood
        TEXTURE_COLORS.put("FWATER",  0xFF2B4B8B);  // blue water
        TEXTURE_COLORS.put("LAVA",    0xFFCB4B1B);  // orange lava
        TEXTURE_COLORS.put("SLIME",   0xFF3B7B2B);  // green slime
        TEXTURE_COLORS.put("GRASS",   0xFF3B6B2B);  // grass green
        TEXTURE_COLORS.put("CRATOP",  0xFF7B6B3B);  // crate top
        TEXTURE_COLORS.put("GATE",    0xFF5B5BBB);  // gate blue
        TEXTURE_COLORS.put("DEM1",    0xFF5B4B3B);  // demon texture
        TEXTURE_COLORS.put("F_SKY1",  0xFF3B5B9B);  // sky blue
    }

    public Palette(int[] colors) {
        this.colors = colors;
    }

    public static Palette fromWad(WadFile wad) throws IOException {
        byte[] playpal = wad.readLump("PLAYPAL");
        int[] colors = new int[256];
        if (playpal != null && playpal.length >= 768) {
            for (int i = 0; i < 256; i++) {
                int r = playpal[i * 3] & 0xFF;
                int g = playpal[i * 3 + 1] & 0xFF;
                int b = playpal[i * 3 + 2] & 0xFF;
                colors[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            // Fallback: generate a basic palette
            for (int i = 0; i < 256; i++) {
                colors[i] = 0xFF000000 | (i << 16) | (i << 8) | i;
            }
        }
        return new Palette(colors);
    }

    public int getColor(int index) {
        return colors[index & 0xFF];
    }

    /**
     * Get an approximate color for a texture name, adjusted by light level.
     */
    public static int colorForTexture(String textureName, int lightLevel) {
        if (textureName == null || textureName.isEmpty() || textureName.equals("-")) {
            return 0xFF404040;
        }

        int baseColor = 0xFF808080; // default gray
        String upper = textureName.toUpperCase();

        // Try exact match first, then prefix matching
        for (Map.Entry<String, Integer> entry : TEXTURE_COLORS.entrySet()) {
            if (upper.startsWith(entry.getKey())) {
                baseColor = entry.getValue();
                break;
            }
        }

        // Apply light level (0-255)
        double light = Math.max(0.1, lightLevel / 255.0);
        int r = (int) (((baseColor >> 16) & 0xFF) * light);
        int g = (int) (((baseColor >> 8) & 0xFF) * light);
        int b = (int) ((baseColor & 0xFF) * light);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /**
     * Apply light level to an arbitrary color.
     */
    public static int applyLight(int color, int lightLevel) {
        double light = Math.max(0.05, lightLevel / 255.0);
        int r = (int) (((color >> 16) & 0xFF) * light);
        int g = (int) (((color >> 8) & 0xFF) * light);
        int b = (int) ((color & 0xFF) * light);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
