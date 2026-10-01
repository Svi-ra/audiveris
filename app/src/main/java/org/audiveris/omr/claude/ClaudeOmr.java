//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       C l a u d e O m r                                        //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.claude;

import org.audiveris.omr.OMR;
import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.image.ImageLoading;
import org.audiveris.omr.util.FileUtil;

import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.mxl.Mxl;
import org.audiveris.proxymusic.mxl.RootFile;
import org.audiveris.proxymusic.util.Marshalling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Class <code>ClaudeOmr</code> is the entry point of the <b>experimental</b> Claude vision OMR
 * mode.
 * <p>
 * This mode is a two-phase hand-off with the Claude Code session the user is working in.
 * No Anthropic API key and no network call is involved on Audiveris side:
 * <ol>
 * <li><b>prepare</b>: Audiveris loads the input file (any format it supports, including
 * multi-page PDF or TIFF), writes each page as a PNG image, detects the page layout with the
 * first engine steps to write an annotated overview and pitch-labelled detail tiles (see
 * {@link ClaudeLayout}; overlapping horizontal strips are written instead when no layout is
 * detected) and writes a <code>request.md</code> file that specifies the expected description
 * format.
 * <li>Claude Code views these images with its own vision capability and writes the
 * <code>&lt;radix&gt;.claude.json</code> score description.
 * <li><b>import</b>: Audiveris reads this description and, via {@link ClaudeScoreBuilder} and the
 * ProxyMusic marshalling, writes the MusicXML file.
 * </ol>
 * The regular Audiveris OMR pipeline is not involved and remains unchanged.
 *
 * @author Audiveris contributors
 */
