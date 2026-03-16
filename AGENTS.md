# Kestra Doom Plugin

## What

Plugin that runs the Doom engine inside a Kestra workflow. Loads a standard Doom WAD file, renders the game using a BSP-based software renderer (the original Doom algorithm), and outputs an animated GIF.

## Why

Because Doom runs everywhere — even in your data pipelines. This is a fun challenge in the tradition of porting Doom to pregnancy tests, printers, and PDF files.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `doom` — Kestra task
- `doom.engine` — Pure Java Doom engine (WAD parser, BSP renderer, demo playback, GIF encoder)

### Key Plugin Classes

- `io.kestra.plugin.doom.Doom` — Main Kestra task
- `io.kestra.plugin.doom.engine.WadFile` — WAD file parser
- `io.kestra.plugin.doom.engine.GameMap` — Map data structures and loader
- `io.kestra.plugin.doom.engine.BspRenderer` — BSP tree software renderer
- `io.kestra.plugin.doom.engine.DoomGame` — Game loop and demo playback
- `io.kestra.plugin.doom.engine.Palette` — Color palette handling
- `io.kestra.plugin.doom.engine.GifSequenceWriter` — Animated GIF encoder

### Project Structure

```
plugin-doom/
├── src/main/java/io/kestra/plugin/doom/
│   ├── Doom.java                    (Kestra task)
│   ├── package-info.java
│   └── engine/
│       ├── WadFile.java             (WAD parser)
│       ├── GameMap.java             (Map loader)
│       ├── BspRenderer.java         (BSP renderer)
│       ├── DoomGame.java            (Game coordinator)
│       ├── Palette.java             (Colors)
│       └── GifSequenceWriter.java   (GIF output)
├── src/test/java/io/kestra/plugin/doom/
├── build.gradle
└── README.md
```

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```
