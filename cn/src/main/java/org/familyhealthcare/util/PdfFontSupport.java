package org.familyhealthcare.util;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.HashSet;
import org.familyhealthcare.service.careplan.CareExecutionReportBudget;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Selects an embeddable TrueType font before PDFBox attempts subsetting. */
public final class PdfFontSupport {
    private PdfFontSupport() { }

    public static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(PdfFontSupport::isCjk);
    }

    public static boolean register(PdfRendererBuilder builder, List<File> candidates, String text) {
        Set<Integer> required = text.codePoints().filter(PdfFontSupport::isCjk).boxed().collect(Collectors.toSet());
        for (File file : candidates) {
            if (!canEmbed(file, required)) continue;
            builder.useFont(file, "Microsoft YaHei");
            builder.useFont(file, "SimSun");
            builder.useFont(file, "Arial Unicode MS");
            return true;
        }
        return false;
    }

    /** Strictly checks every glyph and only uses a single verified face/collection. */
    public static boolean registerAllText(PdfRendererBuilder builder, List<File> candidates,
                                          List<String> visibleText, CareExecutionReportBudget budget) {
        Set<Integer> required = new HashSet<>();
        for (String text : visibleText) {
            budget.checkTime();
            if (text == null) return false;
            for (int offset = 0; offset < text.length();) {
                if ((offset & 1023) == 0) budget.checkTime();
                int point = text.codePointAt(offset);
                offset += Character.charCount(point);
                if (point == '\n' || point == '\r' || point == '\t') continue;
                int type = Character.getType(point);
                // This renderer has no verified complex-shaping or supplementary-glyph pipeline.
                // Reject these sequences, including joiners/variation selectors, rather than dropping
                // them or claiming that cmap presence alone proves faithful rendering.
                if (point > Character.MAX_VALUE || Character.isSurrogate((char) point)
                        || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                        || type == Character.CONTROL || type == Character.UNASSIGNED
                        || !hasVerifiedSimpleLayout(point)) return false;
                required.add(point);
            }
        }
        for (File file : candidates) {
            budget.checkTime();
            if (!canEmbed(file, required)) continue;
            budget.checkTime();
            builder.useFont(file, "CareReport");
            return true;
        }
        return false;
    }

    /**
     * Conservative layout policy for the renderer's non-shaping, non-bidi path. Accept only
     * BMP Latin, Han, Hiragana, Katakana, precomposed Hangul syllables and non-RTL Common
     * characters, after the mark/control/format exclusions above. All other scripts and
     * conjoining Hangul Jamo remain unavailable even when a font has their cmap entries.
     * This does not normalize, reorder or replace original text to make it renderable.
     */
    private static boolean hasVerifiedSimpleLayout(int point) {
        byte direction = Character.getDirectionality(point);
        if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                || direction == Character.DIRECTIONALITY_ARABIC_NUMBER) return false;
        switch (Character.UnicodeScript.of(point)) {
            case LATIN:
            case HAN:
            case HIRAGANA:
            case KATAKANA:
            case COMMON:
                return true;
            case HANGUL:
                return point >= 0xAC00 && point <= 0xD7A3;
            default:
                return false;
        }
    }

    private static boolean canEmbed(File file, Set<Integer> required) {
        if (!file.isFile() || !file.canRead()) return false;
        try {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                final boolean[] outlines = { true }, coverage = { true };
                final int[] faces = { 0 };
                try (TrueTypeCollection collection = new TrueTypeCollection(file)) {
                    collection.processAllFonts(font -> {
                        faces[0]++;
                        outlines[0] &= font.getTableMap().containsKey("glyf");
                        coverage[0] &= font.getTableMap().containsKey("glyf") && covers(font, required);
                    });
                }
                return faces[0] > 0 && outlines[0] && coverage[0];
            }
            try (TrueTypeFont font = new TTFParser().parse(file)) {
                return font.getTableMap().containsKey("glyf") && covers(font, required);
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException exception) {
            return false;
        }
    }

    private static boolean covers(TrueTypeFont font, Set<Integer> required) throws IOException {
        org.apache.fontbox.ttf.CmapLookup cmap = font.getUnicodeCmapLookup();
        if (cmap == null) return required.isEmpty();
        for (Integer codePoint : required) if (cmap.getGlyphId(codePoint) == 0) return false;
        return true;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }
}
