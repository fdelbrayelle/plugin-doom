package io.kestra.plugin.doom;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.plugin.doom.engine.DoomGame;
import io.kestra.plugin.doom.engine.GifSequenceWriter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Play Doom",
    description = "Runs the Doom engine on a WAD file, plays back a demo or auto-walks through a map, " +
        "and outputs an animated GIF. Because Doom runs everywhere — even in Kestra."
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Play E1M1 from a WAD file and generate a GIF",
            code = {
                "wadFile: \"{{ inputs.wadFile }}\"",
                "map: \"E1M1\"",
                "frames: 350",
                "width: 320",
                "height: 200"
            }
        )
    }
)
public class Doom extends Task implements RunnableTask<Doom.Output> {

    @Schema(title = "WAD file", description = "URI to a Doom WAD file (IWAD or PWAD) in Kestra internal storage.")
    private Property<String> wadFile;

    @Schema(title = "Map name", description = "Map to load, e.g. E1M1 for Doom 1 or MAP01 for Doom 2.")
    @Builder.Default
    private Property<String> map = Property.of("E1M1");

    @Schema(title = "Number of game tics to simulate", description = "Each tic is ~1/35 second. Default 350 = ~10 seconds.")
    @Builder.Default
    private Property<Integer> frames = Property.of(350);

    @Schema(title = "Capture every N tics", description = "Capture a frame every N game tics. Lower = smoother GIF but larger file.")
    @Builder.Default
    private Property<Integer> captureEvery = Property.of(3);

    @Schema(title = "Screen width in pixels")
    @Builder.Default
    private Property<Integer> width = Property.of(320);

    @Schema(title = "Screen height in pixels")
    @Builder.Default
    private Property<Integer> height = Property.of(200);

    @Schema(title = "GIF frame delay in milliseconds")
    @Builder.Default
    private Property<Integer> frameDelay = Property.of(85);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String wadUri = runContext.render(wadFile).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("wadFile is required"));
        String mapName = runContext.render(map).as(String.class).orElse("E1M1");
        int totalFrames = runContext.render(frames).as(Integer.class).orElse(350);
        int captureN = runContext.render(captureEvery).as(Integer.class).orElse(3);
        int w = runContext.render(width).as(Integer.class).orElse(320);
        int h = runContext.render(height).as(Integer.class).orElse(200);
        int delay = runContext.render(frameDelay).as(Integer.class).orElse(85);

        logger.info("DOOM: Loading WAD file, map={}, frames={}, resolution={}x{}", mapName, totalFrames, w, h);

        // Download WAD from Kestra storage to temp file
        Path wadPath = downloadWad(runContext, wadUri);

        try (DoomGame game = new DoomGame(wadPath, mapName, w, h)) {
            logger.info("DOOM: Map loaded, starting render...");

            List<BufferedImage> renderedFrames = game.run(totalFrames, captureN);
            logger.info("DOOM: Rendered {} frames", renderedFrames.size());

            // Encode as animated GIF
            Path gifPath = Files.createTempFile("doom-", ".gif");
            try (FileImageOutputStream gifOut = new FileImageOutputStream(gifPath.toFile());
                 GifSequenceWriter gifWriter = new GifSequenceWriter(gifOut, BufferedImage.TYPE_INT_ARGB, delay, true)) {
                for (BufferedImage frame : renderedFrames) {
                    gifWriter.writeFrame(frame);
                }
            }

            // Store GIF in Kestra internal storage
            URI gifUri;
            try (InputStream gifIn = Files.newInputStream(gifPath)) {
                gifUri = runContext.storage().putFile(gifIn, "doom.gif");
            }
            Files.deleteIfExists(gifPath);
            Files.deleteIfExists(wadPath);

            logger.info("DOOM: GIF saved to {}", gifUri);

            return Output.builder()
                .gif(gifUri)
                .framesRendered(renderedFrames.size())
                .build();
        }
    }

    private Path downloadWad(RunContext runContext, String wadUri) throws Exception {
        Path wadPath = Files.createTempFile("doom-", ".wad");
        try (InputStream in = runContext.storage().getFile(URI.create(wadUri));
             OutputStream out = Files.newOutputStream(wadPath)) {
            in.transferTo(out);
        }
        return wadPath;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the generated animated GIF")
        private final URI gif;

        @Schema(title = "Number of frames rendered")
        private final Integer framesRendered;
    }
}
