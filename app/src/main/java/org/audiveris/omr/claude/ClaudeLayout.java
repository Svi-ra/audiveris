//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    C l a u d e L a y o u t                                     //
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

import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.grid.LineInfo;
import org.audiveris.omr.sheet.header.StaffHeader;
import org.audiveris.omr.sig.inter.AbstractTimeInter;
import org.audiveris.omr.sig.inter.BarlineInter;
import org.audiveris.omr.sig.inter.ClefInter;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.util.HorizontalSide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Class <code>ClaudeLayout</code> uses the first steps of the regular Audiveris engine
 * (LOAD, BINARY, SCALE, GRID and HEADERS) to detect the page layout (systems, staves, barlines,
 * clefs, key and time signatures) and writes compact, pitch-labelled detail tiles for Claude.
 * <p>
 * The purpose is to minimize the number of images Claude must look at and the guess-work on
 * pitches and measures: each tile covers a few measures of a few staves, is scaled so that
 * staff spaces are clearly visible, carries the pitch name of every line and space (according
 * to the detected clef) and the provisional measure numbers.
 * <p>
 * Detection is best effort: if the engine fails on a page, the caller falls back to plain strips.
 *
 * @author Audiveris contributors
 */
public abstract class ClaudeLayout
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ClaudeLayout.class);

    /** Desired interline in tiles, in pixels. */
    private static final double TARGET_INTERLINE = 28;

    /** Maximum scaling applied to the page in tiles. */
    private static final double MAX_SCALE = 3.0;

    /** Maximum content size of a tile, in pixels (Claude downsizes images beyond ~1568). */
    private static final int MAX_CONTENT = 1400;

    /** Tiles may be scaled down to this ratio of the desired scale to avoid an extra tile. */
    private static final double MIN_SHRINK = 0.85;

    /** Width of each side margin holding pitch labels. */
    private static final int LABEL_WIDTH = 64;

    /** Height of top band holding measure numbers. */
    private static final int TOP_HEIGHT = 26;

    /** Room kept above and below a staff, in interlines (ledger lines). */
    private static final double STAFF_MARGIN = 4.5;

    /** Highest pitch position labelled outside a staff (5 = first space below staff). */
    private static final int MAX_LABEL_POSITION = 10;

    /** Barlines closer than this (in interlines) are considered one boundary (double bar). */
    private static final double BAR_MERGE = 2.5;

    /** Maximum upscaling tried for low-resolution images. */
    private static final int MAX_UPSCALING = 2;

    /** Maximum pixel count of an upscaled copy (engine default limit is 20 M). */
    private static final long MAX_ANALYSIS_PIXELS = 20_000_000L;

    /** Maximum long edge of the annotated overview image. */
    private static final int OVERVIEW_SIZE = 1560;

    private static final Color LINE_COLOR = new Color(200, 0, 0);

    private static final Color SPACE_COLOR = new Color(0, 90, 220);

    private static final Color LEDGER_GUIDE = new Color(240, 150, 150);

    private static final Color MEASURE_COLOR = new Color(0, 130, 0);

    //~ Constructors -------------------------------------------------------------------------------

    private ClaudeLayout ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //---------//
    // analyze //
    //---------//
    /**
     * Run the first engine steps on a page image and report its layout.
     * <p>
     * Images of low resolution (typically from the web) are rejected by the engine because of
     * a too small interline: in that case the analysis is retried on an upscaled copy and the
     * resulting geometry is mapped back to the original image coordinates.
     *
     * @param image  the page image
     * @param folder folder where a temporary copy of the image can be written
     * @param id     page id, for messages
     * @return the page layout, or null if no layout could be detected
     */
    public static PageInfo analyze (BufferedImage image,
                                    Path folder,
                                    int id)
    {
        final long pixels = (long) image.getWidth() * image.getHeight();

        for (int factor = 1; factor <= MAX_UPSCALING; factor++) {
            if ((factor > 1) && ((pixels * factor * factor) > MAX_ANALYSIS_PIXELS)) {
                break;
            }

            final Path temp = folder.resolve(".layout-page-" + id + ".png");

            try {
                ImageIO.write(upscaled(image, factor), "png", temp.toFile());

                final PageInfo page = analyzeFile(temp, factor, id);

                if (page != null) {
                    if (factor > 1) {
                        logger.info("Claude OMR: page {} layout detected on a x{} copy", id, factor);
                    }

                    return page;
                }
            } catch (Throwable ex) {
                logger.warn("Claude OMR: layout detection error on page {}: {}", id, ex.toString());
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }

        logger.warn("Claude OMR: no layout detected on page {}, falling back to strips", id);

        return null;
    }

    //-------------//
    // analyzeFile //
    //-------------//
    /**
     * Run GRID (and HEADERS if possible) on a single-image file.
     *
     * @return the layout in original image coordinates, or null
     */
    private static PageInfo analyzeFile (Path file,
                                         int factor,
                                         int id)
    {
        final Book book = new Book(file);

        try {
            book.createStubs();

            final SheetStub stub = book.getStubs().get(0);

            try {
                stub.reachStep(OmrStep.GRID, false);
            } catch (Throwable ex) {
                logger.info("Claude OMR: page {} at x{}: {}", id, factor, ex.toString());

                return null;
            }

            boolean headers = true;

            try {
                stub.reachStep(OmrStep.HEADERS, false);
            } catch (Throwable ex) {
                logger.info("Claude OMR: no clef/key/time detected on page {}", id);
                headers = false;
            }

            final PageInfo page = buildPage(stub.getSheet(), headers, factor);

            return page.systems.isEmpty() ? null : page;
        } finally {
            try {
                book.close(null);
            } catch (Throwable ex) {
                logger.debug("Error closing book {}", ex.toString());
            }
        }
    }

    //----------//
    // upscaled //
    //----------//
    private static BufferedImage upscaled (BufferedImage image,
                                           int factor)
    {
        if (factor == 1) {
            return image;
        }

        final BufferedImage big = new BufferedImage(
                image.getWidth() * factor,
                image.getHeight() * factor,
                BufferedImage.TYPE_BYTE_GRAY);
        final Graphics2D g = big.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, big.getWidth(), big.getHeight(), null);
        g.dispose();

        return big;
    }

    //-----------//
    // buildPage //
    //-----------//
    private static PageInfo buildPage (Sheet sheet,
                                       boolean headers,
                                       int factor)
    {
        final PageInfo page = new PageInfo(
                Math.max(1, (int) Math.round((double) sheet.getInterline() / factor)));

        for (SystemInfo system : sheet.getSystems()) {
            final SystemGeo sys = new SystemGeo(system.getId());
            final List<Staff> staves = system.getStaves();

            for (Staff staff : staves) {
                if (staff.isTablature() || staff.isOneLineStaff()) {
                    // Keep the slot for numbering, but no pitch labels
                    sys.staves.add(new StaffGeo(staff, factor, null, null, null));
                    continue;
                }

                final StaffHeader header = headers ? staff.getHeader() : null;
                final ClefInter clef = (header != null) ? header.clef : null;
                final Integer fifths = ((header != null) && (header.key != null))
                        ? header.key.getFifths()
                        : null;
                final AbstractTimeInter time = (header != null) ? header.time : null;
                final StaffGeo geo = new StaffGeo(
                        staff,
                        factor,
                        clef,
                        fifths,
                        (time != null) ? time.getTimeRational().toString() : null);
                geo.noKey = (header != null) && (header.key == null);
                sys.staves.add(geo);
            }

            for (Part part : system.getParts()) {
                if (part.getStaves().size() > 1) {
                    final int first = staves.indexOf(part.getStaves().get(0)) + 1;
                    final int last = first + part.getStaves().size() - 1;
                    sys.groups.add(new int[] { first, last });
                }
            }

            final List<Integer> bounds = new ArrayList<>();

            for (int x : measureBounds(staves.get(0), sheet.getInterline())) {
                bounds.add((int) Math.round((double) x / factor));
            }

            sys.bounds = bounds;
            page.systems.add(sys);
        }

        return page;
    }

    //---------------//
    // measureBounds //
    //---------------//
    /**
     * Report the abscissae delimiting the measures of a system, from the barlines detected on
     * its first staff. First value is the staff left side (header included), last value is the
     * staff right side.
     */
    private static List<Integer> measureBounds (Staff staff,
                                                int interline)
    {
        final int left = staff.getAbscissa(HorizontalSide.LEFT);
        final int right = staff.getAbscissa(HorizontalSide.RIGHT);
        final double merge = BAR_MERGE * interline;
        final List<Integer> xs = new ArrayList<>();

        for (BarlineInter bar : staff.getBarlines()) {
            xs.add(bar.getCenter().x);
        }

        xs.sort(null);

        final List<Integer> bounds = new ArrayList<>();
        bounds.add(left);

        for (int x : xs) {
            final int last = bounds.get(bounds.size() - 1);

            if ((x - left) < merge) {
                continue; // Barline at system start
            }

            if ((x - last) < merge) {
                bounds.set(bounds.size() - 1, x); // Double barline: keep the rightmost
            } else {
                bounds.add(x);
            }
        }

        if ((right - bounds.get(bounds.size() - 1)) < merge) {
            bounds.set(bounds.size() - 1, Math.max(right, bounds.get(bounds.size() - 1)));
        } else {
            bounds.add(right); // System not ended by a barline
        }

        return bounds;
    }

    //----------------//
    // writeMaterial //
    //----------------//
    /**
     * Write the annotated overview and the detail tiles of one page.
     *
     * @param page         the detected layout of the page
     * @param image        the page image (same coordinates as the layout)
     * @param folder       target folder
     * @param id           page id
     * @param firstMeasure provisional number of the first measure of the page
     * @param lines        (output) markdown lines describing the material
     * @return the provisional number of the first measure of next page
     * @throws Exception if an image cannot be written
     */
    public static int writeMaterial (PageInfo page,
                                     BufferedImage image,
                                     Path folder,
                                     int id,
                                     int firstMeasure,
                                     List<String> lines)
        throws Exception
    {
        final int interline = page.interline;
        final double scale = Math.max(1.0, Math.min(MAX_SCALE, TARGET_INTERLINE / interline));
        int measure = firstMeasure;

        // Number measures
        for (SystemGeo sys : page.systems) {
            sys.firstMeasure = measure;
            measure += sys.bounds.size() - 1;
        }

        final String overview = "page-" + id + "-overview.png";
        writeOverview(page, image, folder.resolve(overview));
        lines.add("  - annotated overview: `" + overview + "` (system, staff and measure numbers)");
        lines.add(
                "  - layout detected by Audiveris (verify it): interline " + interline + " px, "
                        + page.systems.size() + " system(s)");

        for (SystemGeo sys : page.systems) {
            final int count = sys.bounds.size() - 1;
            lines.add(
                    "  - system " + sys.id + ": " + sys.staves.size() + " staves, " + count
                            + " measure(s) m" + sys.firstMeasure + "-m"
                            + (sys.firstMeasure + count - 1));

            for (int i = 0; i < sys.staves.size(); i++) {
                lines.add("    - staff " + (i + 1) + ": " + sys.staves.get(i).describe());
            }

            if (!sys.groups.isEmpty()) {
                final StringBuilder sb = new StringBuilder("    - braced/multi-staff groups:");

                for (int[] g : sys.groups) {
                    sb.append(" staves ").append(g[0]).append('-').append(g[1]).append(';');
                }

                lines.add(sb.substring(0, sb.length() - 1));
            }

            lines.add("    - detail tiles:");

            final double minScale = scale * MIN_SHRINK;

            for (int[] staffRange : staffGroups(sys, interline, minScale)) {
                for (int[] measureRange : measureGroups(sys, minScale)) {
                    final String name = "page-" + id + "-s" + sys.id + "-m"
                            + (sys.firstMeasure + measureRange[0]) + "-"
                            + (sys.firstMeasure + measureRange[1]) + "-st" + (staffRange[0] + 1)
                            + "-" + (staffRange[1] + 1) + ".png";
                    writeTile(sys, image, interline, scale, staffRange, measureRange, folder
                            .resolve(name));
                    lines.add(
                            "      - `" + name + "`: measures m"
                                    + (sys.firstMeasure + measureRange[0]) + "-m"
                                    + (sys.firstMeasure + measureRange[1]) + ", staves "
                                    + (staffRange[0] + 1) + "-" + (staffRange[1] + 1));
                }
            }
        }

        return measure;
    }

    //---------------//
    // measureGroups //
    //---------------//
    /**
     * Gather consecutive measures (0-based indices) so that a tile stays below the size limit.
     * A single over-wide measure gets a tile of its own (it will be scaled down a bit).
     */
    private static List<int[]> measureGroups (SystemGeo sys,
                                              double scale)
    {
        final List<int[]> groups = new ArrayList<>();
        final List<Integer> b = sys.bounds;
        int start = 0;

        for (int m = 0; m < (b.size() - 1); m++) {
            final double width = (b.get(m + 1) - b.get(start)) * scale;

            if ((width > MAX_CONTENT) && (m > start)) {
                groups.add(new int[] { start, m - 1 });
                start = m;
            }
        }

        groups.add(new int[] { start, b.size() - 2 });

        return groups;
    }

    //-------------//
    // staffGroups //
    //-------------//
    /**
     * Gather consecutive staves (0-based indices) so that a tile stays below the size limit,
     * avoiding to split a multi-staff group (such as a piano grand staff) when possible.
     */
    private static List<int[]> staffGroups (SystemGeo sys,
                                            int interline,
                                            double scale)
    {
        final int n = sys.staves.size();

        // Units of staves that should not be split (1-based ranges in sys.groups)
        final List<int[]> units = new ArrayList<>();

        for (int i = 0; i < n;) {
            int last = i;

            for (int[] g : sys.groups) {
                if ((g[0] - 1) == i) {
                    last = g[1] - 1;
                }
            }

            units.add(new int[] { i, last });
            i = last + 1;
        }

        final List<int[]> groups = new ArrayList<>();
        int start = -1;
        int end = -1;

        for (int[] unit : units) {
            if (start < 0) {
                start = unit[0];
                end = unit[1];
            } else if ((bandHeight(sys, start, unit[1], interline) * scale) <= MAX_CONTENT) {
                end = unit[1];
            } else {
                groups.add(new int[] { start, end });
                start = unit[0];
                end = unit[1];
            }
        }

        if (start >= 0) {
            groups.add(new int[] { start, end });
        }

        return groups;
    }

    private static double bandHeight (SystemGeo sys,
                                      int first,
                                      int last,
                                      int interline)
    {
        return sys.staves.get(last).bottom(sys.staves.get(last).left) - sys.staves.get(first).top(
                sys.staves.get(first).left) + (2 * STAFF_MARGIN * interline);
    }

    //-----------//
    // writeTile //
    //-----------//
    private static void writeTile (SystemGeo sys,
                                   BufferedImage image,
                                   int interline,
                                   double scale,
                                   int[] staffRange,
                                   int[] measureRange,
                                   Path target)
        throws Exception
    {
        final StaffGeo firstStaff = sys.staves.get(staffRange[0]);
        final StaffGeo lastStaff = sys.staves.get(staffRange[1]);
        final int margin = (int) Math.round(STAFF_MARGIN * interline);

        // Source rectangle
        final int x0 = Math.max(0, sys.bounds.get(measureRange[0]) - interline);
        final int x1 = Math.min(image.getWidth(), sys.bounds.get(measureRange[1] + 1) + interline);
        final int y0 = Math.max(
                0,
                (int) Math.floor(Math.min(firstStaff.top(x0), firstStaff.top(x1))) - margin);
        final int y1 = Math.min(
                image.getHeight(),
                (int) Math.ceil(Math.max(lastStaff.bottom(x0), lastStaff.bottom(x1))) + margin);

        // Shrink if needed (very wide measure or very tall staff)
        final double s = Math.min(
                scale,
                Math.min((double) MAX_CONTENT / (x1 - x0), (double) MAX_CONTENT / (y1 - y0)));
        final int cw = (int) Math.round((x1 - x0) * s);
        final int ch = (int) Math.round((y1 - y0) * s);

        final BufferedImage tile = new BufferedImage(
                cw + (2 * LABEL_WIDTH),
                ch + TOP_HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = tile.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, tile.getWidth(), tile.getHeight());
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(image, LABEL_WIDTH, TOP_HEIGHT, LABEL_WIDTH + cw, TOP_HEIGHT + ch, x0, y0, x1,
                y1, null);

        // Measure numbers
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(MEASURE_COLOR);

        for (int m = measureRange[0]; m <= measureRange[1]; m++) {
            final int xs = LABEL_WIDTH + (int) Math.round((sys.bounds.get(m) - x0) * s);
            g.drawString("m" + (sys.firstMeasure + m), Math.max(LABEL_WIDTH, xs) + 4, 18);
            g.fillRect(xs, 4, 2, TOP_HEIGHT - 4);
        }

        // Pitch labels, staff per staff
        final Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        final Font staffFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        final double xMid = (x0 + x1) / 2.0;

        for (int i = staffRange[0]; i <= staffRange[1]; i++) {
            final StaffGeo staff = sys.staves.get(i);
            final double top = staff.top(xMid);
            final double step = (staff.bottom(xMid) - top) / 8.0;

            // Limits between this staff and its neighbors (labels must not mix)
            final double upper = (i > 0) ? (sys.staves.get(i - 1).bottom(xMid) + top) / 2
                    : Double.NEGATIVE_INFINITY;
            final double lower = (i < (sys.staves.size() - 1))
                    ? (staff.bottom(xMid) + sys.staves.get(i + 1).top(xMid)) / 2
                    : Double.POSITIVE_INFINITY;

            for (int p = -MAX_LABEL_POSITION; p <= MAX_LABEL_POSITION; p++) {
                final double y = top + ((p + 4) * step);

                if ((y <= upper) || (y >= lower) || (y < y0) || (y > y1)) {
                    continue;
                }

                final int ty = TOP_HEIGHT + (int) Math.round((y - y0) * s);
                final boolean isLine = (p % 2) == 0;
                final Color color = isLine ? LINE_COLOR : SPACE_COLOR;

                // Ledger line positions: faint dotted guide across the tile
                if (isLine && (Math.abs(p) > 4)) {
                    g.setColor(LEDGER_GUIDE);
                    g.setStroke(new BasicStroke(
                            1,
                            BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER,
                            1,
                            new float[] { 2, 6 },
                            0));
                    g.drawLine(LABEL_WIDTH, ty, LABEL_WIDTH + cw, ty);
                    g.setStroke(new BasicStroke(1));
                }

                // Lines are labelled in left margin, spaces in right margin (less crowded)
                g.setColor(color);

                if (isLine) {
                    g.drawLine(LABEL_WIDTH - 8, ty, LABEL_WIDTH - 1, ty);
                } else {
                    g.drawLine(LABEL_WIDTH + cw, ty, LABEL_WIDTH + cw + 7, ty);
                }

                final String name = staff.pitchName(p);

                if (name != null) {
                    g.setFont(labelFont);

                    final FontMetrics fm = g.getFontMetrics();
                    final int base = ty + (fm.getAscent() / 2) - 1;

                    if (isLine) {
                        g.drawString(name, LABEL_WIDTH - 10 - fm.stringWidth(name), base);
                    } else {
                        g.drawString(name, LABEL_WIDTH + cw + 10, base);
                    }
                }

                if (p == 0) {
                    // Staff number, right margin at middle line height (a line, so free there)
                    g.setFont(staffFont);
                    g.setColor(Color.BLACK);
                    g.drawString("S" + (i + 1), LABEL_WIDTH + cw + 38, ty + 5);
                }
            }
        }

        g.dispose();
        ImageIO.write(tile, "png", target.toFile());
    }

    //---------------//
    // writeOverview //
    //---------------//
    private static void writeOverview (PageInfo page,
                                       BufferedImage image,
                                       Path target)
        throws Exception
    {
        final double s = Math.min(
                1.0,
                (double) OVERVIEW_SIZE / Math.max(image.getWidth(), image.getHeight()));
        final int w = (int) Math.round(image.getWidth() * s);
        final int h = (int) Math.round(image.getHeight() * s);
        final BufferedImage overview = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = overview.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(image, 0, 0, w, h, null);

        final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 14);
        g.setFont(font);

        for (SystemGeo sys : page.systems) {
            final StaffGeo first = sys.staves.get(0);
            final StaffGeo last = sys.staves.get(sys.staves.size() - 1);

            // Barlines and measure numbers
            g.setColor(MEASURE_COLOR);

            for (int m = 0; m < (sys.bounds.size() - 1); m++) {
                final int x = (int) Math.round(sys.bounds.get(m) * s);
                final int yTop = (int) Math.round(first.top(sys.bounds.get(m)) * s);
                final int yBottom = (int) Math.round(last.bottom(sys.bounds.get(m)) * s);
                g.drawLine(x, yTop - 14, x, yBottom);
                g.drawString("m" + (sys.firstMeasure + m), x + 3, yTop - 4);
            }

            // System and staff numbers
            for (int i = 0; i < sys.staves.size(); i++) {
                final StaffGeo staff = sys.staves.get(i);
                final double yMid = (staff.top(staff.left) + staff.bottom(staff.left)) / 2;
                final String label = ((i == 0) ? ("Sys" + sys.id + " ") : "") + "S" + (i + 1);
                final int x = (int) Math.round(staff.left * s);
                final int width = g.getFontMetrics().stringWidth(label);
                final int y = (int) Math.round(yMid * s) + 5;
                g.setColor(Color.WHITE);
                g.fillRect(x - width - 6, y - 13, width + 4, 16);
                g.setColor(LINE_COLOR);
                g.drawString(label, x - width - 4, y);
            }
        }

        g.dispose();
        ImageIO.write(overview, "png", target.toFile());
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //----------//
    // PageInfo //
    //----------//
    /**
     * Layout of one page, as detected by the engine.
     */
    public static class PageInfo
    {
        final int interline;

        final List<SystemGeo> systems = new ArrayList<>();

        PageInfo (int interline)
        {
            this.interline = interline;
        }
    }

    //-----------//
    // SystemGeo //
    //-----------//
    private static class SystemGeo
    {
        final int id;

        final List<StaffGeo> staves = new ArrayList<>();

        /** 1-based staff ranges of multi-staff parts. */
        final List<int[]> groups = new ArrayList<>();

        /** Measure limits, see {@link ClaudeLayout#measureBounds}. */
        List<Integer> bounds;

        int firstMeasure;

        SystemGeo (int id)
        {
            this.id = id;
        }
    }

    //----------//
    // StaffGeo //
    //----------//
    /**
     * Geometry and header of one staff, kept independently of the (closed) engine book.
     */
    private static class StaffGeo
    {
        /** Sampling step of line ordinates. */
        private static final int SAMPLE = 16;

        final int left;

        final int right;

        final double[] tops;

        final double[] bottoms;

        /** Pitch names for positions -MAX_LABEL_POSITION..MAX_LABEL_POSITION, or null. */
        final String[] names;

        final String clefName;

        final Integer fifths;

        final String time;

        boolean noKey;

        /**
         * @param staff  the engine staff
         * @param factor upscaling factor of the analyzed image (geometry is divided by it)
         */
        StaffGeo (Staff staff,
                  int factor,
                  ClefInter clef,
                  Integer fifths,
                  String time)
        {
            left = (int) Math.round((double) staff.getAbscissa(HorizontalSide.LEFT) / factor);
            right = (int) Math.round((double) staff.getAbscissa(HorizontalSide.RIGHT) / factor);

            final LineInfo topLine = staff.getFirstLine();
            final LineInfo bottomLine = staff.getLastLine();
            final int n = ((right - left) / SAMPLE) + 2;
            tops = new double[n];
            bottoms = new double[n];

            for (int i = 0; i < n; i++) {
                final double x = Math.min(right, left + (i * SAMPLE)) * factor;
                tops[i] = topLine.yAt(x) / factor;
                bottoms[i] = bottomLine.yAt(x) / factor;
            }

            this.fifths = fifths;
            this.time = time;

            clefName = (clef == null) ? null : clefNameOf(clef);

            if ((clef != null) && (clef.getKind() != null)
                    && (clef.getKind() != ClefInter.ClefKind.PERCUSSION)) {
                names = new String[(2 * MAX_LABEL_POSITION) + 1];

                for (int p = -MAX_LABEL_POSITION; p <= MAX_LABEL_POSITION; p++) {
                    names[p + MAX_LABEL_POSITION] = ClefInter.noteStepOf(clef, p).toString()
                            + ClefInter.octaveOf(clef, p);
                }
            } else {
                names = null;
            }
        }

        private static String clefNameOf (ClefInter clef)
        {
            final String shape = clef.getShape().toString();
            final String kind = (clef.getKind() != null)
                    ? clef.getKind().toString().toLowerCase(Locale.ROOT).replace('_', '-')
                    : shape.toLowerCase(Locale.ROOT);

            if (shape.endsWith("_8VA")) {
                return kind + " 8va";
            }

            if (shape.endsWith("_8VB")) {
                return kind + " 8vb";
            }

            return kind;
        }

        double bottom (double x)
        {
            return sample(bottoms, x);
        }

        String describe ()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append((clefName != null) ? clefName + " clef" : "clef not detected");

            if (fifths != null) {
                sb.append(", key ").append(
                        (fifths == 0) ? "none"
                                : Math.abs(fifths) + ((fifths > 0) ? " sharp(s)" : " flat(s)"));
            } else if (noKey) {
                sb.append(", no key signature");
            }

            if (time != null) {
                sb.append(", time ").append(time);
            }

            if (names == null) {
                sb.append(" (no pitch labels)");
            }

            return sb.toString();
        }

        String pitchName (int position)
        {
            return (names == null) ? null : names[position + MAX_LABEL_POSITION];
        }

        private double sample (double[] values,
                               double x)
        {
            final double pos = Math.max(0, Math.min(values.length - 1, (x - left) / SAMPLE));
            final int i = (int) Math.floor(pos);
            final int j = Math.min(values.length - 1, i + 1);

            return values[i] + ((pos - i) * (values[j] - values[i]));
        }

        double top (double x)
        {
            return sample(tops, x);
        }
    }
}
