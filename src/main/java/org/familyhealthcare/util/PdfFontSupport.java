package org.familyhealthcare.util;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;

import java.io.File;
import java.io.IOException;
import java.util.List;
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
