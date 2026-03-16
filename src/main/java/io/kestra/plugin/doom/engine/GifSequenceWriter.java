package io.kestra.plugin.doom.engine;

import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;

/**
 * Writes a sequence of BufferedImages as an animated GIF.
 */
public class GifSequenceWriter implements AutoCloseable {
    private final ImageWriter writer;
    private final ImageWriteParam params;
    private final IIOMetadata metadata;

    public GifSequenceWriter(ImageOutputStream out, int imageType, int delayMs, boolean loop) throws IOException {
        writer = ImageIO.getImageWritersBySuffix("gif").next();
        params = writer.getDefaultWriteParam();

        ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(imageType);
        metadata = writer.getDefaultImageMetadata(typeSpecifier, params);

        configureMetadata(delayMs / 10, loop); // GIF delay is in centiseconds

        writer.setOutput(out);
        writer.prepareWriteSequence(null);
    }

    private void configureMetadata(int delayCentiseconds, boolean loop) throws IIOInvalidTreeException {
        String metaFormatName = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metaFormatName);

        IIOMetadataNode graphicsControlExtension = getNode(root, "GraphicControlExtension");
        graphicsControlExtension.setAttribute("disposalMethod", "none");
        graphicsControlExtension.setAttribute("userInputFlag", "FALSE");
        graphicsControlExtension.setAttribute("transparentColorFlag", "FALSE");
        graphicsControlExtension.setAttribute("delayTime", String.valueOf(Math.max(1, delayCentiseconds)));
        graphicsControlExtension.setAttribute("transparentColorIndex", "0");

        if (loop) {
            IIOMetadataNode appExtensions = getNode(root, "ApplicationExtensions");
            IIOMetadataNode appExtension = new IIOMetadataNode("ApplicationExtension");
            appExtension.setAttribute("applicationID", "NETSCAPE");
            appExtension.setAttribute("authenticationCode", "2.0");
            appExtension.setUserObject(new byte[]{0x1, 0x0, 0x0}); // loop forever
            appExtensions.appendChild(appExtension);
        }

        metadata.setFromTree(metaFormatName, root);
    }

    public void writeFrame(RenderedImage img) throws IOException {
        writer.writeToSequence(new IIOImage(img, null, metadata), params);
    }

    private static IIOMetadataNode getNode(IIOMetadataNode root, String nodeName) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(nodeName)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(nodeName);
        root.appendChild(node);
        return node;
    }

    @Override
    public void close() throws IOException {
        writer.endWriteSequence();
        writer.dispose();
    }
}
