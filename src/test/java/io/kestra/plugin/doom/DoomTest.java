package io.kestra.plugin.doom;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.doom.engine.DoomGame;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class DoomTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void engineRendersFramesFromTestWad() throws Exception {
        Path wadPath = TestWadGenerator.generate();

        try (DoomGame game = new DoomGame(wadPath, "E1M1", 320, 200)) {
            List<BufferedImage> frames = game.run(35, 1);
            assertThat(frames.size(), is(35));
            assertThat(frames.get(0).getWidth(), is(320));
            assertThat(frames.get(0).getHeight(), is(200));
        } finally {
            Files.deleteIfExists(wadPath);
        }
    }

    @Test
    void taskProducesGif() throws Exception {
        Path wadPath = TestWadGenerator.generate();
        RunContext runContext = runContextFactory.of(Map.of());

        // Store the test WAD in Kestra storage
        URI wadUri;
        try (var wadIn = Files.newInputStream(wadPath)) {
            wadUri = runContext.storage().putFile(wadIn, "test.wad");
        }

        Doom task = Doom.builder()
            .wadFile(Property.of(wadUri.toString()))
            .map(Property.of("E1M1"))
            .frames(Property.of(70))
            .captureEvery(Property.of(5))
            .width(Property.of(320))
            .height(Property.of(200))
            .frameDelay(Property.of(100))
            .build();

        Doom.Output output = task.run(runContext);

        assertThat(output.getGif(), is(notNullValue()));
        assertThat(output.getGif().toString(), containsString("doom.gif"));
        assertThat(output.getFramesRendered(), is(greaterThan(0)));

        Files.deleteIfExists(wadPath);
    }
}
