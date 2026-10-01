//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                              C l a u d e S c o r e B u i l d e r                               //
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

import org.audiveris.omr.claude.Json.JsonException;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.math.GCD;
import org.audiveris.omr.math.Rational;
import org.audiveris.omr.score.MusicXML;

import org.audiveris.proxymusic.AboveBelow;
import org.audiveris.proxymusic.Accidental;
import org.audiveris.proxymusic.Articulations;
import org.audiveris.proxymusic.Attributes;
import org.audiveris.proxymusic.Backup;
import org.audiveris.proxymusic.BackwardForward;
import org.audiveris.proxymusic.BarStyle;
import org.audiveris.proxymusic.BarStyleColor;
import org.audiveris.proxymusic.Barline;
import org.audiveris.proxymusic.Defaults;
import org.audiveris.proxymusic.MarginType;
import org.audiveris.proxymusic.PageLayout;
import org.audiveris.proxymusic.PageMargins;
import org.audiveris.proxymusic.Scaling;
import org.audiveris.proxymusic.Transpose;
import org.audiveris.proxymusic.Beam;
import org.audiveris.proxymusic.BeamValue;
import org.audiveris.proxymusic.Clef;
import org.audiveris.proxymusic.ClefSign;
import org.audiveris.proxymusic.Direction;
import org.audiveris.proxymusic.DirectionType;
import org.audiveris.proxymusic.Dynamics;
import org.audiveris.proxymusic.Empty;
import org.audiveris.proxymusic.Encoding;
import org.audiveris.proxymusic.Ending;
import org.audiveris.proxymusic.Fermata;
import org.audiveris.proxymusic.FormattedTextId;
import org.audiveris.proxymusic.Grace;
import org.audiveris.proxymusic.Identification;
import org.audiveris.proxymusic.Key;
import org.audiveris.proxymusic.Lyric;
import org.audiveris.proxymusic.Metronome;
import org.audiveris.proxymusic.Notations;
import org.audiveris.proxymusic.Note;
import org.audiveris.proxymusic.NoteType;
import org.audiveris.proxymusic.ObjectFactory;
import org.audiveris.proxymusic.PartList;
import org.audiveris.proxymusic.PartName;
import org.audiveris.proxymusic.PerMinute;
import org.audiveris.proxymusic.Pitch;
import org.audiveris.proxymusic.Print;
import org.audiveris.proxymusic.Repeat;
import org.audiveris.proxymusic.Rest;
import org.audiveris.proxymusic.RightLeftMiddle;
import org.audiveris.proxymusic.ScorePart;
import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.Slur;
import org.audiveris.proxymusic.Sound;
import org.audiveris.proxymusic.StartStop;
import org.audiveris.proxymusic.StartStopContinue;
import org.audiveris.proxymusic.StartStopDiscontinue;
import org.audiveris.proxymusic.Stem;
import org.audiveris.proxymusic.StemValue;
import org.audiveris.proxymusic.Step;
import org.audiveris.proxymusic.Syllabic;
import org.audiveris.proxymusic.TextElementData;
import org.audiveris.proxymusic.Tie;
import org.audiveris.proxymusic.Tied;
import org.audiveris.proxymusic.TiedType;
import org.audiveris.proxymusic.Time;
import org.audiveris.proxymusic.TimeModification;
import org.audiveris.proxymusic.TimeSymbol;
import org.audiveris.proxymusic.Tuplet;
import org.audiveris.proxymusic.TypedText;
import org.audiveris.proxymusic.UprightInverted;
import org.audiveris.proxymusic.Wedge;
import org.audiveris.proxymusic.WedgeType;
import org.audiveris.proxymusic.Work;
import org.audiveris.proxymusic.YesNo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.xml.bind.JAXBElement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class <code>ClaudeScoreBuilder</code> converts the score description extracted by Claude
 * vision (format {@value #FORMAT}, specified in {@link ClaudeRequest#FORMAT_SPEC}) into a
 * ProxyMusic {@link ScorePartwise} instance, ready to be marshalled as MusicXML by the
 * regular Audiveris export machinery.
 * <p>
 * Claude is used only for the visual/semantic recognition. All the musical arithmetic
 * (divisions, durations, backups, measure checks) and the MusicXML generation are performed here.
 *
 * @author Audiveris contributors
 */
public class ClaudeScoreBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ClaudeScoreBuilder.class);

    /** Identifier of the supported description format. */
    public static final String FORMAT = "audiveris-claude-omr/1";

    /** Supported note type names, from longest to shortest, with their whole-note fraction. */
    private static final String[] TYPE_NAMES = { "long", "breve", "whole", "half", "quarter",
            "eighth", "16th", "32nd", "64th", "128th", "256th" };

    /** Default diatonic steps for a chromatic transposition of 0..11 semitones. */
    private static final int[] DIATONIC_OF_CHROMATIC = { 0, 1, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6 };

    /** Default page size (A4) and margin, in millimeters. */
    private static final double DEFAULT_PAGE_WIDTH = 210;

    private static final double DEFAULT_PAGE_HEIGHT = 297;

    private static final double PAGE_MARGIN = 15;

    /** Vertical room reserved on first page for title and credits, in millimeters. */
    private static final double TITLE_ROOM = 30;

    /** Vertical room needed per staff, as a multiple of staff height (staff + spacing). */
    private static final double STAFF_ROOM_RATIO = 4.2;

    /** Range of automatic staff height, in millimeters (MuseScore default is 7). */
    private static final double MIN_STAFF_HEIGHT = 3.5;

    private static final double MAX_STAFF_HEIGHT = 7.0;

    //~ Instance fields ----------------------------------------------------------------------------

    private final ObjectFactory factory = new ObjectFactory();

    /** Non-fatal problems detected while building. */
    private final List<String> warnings = new ArrayList<>();

    /** Software name to be written in MusicXML encoding. */
    private final String software;

    /** Divisions per quarter, computed for the whole score. */
    private int divisions;

    /** Total divisions per whole note. */
    private int wholeDivisions;

    /** Numbers of wedges in current part, keyed by staff. */
    private SpannerNumbers wedgeNumbers;

    /** Numbers of slurs in current part, keyed by voice. */
    private SpannerNumbers slurNumbers;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Create a builder.
     *
     * @param software software name to be written in MusicXML encoding, or null
     */
    public ClaudeScoreBuilder (String software)
    {
        this.software = software;
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-------//
    // build //
    //-------//
    /**
     * Build the ScorePartwise out of the parsed JSON description.
     *
     * @param json the parsed JSON root (as returned by {@link Json#parse(String)})
     * @return the ScorePartwise instance
     * @throws JsonException if the description is not consistent with the expected format
     */
    public ScorePartwise build (Object json)
    {
        final Node root = new Node(json, "$");
        root.map(); // Check root is an object

        final String format = root.get("format").str(null);

        if ((format != null) && !format.equals(FORMAT)) {
            warn("$.format", "unexpected format '" + format + "', expected '" + FORMAT + "'");
        }

        computeDivisions(root);

        final ScorePartwise scorePartwise = factory.createScorePartwise();
        processHeader(root, scorePartwise);

        final PartList partList = factory.createPartList();
        scorePartwise.setPartList(partList);

        final List<Node> parts = root.get("parts").list();

        if (parts.isEmpty()) {
            throw new JsonException("$.parts: at least one part is required");
        }

        int partIndex = 0;
        int staffTotal = 0;

        for (Node part : parts) {
            staffTotal += processPart(part, ++partIndex, partList, scorePartwise);
        }

        scorePartwise.setDefaults(buildDefaults(root.get("layout"), staffTotal));

        return scorePartwise;
    }

    //---------------//
    // buildDefaults //
    //---------------//
    /**
     * Build page layout defaults, so that a whole system fits on a page.
     * <p>
     * Without them, score editors use their default staff size, which for large ensembles pushes
     * the first system off the title page.
     *
     * @param layout     optional "layout" node: staffHeight, pageWidth, pageHeight (millimeters)
     * @param staffTotal total number of staves in a system
     */
    private Defaults buildDefaults (Node layout,
                                    int staffTotal)
    {
        final double pageWidth = layout.get("pageWidth").number(DEFAULT_PAGE_WIDTH);
        final double pageHeight = layout.get("pageHeight").number(DEFAULT_PAGE_HEIGHT);

        if ((pageWidth <= (2 * PAGE_MARGIN)) || (pageHeight <= (2 * PAGE_MARGIN + TITLE_ROOM))) {
            throw new JsonException(layout.path + ": page is too small");
        }

        final double available = pageHeight - (2 * PAGE_MARGIN) - TITLE_ROOM;
        final double auto = Math.max(
                MIN_STAFF_HEIGHT,
                Math.min(MAX_STAFF_HEIGHT, available / (staffTotal * STAFF_ROOM_RATIO)));
        final double staffHeight = layout.get("staffHeight").number(auto);

        if (staffHeight <= 0) {
            throw new JsonException(layout.get("staffHeight").path + ": must be positive");
        }

        // MusicXML tenths: 40 tenths = one staff height
        final double tenthsPerMm = 40 / staffHeight;

        final Scaling scaling = factory.createScaling();
        scaling.setMillimeters(decimal(staffHeight));
        scaling.setTenths(new BigDecimal(40));

        final PageMargins margins = factory.createPageMargins();
        margins.setType(MarginType.BOTH);
        margins.setLeftMargin(decimal(PAGE_MARGIN * tenthsPerMm));
        margins.setRightMargin(decimal(PAGE_MARGIN * tenthsPerMm));
        margins.setTopMargin(decimal(PAGE_MARGIN * tenthsPerMm));
        margins.setBottomMargin(decimal(PAGE_MARGIN * tenthsPerMm));

        final PageLayout pageLayout = factory.createPageLayout();
        pageLayout.setPageWidth(decimal(pageWidth * tenthsPerMm));
        pageLayout.setPageHeight(decimal(pageHeight * tenthsPerMm));
        pageLayout.getPageMargins().add(margins);

        final Defaults defaults = factory.createDefaults();
        defaults.setScaling(scaling);
        defaults.setPageLayout(pageLayout);

        return defaults;
    }

    //------------------//
    // computeDivisions //
    //------------------//
    /**
     * Compute a common divisions value, able to express all event durations as integers.
     */
    private void computeDivisions (Node root)
    {
        final TreeSet<Integer> dens = new TreeSet<>();
        dens.add(4); // So that a quarter is always an integer number of divisions

        for (Node part : root.get("parts").list()) {
            for (Node measure : part.get("measures").list()) {
                for (Node voice : measure.get("voices").listOrEmpty()) {
                    for (Node event : voice.get("events").list()) {
                        if (!isGrace(event) && !isMeasureRest(event)) {
                            dens.add(durationOf(event).den);
                        }
                    }
                }
            }
        }

        wholeDivisions = GCD.lcm(dens.stream().mapToInt(Integer::intValue).toArray());
        divisions = wholeDivisions / 4;
        logger.debug("Claude score divisions: {} per quarter", divisions);
    }

    //------------//
    // durationOf //
    //------------//
    /**
     * Report the duration (in whole notes) of a non-grace, non-measure-rest event.
     */
    private Rational durationOf (Node event)
    {
        final Node typeNode = event.get("type");
        final String type = normalizeType(typeNode.str(), typeNode.path);
        final int index = indexOfType(type);
        // whole is at index 2 => 2^(index-2) is the denominator
        Rational dur = (index >= 2) ? new Rational(1, 1 << (index - 2))
                : new Rational(1 << (2 - index), 1);

        final int dots = event.get("dots").integer(0);

        if ((dots < 0) || (dots > 4)) {
            throw new JsonException(event.get("dots").path + ": invalid dots count " + dots);
        }

        dur = dur.times(new Rational((1 << (dots + 1)) - 1, 1 << dots));

        final Node tuplet = event.get("tuplet");

        if (!tuplet.isNull()) {
            final int actual = tuplet.get("actual").integer();
            final int normal = tuplet.get("normal").integer();

            if ((actual <= 0) || (normal <= 0)) {
                throw new JsonException(tuplet.path + ": actual and normal must be positive");
            }

            dur = dur.times(new Rational(normal, actual));
        }

        return dur;
    }

    //-------------//
    // getWarnings //
    //-------------//
    /**
     * Report the non-fatal problems detected during last build.
     *
     * @return the (unmodifiable) list of warnings
     */
    public List<String> getWarnings ()
    {
        return Collections.unmodifiableList(warnings);
    }

    private boolean isGrace (Node event)
    {
        return event.get("grace").bool(false);
    }

    private boolean isMeasureRest (Node event)
    {
        return event.get("rest").bool(false) && event.get("measureRest").bool(false);
    }

    //----------------//
    // processBarline //
    //----------------//
    private Barline processBarline (Node node,
                                    RightLeftMiddle location)
    {
        final Barline barline = factory.createBarline();
        barline.setLocation(location);

        final String repeat = node.get("repeat").str(null);
        String style = node.get("style").str(null);

        if ((style == null) && (repeat != null)) {
            style = (location == RightLeftMiddle.LEFT) ? "heavy-light" : "light-heavy";
        }

        if (style != null) {
            final BarStyleColor barStyle = factory.createBarStyleColor();
            barStyle.setValue(
                    enumOf(BarStyle.class, style, node.get("style").path));
            barline.setBarStyle(barStyle);
        }

        final Node endingNode = node.get("ending");

        if (!endingNode.isNull()) {
            final Ending ending = factory.createEnding();
            ending.setNumber(endingNode.get("number").str());
            ending.setType(
                    enumOf(
                            StartStopDiscontinue.class,
                            endingNode.get("type").str(),
                            endingNode.get("type").path));

            final String text = endingNode.get("text").str(null);

            if (text != null) {
                ending.setValue(text);
            }

            barline.setEnding(ending);
        }

        if (repeat != null) {
            final Repeat pmRepeat = factory.createRepeat();
            pmRepeat.setDirection(enumOf(BackwardForward.class, repeat, node.get("repeat").path));
            barline.setRepeat(pmRepeat);
        }

        if (node.get("fermata").bool(false)) {
            final Fermata fermata = factory.createFermata();
            fermata.setType(UprightInverted.UPRIGHT);
            barline.getFermata().add(fermata);
        }

        return barline;
    }

    //------------------//
    // processDirection //
    //------------------//
    private void processDirection (Node node,
                                   int staffCount,
                                   int defaultStaff,
                                   List<Object> items)
    {
        final Direction direction = factory.createDirection();
        final DirectionType directionType = factory.createDirectionType();
        direction.getDirectionType().add(directionType);
        String defaultPlacement = "above";

        final String dynamics = node.get("dynamics").str(null);
        final String words = node.get("words").str(null);
        final String wedge = node.get("wedge").str(null);
        final Node tempo = node.get("tempo");

        if (dynamics != null) {
            final Shape shape = shapeOf(
                    "DYNAMICS_" + dynamics.toUpperCase(Locale.ROOT),
                    node.get("dynamics").path);
            final JAXBElement<?> element = MusicXML.getDynamicsObject(shape);

            if (element == null) {
                throw new JsonException(
                        node.get("dynamics").path + ": unsupported dynamics '" + dynamics + "'");
            }

            final Dynamics pmDynamics = factory.createDynamics();
            pmDynamics.getPOrPpOrPpp().add(element);
            directionType.getDynamics().add(pmDynamics);
            defaultPlacement = "below";
        } else if (words != null) {
            final FormattedTextId pmWords = factory.createFormattedTextId();
            pmWords.setValue(words);
            directionType.getWordsOrSymbol().add(pmWords);
        } else if (wedge != null) {
            final Wedge pmWedge = factory.createWedge();
            final WedgeType wedgeType = enumOf(WedgeType.class, wedge, node.get("wedge").path);
            pmWedge.setType(wedgeType);

            // Number wedges per staff, so that starts and stops on different staves of the
            // same part are not paired together by score editors
            final String key = "staff " + node.get("staff").integer(defaultStaff);

            if (wedgeType == WedgeType.STOP) {
                final Integer number = wedgeNumbers.stop(key);

                if (number == null) {
                    warn(node.path, "wedge stop without any open wedge on " + key);
                } else {
                    pmWedge.setNumber(number);
                }
            } else if (wedgeType == WedgeType.CONTINUE) {
                pmWedge.setNumber(wedgeNumbers.current(key));
            } else {
                pmWedge.setNumber(wedgeNumbers.start(key, node.path));
            }

            directionType.setWedge(pmWedge);
            defaultPlacement = "below";
        } else if (!tempo.isNull()) {
            final Metronome metronome = factory.createMetronome();
            final String beatUnit = normalizeType(
                    tempo.get("beatUnit").str("quarter"),
                    tempo.get("beatUnit").path);
            metronome.setBeatUnit(beatUnit);

            final int dots = tempo.get("dots").integer(0);

            for (int i = 0; i < dots; i++) {
                metronome.getBeatUnitDot().add(factory.createEmpty());
            }

            final int perMinute = tempo.get("perMinute").integer();
            final PerMinute pmPerMinute = factory.createPerMinute();
            pmPerMinute.setValue(Integer.toString(perMinute));
            metronome.setPerMinute(pmPerMinute);
            directionType.setMetronome(metronome);

            // Sound tempo is always expressed in quarters per minute
            final Rational unit = durationOf(new Node(Map.of("type", beatUnit, "dots",
                    new BigDecimal(dots)), tempo.path));
            final Rational quarters = unit.times(4).times(perMinute);
            final Sound sound = factory.createSound();
            sound.setTempo(
                    new BigDecimal(quarters.num).divide(
                            new BigDecimal(quarters.den),
                            2,
                            java.math.RoundingMode.HALF_UP));
            direction.setSound(sound);
        } else {
            warn(node.path, "empty direction ignored");

            return;
        }

        direction.setPlacement(
                enumOf(
                        AboveBelow.class,
                        node.get("placement").str(defaultPlacement),
                        node.get("placement").path));

        if (staffCount > 1) {
            direction.setStaff(bigInt(node.get("staff").integer(defaultStaff)));
        }

        items.add(direction);
    }

    //--------------//
    // processEvent //
    //--------------//
    /**
     * Process one event (a chord, a single note or a rest) and return its duration.
     */
    private int processEvent (Node event,
                              String voiceId,
                              int voiceStaff,
                              int staffCount,
                              Rational measureDuration,
                              List<Object> items)
    {
        final int staff = event.get("staff").integer(voiceStaff);
        checkStaff(event.get("staff"), staff, staffCount);

        // Directions attached to this event are inserted just before it
        for (Node direction : event.get("directions").listOrEmpty()) {
            processDirection(direction, staffCount, staff, items);
        }

        final boolean isRest = event.get("rest").bool(false);
        final boolean isGrace = isGrace(event);
        final boolean isMeasureRest = isMeasureRest(event);

        final int duration;

        if (isGrace) {
            duration = 0;
        } else if (isMeasureRest) {
            duration = toDivisions(measureDuration);
        } else {
            duration = toDivisions(durationOf(event));
        }

        final List<Node> pitches = isRest ? Collections.<Node>singletonList(null)
                : event.get("pitches").list();

        if (pitches.isEmpty()) {
            throw new JsonException(event.path + ": a non-rest event needs at least one pitch");
        }

        boolean isFirst = true;

        for (Node pitchNode : pitches) {
            final Note note = factory.createNote();
            Notations notations = null;

            if (!isFirst) {
                note.setChord(new Empty());
            }

            if (isGrace) {
                final Grace grace = factory.createGrace();

                if (event.get("graceSlash").bool(false)) {
                    grace.setSlash(YesNo.YES);
                }

                note.setGrace(grace);
            }

            if (isRest) {
                final Rest rest = factory.createRest();

                if (isMeasureRest) {
                    rest.setMeasure(YesNo.YES);
                }

                note.setRest(rest);
            } else {
                final Pitch pitch = factory.createPitch();
                final String step = pitchNode.get("step").str().toUpperCase(Locale.ROOT);

                try {
                    pitch.setStep(Step.fromValue(step));
                } catch (IllegalArgumentException ex) {
                    throw new JsonException(pitchNode.get("step").path + ": invalid step " + step);
                }

                pitch.setOctave(pitchNode.get("octave").integer());

                final BigDecimal alter = pitchNode.get("alter").decimal(null);

                if ((alter != null) && (alter.signum() != 0)) {
                    pitch.setAlter(alter);
                }

                note.setPitch(pitch);
            }

            if (!isGrace) {
                note.setDuration(new BigDecimal(duration));
            }

            // Ties
            if (!isRest) {
                final String tie = pitchNode.get("tie").str(null);

                if (tie != null) {
                    notations = (notations != null) ? notations : factory.createNotations();

                    for (String t : expandTie(tie, pitchNode.get("tie").path)) {
                        final Tie pmTie = factory.createTie();
                        pmTie.setType(enumOf(StartStop.class, t, pitchNode.get("tie").path));
                        note.getTie().add(pmTie);

                        final Tied tied = factory.createTied();
                        tied.setType(enumOf(TiedType.class, t, pitchNode.get("tie").path));
                        notations.getTiedOrSlurOrTuplet().add(tied);
                    }
                }
            }

            note.setVoice(voiceId);

            if (!isMeasureRest) {
                final NoteType noteType = factory.createNoteType();
                noteType.setValue(normalizeType(event.get("type").str(), event.get("type").path));
                note.setType(noteType);

                for (int i = 0; i < event.get("dots").integer(0); i++) {
                    note.getDot().add(factory.createEmptyPlacement());
                }
            }

            if (!isRest) {
                final String accidental = pitchNode.get("accidental").str(null);

                if (accidental != null) {
                    final Accidental pmAccidental = factory.createAccidental();
                    pmAccidental.setValue(
                            MusicXML.accidentalValueOf(
                                    accidentalShapeOf(
                                            accidental,
                                            pitchNode.get("accidental").path)));
                    note.setAccidental(pmAccidental);
                }
            }

            final Node tuplet = event.get("tuplet");

            if (!tuplet.isNull()) {
                final TimeModification timeModification = factory.createTimeModification();
                timeModification.setActualNotes(bigInt(tuplet.get("actual").integer()));
                timeModification.setNormalNotes(bigInt(tuplet.get("normal").integer()));
                note.setTimeModification(timeModification);
            }

            final String stem = event.get("stem").str(null);

            if ((stem != null) && !isRest) {
                final Stem pmStem = factory.createStem();
                pmStem.setValue(enumOf(StemValue.class, stem, event.get("stem").path));
                note.setStem(pmStem);
            }

            if (staffCount > 1) {
                note.setStaff(bigInt(staff));
            }

            // Items below are attached to the first note of the chord only
            if (isFirst) {
                int beamNumber = 0;

                for (Node beam : event.get("beams").listOrEmpty()) {
                    final Beam pmBeam = factory.createBeam();
                    pmBeam.setNumber(++beamNumber);
                    pmBeam.setValue(enumOf(BeamValue.class, beam.str(), beam.path));
                    note.getBeam().add(pmBeam);
                }

                notations = processNotations(event, voiceId, notations);

                int lyricIndex = 0;

                for (Node lyric : event.get("lyrics").listOrEmpty()) {
                    lyricIndex++;

                    final Lyric pmLyric = factory.createLyric();
                    pmLyric.setNumber(Integer.toString(lyric.get("number").integer(lyricIndex)));
                    pmLyric.getElisionAndSyllabicAndText().add(
                            enumOf(
                                    Syllabic.class,
                                    lyric.get("syllabic").str("single"),
                                    lyric.get("syllabic").path));

                    final TextElementData text = factory.createTextElementData();
                    text.setValue(lyric.get("text").str());
                    pmLyric.getElisionAndSyllabicAndText().add(text);
                    note.getLyric().add(pmLyric);
                }
            }

            if (notations != null) {
                note.getNotations().add(notations);
            }

            items.add(note);
            isFirst = false;
        }

        return duration;
    }

    //---------------//
    // processHeader //
    //---------------//
    private void processHeader (Node root,
                                ScorePartwise scorePartwise)
    {
        final String title = root.get("title").str(null);

        if (title != null) {
            final Work work = factory.createWork();
            work.setWorkTitle(title);
            scorePartwise.setWork(work);
            scorePartwise.setMovementTitle(title);
        }

        final Identification identification = factory.createIdentification();
        scorePartwise.setIdentification(identification);

        for (String role : new String[] { "composer", "lyricist", "arranger" }) {
            final String value = root.get(role).str(null);

            if (value != null) {
                final TypedText typedText = factory.createTypedText();
                typedText.setType(role);
                typedText.setValue(value);
                identification.getCreator().add(typedText);
            }
        }

        final String rights = root.get("rights").str(null);

        if (rights != null) {
            final TypedText typedText = factory.createTypedText();
            typedText.setValue(rights);
            identification.getRights().add(typedText);
        }

        final String source = root.get("source").str(null);

        if (source != null) {
            identification.setSource(source);
        }

        if (software != null) {
            final Encoding encoding = factory.createEncoding();
            encoding.getEncodingDateOrEncoderOrSoftware().add(
                    factory.createEncodingSoftware(software));
            identification.setEncoding(encoding);
        }
    }

    //----------------//
    // processMeasure //
    //----------------//
    /**
     * Process one measure.
     *
     * @return the time signature in effect at the end of this measure
     */
    private Rational processMeasure (Node measure,
                                     int measureIndex,
                                     int staffCount,
                                     Rational currentTime,
                                     Node partTranspose,
                                     ScorePartwise.Part pmPart)
    {
        final ScorePartwise.Part.Measure pmMeasure = factory.createScorePartwisePartMeasure();
        pmPart.getMeasure().add(pmMeasure);
        pmMeasure.setNumber(measure.get("number").str(Integer.toString(measureIndex)));

        final boolean implicit = measure.get("implicit").bool(false);

        if (implicit) {
            pmMeasure.setImplicit(YesNo.YES);
        }

        final List<Object> items = pmMeasure.getNoteOrBackupOrForward();

        // Print (layout hints)
        if (measureIndex > 1) {
            final boolean newPage = measure.get("newPage").bool(false);
            final boolean newSystem = measure.get("newSystem").bool(false);

            if (newPage || newSystem) {
                final Print print = factory.createPrint();

                if (newPage) {
                    print.setNewPage(YesNo.YES);
                } else {
                    print.setNewSystem(YesNo.YES);
                }

                items.add(print);
            }
        }

        // Left barline
        final Node leftBarline = measure.get("leftBarline");

        if (!leftBarline.isNull()) {
            items.add(processBarline(leftBarline, RightLeftMiddle.LEFT));
        }

        // Attributes
        final Attributes attributes = factory.createAttributes();
        boolean hasAttributes = false;

        if (measureIndex == 1) {
            attributes.setDivisions(new BigDecimal(divisions));

            if (staffCount > 1) {
                attributes.setStaves(bigInt(staffCount));
            }

            hasAttributes = true;
        }

        final Node keyNode = measure.get("key");

        if (!keyNode.isNull()) {
            final Key key = factory.createKey();
            final int fifths = keyNode.get("fifths").integer();

            if ((fifths < -7) || (fifths > 7)) {
                throw new JsonException(keyNode.get("fifths").path + ": invalid fifths " + fifths);
            }

            key.setFifths(bigInt(fifths));

            final String mode = keyNode.get("mode").str(null);

            if (mode != null) {
                key.setMode(mode);
            }

            attributes.getKey().add(key);
            hasAttributes = true;
        }

        final Node timeNode = measure.get("time");

        if (!timeNode.isNull()) {
            final int beats = timeNode.get("beats").integer();
            final int beatType = timeNode.get("beatType").integer();

            if ((beats <= 0) || (beatType <= 0)) {
                throw new JsonException(timeNode.path + ": invalid time signature");
            }

            final Time time = factory.createTime();
            time.getTimeSignature().add(factory.createTimeBeats(Integer.toString(beats)));
            time.getTimeSignature().add(factory.createTimeBeatType(Integer.toString(beatType)));

            final String symbol = timeNode.get("symbol").str(null);

            if (symbol != null) {
                time.setSymbol(enumOf(TimeSymbol.class, symbol, timeNode.get("symbol").path));
            }

            attributes.getTime().add(time);
            currentTime = new Rational(beats, beatType);
            hasAttributes = true;
        } else if ((measureIndex == 1) && (currentTime == null)) {
            warn(measure.path, "no time signature in first measure, assuming 4/4 for checks");
        }

        for (Node clefNode : measure.get("clefs").listOrEmpty()) {
            final Clef clef = factory.createClef();
            final String sign = clefNode.get("sign").str().toUpperCase(Locale.ROOT);
            clef.setSign(enumOf(ClefSign.class, sign, clefNode.get("sign").path));

            final int defaultLine = switch (sign) {
                case "G" -> 2;
                case "F" -> 4;
                default -> 3;
            };

            clef.setLine(bigInt(clefNode.get("line").integer(defaultLine)));

            final int octaveChange = clefNode.get("octaveChange").integer(0);

            if (octaveChange != 0) {
                clef.setClefOctaveChange(bigInt(octaveChange));
            }

            if (staffCount > 1) {
                final int staff = clefNode.get("staff").integer(1);
                checkStaff(clefNode.get("staff"), staff, staffCount);
                clef.setNumber(bigInt(staff));
            }

            attributes.getClef().add(clef);
            hasAttributes = true;
        }

        // Transposition: part-level value applies from first measure, measure-level value
        // (e.g. instrument change) overrides it
        Node transposeNode = measure.get("transpose");

        if (transposeNode.isNull() && (partTranspose != null)) {
            transposeNode = partTranspose;
        }

        if (!transposeNode.isNull()) {
            attributes.getTranspose().add(processTranspose(transposeNode));
            hasAttributes = true;
        }

        if (hasAttributes) {
            items.add(attributes);
        }

        // Voices
        final Rational measureDuration = (currentTime != null) ? currentTime : Rational.ONE;
        final int expected = toDivisions(measureDuration);
        final List<Node> voices = measure.get("voices").listOrEmpty();
        int maxElapsed = 0;
        int voiceIndex = 0;

        for (Node voice : voices) {
            voiceIndex++;

            final String voiceId = Integer.toString(voice.get("voice").integer(voiceIndex));
            final int voiceStaff = voice.get("staff").integer(1);
            checkStaff(voice.get("staff"), voiceStaff, staffCount);

            int elapsed = 0;

            for (Node event : voice.get("events").list()) {
                elapsed += processEvent(
                        event,
                        voiceId,
                        voiceStaff,
                        staffCount,
                        measureDuration,
                        items);
            }

            if (elapsed > expected) {
                warn(
                        voice.path,
                        "voice " + voiceId + " lasts " + fractionOf(elapsed)
                                + " whole note(s), more than the measure capacity "
                                + fractionOf(expected));
            }

            maxElapsed = Math.max(maxElapsed, elapsed);

            // Move back to measure start before next voice
            if ((voiceIndex < voices.size()) && (elapsed > 0)) {
                final Backup backup = factory.createBackup();
                backup.setDuration(new BigDecimal(elapsed));
                items.add(backup);
            }
        }

        if (!implicit && (maxElapsed < expected)) {
            warn(
                    measure.path,
                    "measure content lasts " + fractionOf(maxElapsed)
                            + " whole note(s), less than the measure capacity "
                            + fractionOf(expected));
        }

        // Right barline
        final Node rightBarline = measure.get("rightBarline");

        if (!rightBarline.isNull()) {
            items.add(processBarline(rightBarline, RightLeftMiddle.RIGHT));
        }

        return currentTime;
    }

    //------------------//
    // processNotations //
    //------------------//
    private Notations processNotations (Node event,
                                        String voiceId,
                                        Notations notations)
    {
        final List<Object> list = new ArrayList<>();

        // Slurs
        for (Node slur : event.get("slurs").listOrEmpty()) {
            final Slur pmSlur = factory.createSlur();
            final boolean isText = slur.value instanceof String;
            final String type = isText ? slur.str() : slur.get("type").str();
            final StartStopContinue ssc = enumOf(
                    StartStopContinue.class,
                    type,
                    isText ? slur.path : slur.path + ".type");
            pmSlur.setType(ssc);

            // An explicit number is kept, otherwise slurs are numbered per voice
            final Integer explicit = isText ? null : slur.get("number").integerOrNull();
            final String key = (explicit != null) ? "number " + explicit : "voice " + voiceId;
            final Integer number = switch (ssc) {
                case START -> slurNumbers.start(key, slur.path, explicit);
                case STOP -> slurNumbers.stop(key);
                case CONTINUE -> slurNumbers.current(key);
            };

            // A stop without start may legitimately end a slur begun before the transcription
            pmSlur.setNumber((number != null) ? number : ((explicit != null) ? explicit : 1));
            list.add(pmSlur);
        }

        // Tuplet bracket start / stop
        final Node tuplet = event.get("tuplet");

        if (!tuplet.isNull()) {
            final String type = tuplet.get("type").str(null);

            if (type != null) {
                final Tuplet pmTuplet = factory.createTuplet();
                pmTuplet.setType(enumOf(StartStop.class, type, tuplet.get("type").path));
                pmTuplet.setNumber(tuplet.get("number").integer(1));

                if (!tuplet.get("bracket").bool(true)) {
                    pmTuplet.setBracket(YesNo.NO);
                }

                list.add(pmTuplet);
            }
        }

        // Articulations
        final List<Node> articulationNodes = event.get("articulations").listOrEmpty();

        if (!articulationNodes.isEmpty()) {
            final Articulations articulations = factory.createArticulations();

            for (Node node : articulationNodes) {
                final String name = node.str().toLowerCase(Locale.ROOT);
                final Shape shape = switch (name) {
                    case "staccato" -> Shape.STACCATO;
                    case "staccatissimo" -> Shape.STACCATISSIMO;
                    case "accent" -> Shape.ACCENT;
                    case "tenuto" -> Shape.TENUTO;
                    case "marcato", "strong-accent" -> Shape.MARCATO;
                    case "breath-mark" -> Shape.BREATH_MARK;
                    case "caesura" -> Shape.CAESURA;
                    default -> throw new JsonException(
                            node.path + ": unsupported articulation '" + name + "'");
                };

                articulations.getAccentOrStrongAccentOrStaccato().add(
                        MusicXML.getArticulationObject(shape));
            }

            list.add(articulations);
        }

        // Fermata
        if (event.get("fermata").bool(false)) {
            final Fermata fermata = factory.createFermata();
            fermata.setType(UprightInverted.UPRIGHT);
            list.add(fermata);
        }

        if (list.isEmpty()) {
            return notations;
        }

        if (notations == null) {
            notations = factory.createNotations();
        }

        notations.getTiedOrSlurOrTuplet().addAll(list);

        return notations;
    }

    //-------------//
    // processPart //
    //-------------//
    /**
     * Process one part.
     *
     * @return the number of staves in this part
     */
    private int processPart (Node part,
                             int partIndex,
                             PartList partList,
                             ScorePartwise scorePartwise)
    {
        wedgeNumbers = new SpannerNumbers();
        slurNumbers = new SpannerNumbers();

        final ScorePart scorePart = factory.createScorePart();
        scorePart.setId(part.get("id").str("P" + partIndex));

        final PartName partName = factory.createPartName();
        partName.setValue(part.get("name").str(""));
        scorePart.setPartName(partName);

        final String abbreviation = part.get("abbreviation").str(null);

        if (abbreviation != null) {
            final PartName partAbbrev = factory.createPartName();
            partAbbrev.setValue(abbreviation);
            scorePart.setPartAbbreviation(partAbbrev);
        }

        partList.getPartGroupOrScorePart().add(scorePart);

        final ScorePartwise.Part pmPart = factory.createScorePartwisePart();
        pmPart.setId(scorePart);
        scorePartwise.getPart().add(pmPart);

        final int staffCount = part.get("staves").integer(1);

        if ((staffCount < 1) || (staffCount > 4)) {
            throw new JsonException(part.get("staves").path + ": invalid staves " + staffCount);
        }

        final List<Node> measures = part.get("measures").list();

        if (measures.isEmpty()) {
            throw new JsonException(part.path + ".measures: at least one measure is required");
        }

        Rational currentTime = null;
        int measureIndex = 0;

        for (Node measure : measures) {
            currentTime = processMeasure(
                    measure,
                    ++measureIndex,
                    staffCount,
                    currentTime,
                    (measureIndex == 1) ? part.get("transpose") : null,
                    pmPart);
        }

        // A wedge left open would be drawn up to the end of the score
        for (String path : wedgeNumbers.openPaths()) {
            warn(path, "wedge is never stopped");
        }

        return staffCount;
    }

    //------------------//
    // processTranspose //
    //------------------//
    /**
     * Process a transposition for a transposing instrument (written pitch to sounding pitch).
     */
    private Transpose processTranspose (Node node)
    {
        final int chromatic = node.get("chromatic").integer(0);
        final int octaveChange = node.get("octaveChange").integer(0);
        final int sign = (chromatic < 0) ? -1 : 1;
        final int abs = Math.abs(chromatic);
        final int defaultDiatonic = sign * (((abs / 12) * 7) + DIATONIC_OF_CHROMATIC[abs % 12]);
        final int diatonic = node.get("diatonic").integer(defaultDiatonic);

        if ((chromatic == 0) && (octaveChange == 0) && (diatonic == 0)) {
            warn(node.path, "transposition has no effect");
        }

        final Transpose transpose = factory.createTranspose();
        transpose.setChromatic(new BigDecimal(chromatic));
        transpose.setDiatonic(bigInt(diatonic));

        if (octaveChange != 0) {
            transpose.setOctaveChange(bigInt(octaveChange));
        }

        return transpose;
    }

    private int toDivisions (Rational duration)
    {
        final long value = ((long) duration.num * wholeDivisions) / duration.den;

        return (int) value;
    }

    private String fractionOf (int divs)
    {
        return new Rational(divs, wholeDivisions).toString();
    }

    private void warn (String path,
                       String message)
    {
        final String msg = path + ": " + message;
        warnings.add(msg);
        logger.warn("Claude OMR: {}", msg);
    }

    //~ Static Methods -----------------------------------------------------------------------------

    private static Shape accidentalShapeOf (String name,
                                            String path)
    {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "sharp" -> Shape.SHARP;
            case "flat" -> Shape.FLAT;
            case "natural" -> Shape.NATURAL;
            case "double-sharp", "sharp-sharp" -> Shape.DOUBLE_SHARP;
            case "double-flat", "flat-flat" -> Shape.DOUBLE_FLAT;
            default -> throw new JsonException(path + ": unsupported accidental '" + name + "'");
        };
    }

    private static BigInteger bigInt (int value)
    {
        return BigInteger.valueOf(value);
    }

    private static BigDecimal decimal (double value)
    {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static void checkStaff (Node node,
                                    int staff,
                                    int staffCount)
    {
        if ((staff < 1) || (staff > staffCount)) {
            throw new JsonException(
                    node.path + ": staff " + staff + " out of range 1.." + staffCount);
        }
    }

    /**
     * Map a JSON string like "light-heavy" or "forward hook" to the enum constant
     * LIGHT_HEAVY or FORWARD_HOOK.
     */
    private static <E extends Enum<E>> E enumOf (Class<E> classe,
                                                 String value,
                                                 String path)
    {
        final String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(
                ' ',
                '_');

        try {
            return Enum.valueOf(classe, name);
        } catch (IllegalArgumentException ex) {
            throw new JsonException(
                    path + ": invalid value '" + value + "' for " + classe.getSimpleName());
        }
    }

    private static List<String> expandTie (String tie,
                                           String path)
    {
        return switch (tie.toLowerCase(Locale.ROOT)) {
            case "start" -> List.of("start");
            case "stop" -> List.of("stop");
            case "continue", "stop-start" -> List.of("stop", "start");
            default -> throw new JsonException(path + ": invalid tie '" + tie + "'");
        };
    }

    private static int indexOfType (String type)
    {
        for (int i = 0; i < TYPE_NAMES.length; i++) {
            if (TYPE_NAMES[i].equals(type)) {
                return i;
            }
        }

        return -1;
    }

    private static String normalizeType (String type,
                                         String path)
    {
        final String t = type.trim().toLowerCase(Locale.ROOT);
        final String normalized = switch (t) {
            case "8th" -> "eighth";
            case "sixteenth" -> "16th";
            case "thirty-second", "32th" -> "32nd";
            case "sixty-fourth" -> "64th";
            default -> t;
        };

        if (indexOfType(normalized) < 0) {
            throw new JsonException(path + ": unknown note type '" + type + "'");
        }

        return normalized;
    }

    private static Shape shapeOf (String name,
                                  String path)
    {
        try {
            return Shape.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new JsonException(path + ": unsupported value '" + name + "'");
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //----------------//
    // SpannerNumbers //
    //----------------//
    /**
     * Allocates MusicXML numbers to spanners (wedges, slurs) of a part, so that each stop is
     * paired with the start of the same key (staff or voice) even when spanners overlap.
     */
    private static class SpannerNumbers
    {
        /** Open spanners per key, most recent first: number and path of start. */
        private final Map<String, Deque<Object[]>> open = new LinkedHashMap<>();

        /** Numbers currently in use. */
        private final Set<Integer> used = new HashSet<>();

        Integer current (String key)
        {
            final Deque<Object[]> deque = open.get(key);

            return ((deque == null) || deque.isEmpty()) ? null : (Integer) deque.peek()[0];
        }

        List<String> openPaths ()
        {
            final List<String> paths = new ArrayList<>();

            for (Deque<Object[]> deque : open.values()) {
                for (Object[] entry : deque) {
                    paths.add((String) entry[1]);
                }
            }

            return paths;
        }

        int start (String key,
                   String path)
        {
            return start(key, path, null);
        }

        int start (String key,
                   String path,
                   Integer explicit)
        {
            int number = (explicit != null) ? explicit : 1;

            if (explicit == null) {
                while (used.contains(number)) {
                    number++;
                }
            }

            used.add(number);
            open.computeIfAbsent(key, k -> new ArrayDeque<>()).push(new Object[] { number, path });

            return number;
        }

        Integer stop (String key)
        {
            final Deque<Object[]> deque = open.get(key);

            if ((deque == null) || deque.isEmpty()) {
                return null;
            }

            final Integer number = (Integer) deque.pop()[0];
            used.remove(number);

            return number;
        }
    }

    //------//
    // Node //
    //------//
    /**
     * A JSON value together with its path, for meaningful error messages.
     */
    private static class Node
    {
        final Object value;

        final String path;

        Node (Object value,
              String path)
        {
            this.value = value;
            this.path = path;
        }

        BigDecimal decimal (BigDecimal def)
        {
            if (value == null) {
                return def;
            }

            if (value instanceof BigDecimal bd) {
                return bd;
            }

            throw new JsonException(path + ": number expected");
        }

        boolean bool (boolean def)
        {
            if (value == null) {
                return def;
            }

            if (value instanceof Boolean b) {
                return b;
            }

            throw new JsonException(path + ": boolean expected");
        }

        Node get (String key)
        {
            return new Node(map().get(key), path + "." + key);
        }

        int integer ()
        {
            if (value == null) {
                throw new JsonException(path + ": missing integer value");
            }

            return integer(0);
        }

        int integer (int def)
        {
            if (value == null) {
                return def;
            }

            try {
                if (value instanceof BigDecimal bd) {
                    return bd.intValueExact();
                }

                if (value instanceof String s) {
                    return Integer.parseInt(s.trim());
                }
            } catch (ArithmeticException | NumberFormatException ex) {
                // Fall through
            }

            throw new JsonException(path + ": integer expected");
        }

        Integer integerOrNull ()
        {
            return (value == null) ? null : integer(0);
        }

        boolean isNull ()
        {
            return value == null;
        }

        double number (double def)
        {
            if (value == null) {
                return def;
            }

            if (value instanceof BigDecimal bd) {
                return bd.doubleValue();
            }

            throw new JsonException(path + ": number expected");
        }

        List<Node> list ()
        {
            if (value == null) {
                throw new JsonException(path + ": missing array");
            }

            return listOrEmpty();
        }

        List<Node> listOrEmpty ()
        {
            if (value == null) {
                return Collections.emptyList();
            }

            if (!(value instanceof List<?> l)) {
                throw new JsonException(path + ": array expected");
            }

            final List<Node> nodes = new ArrayList<>();

            for (int i = 0; i < l.size(); i++) {
                nodes.add(new Node(l.get(i), path + "[" + i + "]"));
            }

            return nodes;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map ()
        {
            if (value == null) {
                return Collections.emptyMap();
            }

            if (!(value instanceof Map)) {
                throw new JsonException(path + ": object expected");
            }

            return (Map<String, Object>) value;
        }

        String str ()
        {
            if (value == null) {
                throw new JsonException(path + ": missing string value");
            }

            return str(null);
        }

        String str (String def)
        {
            if (value == null) {
                return def;
            }

            if (value instanceof String s) {
                return s;
            }

            if (value instanceof BigDecimal bd) {
                return bd.toPlainString();
            }

            throw new JsonException(path + ": string expected");
        }
    }
}