public abstract class ClaudeOmr
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ClaudeOmr.class);

    /** File extension of the score description written by Claude. */
    public static final String DESCRIPTION_EXTENSION = ".claude.json";

    /** Suffix of the folder that gathers the material prepared for Claude. */
    public static final String FOLDER_SUFFIX = "-claude";

    /** Name of the request file written in the prepared folder. */
    public static final String REQUEST_FILE_NAME = "request.md";

    /** Page height (in pixels) above which overlapping strips are also provided. */
    private static final int STRIP_THRESHOLD = 1600;

    /** Target height of one strip. */
    private static final int STRIP_HEIGHT = 1200;

    /** Vertical overlap between consecutive strips. */
    private static final int STRIP_OVERLAP = 200;

    //~ Constructors -------------------------------------------------------------------------------

    private ClaudeOmr ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //----------------//
    // getRadixOfJson //
    //----------------//
    /**
     * Report the radix of a description file name ("foo.claude.json" &rarr; "foo").
     *
     * @param jsonPath path to description file
     * @return the radix
     */
    public static String getRadixOfJson (Path jsonPath)
    {
        final String name = jsonPath.getFileName().toString();

        if (name.endsWith(DESCRIPTION_EXTENSION)) {
            return name.substring(0, name.length() - DESCRIPTION_EXTENSION.length());
        }

        return FileUtil.getNameSansExtension(jsonPath);
    }

    //-------------//
    // importScore //
    //-------------//
    /**
     * Convert a Claude score description into a MusicXML file.
     *
     * @param jsonPath     path to the <code>.claude.json</code> description
     * @param outputFolder target folder, or null for the description folder
     * @param compressed   true for .mxl output, false for plain .xml
     * @return path to the written MusicXML file
     * @throws Exception if description is invalid or file cannot be written
     */
    public static Path importScore (Path jsonPath,
                                    Path outputFolder,
                                    boolean compressed)
        throws Exception
    {
        final String radix = getRadixOfJson(jsonPath);
        final Path folder = (outputFolder != null) ? outputFolder
                : jsonPath.toAbsolutePath().getParent();
        Files.createDirectories(folder);

        final String text = Files.readString(jsonPath, StandardCharsets.UTF_8);
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder(
                WellKnowns.TOOL_NAME + " " + WellKnowns.TOOL_REF + " (Claude vision, experimental)");
        final ScorePartwise scorePartwise = builder.build(Json.parse(text));

        final Path target = folder.resolve(
                radix + (compressed ? OMR.COMPRESSED_SCORE_EXTENSION : OMR.SCORE_EXTENSION));
        writeMusicXML(scorePartwise, target, radix, compressed);

        final List<String> warnings = builder.getWarnings();

        if (warnings.isEmpty()) {
            logger.info("Claude OMR: {} converted to {} with no warning", jsonPath, target);
        } else {
            logger.info(
                    "Claude OMR: {} converted to {} with {} warning(s)",
                    jsonPath,
                    target,
                    warnings.size());
        }

        return target;
    }

    //---------//
    // prepare //
    //---------//
    /**
     * Prepare the material for Claude vision recognition of an input image file.
     *
     * @param inputPath    path to input file (image, PDF, ...)
     * @param outputFolder base output folder, or null for the input folder
     * @param sheetIds     ids of sheets to export, or null/empty for all
     * @return the folder where material has been written
     * @throws Exception if input cannot be loaded or files cannot be written
     */
    public static Path prepare (Path inputPath,
                                Path outputFolder,
                                java.util.Collection<Integer> sheetIds)
        throws Exception
    {
        final Path input = inputPath.toAbsolutePath();
        final String radix = FileUtil.getNameSansExtension(input);
        final Path base = (outputFolder != null) ? outputFolder : input.getParent();
        final Path folder = base.resolve(radix + FOLDER_SUFFIX);
        Files.createDirectories(folder);

        final ImageLoading.Loader loader = ImageLoading.getLoader(input);

        if (loader == null) {
            throw new IllegalArgumentException("Cannot load images from " + input);
        }

        final List<String> lines = new ArrayList<>();
        int nextMeasure = 1;

        try {
            final int count = loader.getImageCount();

            for (int id = 1; id <= count; id++) {
                if ((sheetIds != null) && !sheetIds.isEmpty() && !sheetIds.contains(id)) {
                    continue;
                }

                final BufferedImage image = toRgb(loader.getImage(id));

                if (image == null) {
                    logger.warn("Claude OMR: could not load image #{} of {}", id, input);
                    continue;
                }

                final String pageName = "page-" + id + ".png";
                ImageIO.write(image, "png", folder.resolve(pageName).toFile());
                lines.add(
                        "- Page " + id + ": `" + pageName + "` (" + image.getWidth() + "x"
                                + image.getHeight() + ")");

                // Layout detection by the first engine steps (best effort)
                final ClaudeLayout.PageInfo layout = ClaudeLayout.analyze(image, folder, id);

                if (layout != null) {
                    nextMeasure = ClaudeLayout.writeMaterial(
                            layout,
                            image,
                            folder,
                            id,
                            nextMeasure,
                            lines);
                } else {
                    lines.add("  - no layout detected on this page, use the detail strips");

                    for (String strip : writeStrips(image, folder, id)) {
                        lines.add("  - detail strip: " + strip);
                    }
                }
            }
        } finally {
            loader.dispose();
        }

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("No page image could be extracted from " + input);
        }

        final Path jsonPath = base.resolve(radix + DESCRIPTION_EXTENSION);
        final String request = ClaudeRequest.build(input, folder, lines, jsonPath);
        Files.writeString(folder.resolve(REQUEST_FILE_NAME), request, StandardCharsets.UTF_8);

        logger.info("Claude OMR: material for {} prepared in {}", input, folder);
        logger.info("Claude OMR: expected description file is {}", jsonPath);

        return folder;
    }

    //-------//
    // toRgb //
    //-------//
    /**
     * Make sure the image can be written as a standard PNG (binary, gray or color).
     */
    private static BufferedImage toRgb (BufferedImage img)
    {
        if (img == null) {
            return null;
        }

        switch (img.getType()) {
            case BufferedImage.TYPE_BYTE_BINARY, BufferedImage.TYPE_BYTE_GRAY,
                    BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR -> {
                return img;
            }
            default -> {
                final BufferedImage rgb = new BufferedImage(
                        img.getWidth(),
                        img.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                final Graphics2D g = rgb.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();

                return rgb;
            }
        }
    }

    //---------------//
    // writeMusicXML //
    //---------------//
    /**
     * Marshal the ScorePartwise exactly as {@link org.audiveris.omr.score.ScoreExporter} does.
     */
    private static void writeMusicXML (ScorePartwise scorePartwise,
                                       Path target,
                                       String scoreName,
                                       boolean compressed)
        throws Exception
    {
        try (OutputStream os = Files.newOutputStream(target)) {
            if (compressed) {
                final Mxl.Output mof = new Mxl.Output(os);
                final OutputStream zos = mof.getOutputStream();
                mof.addEntry(
                        new RootFile(scoreName + OMR.SCORE_EXTENSION, RootFile.MUSICXML_MEDIA_TYPE));
                Marshalling.marshal(scorePartwise, zos, true, 2);
                mof.close();
            } else {
                Marshalling.marshal(scorePartwise, os, true, 2);
            }
        }
    }

    //-------------//
    // writeStrips //
    //-------------//
    /**
     * For a tall page, write overlapping horizontal strips at full resolution, so that the
     * vision model can read small details (accidentals, dots, ledger lines).
     */
    private static List<String> writeStrips (BufferedImage image,
                                             Path folder,
                                             int id)
        throws Exception
    {
        final List<String> names = new ArrayList<>();
        final int height = image.getHeight();

        if (height <= STRIP_THRESHOLD) {
            return names;
        }

        final int step = STRIP_HEIGHT - STRIP_OVERLAP;
        int index = 0;

        for (int y = 0; y < height; y += step) {
            final int h = Math.min(STRIP_HEIGHT, height - y);
            final BufferedImage strip = image.getSubimage(0, y, image.getWidth(), h);
            final String name = "page-" + id + "-strip-" + (++index) + ".png";
            ImageIO.write(strip, "png", folder.resolve(name).toFile());
            names.add("`" + name + "` (page rows " + y + " to " + (y + h) + ")");

            if ((y + h) >= height) {
                break;
            }
        }

        return names;
    }
}
