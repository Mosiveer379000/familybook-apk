package com.familybook.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java helper for the NID / Birth-ID verification step.
 * It never needs the Bengali line: ML Kit's text recognizer has no Bengali model,
 * so we read ONLY the English lines of the card ("Name", "Date of Birth", "NID No.")
 * and compare them with the profile using fuzzy matching, which tolerates blur and OCR slips.
 *
 * Typical use (after ML Kit gives you the full text):
 *   String raw = result.getText();
 *   boolean nameOk = NidTextParser.nameMatches(raw, profileName);          // fuzzy
 *   String dob     = NidTextParser.extractDob(raw);                        // "dd/MM/yyyy" or null
 *   boolean dobOk  = NidTextParser.dobMatches(dob, profileDobDdMmYyyy);
 *   String nid     = NidTextParser.extractNidNumber(raw);                  // digits only or null
 */
public final class NidTextParser {
    private NidTextParser() { }

    private static final String[] MON = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    // ---------------------------------------------------------------- name
    /** true when enough of the profile-name words are found in the OCR text (typo-tolerant). */
    public static boolean nameMatches(String ocrText, String profileName) {
        return nameScore(ocrText, profileName) >= 0.67f;
    }

    /** 0..1 : share of profile-name words that were found in the OCR text. */
    public static float nameScore(String ocrText, String profileName) {
        if (ocrText == null || profileName == null) return 0f;
        // letters only, so merged words like "MASUDALAM" still work
        String hay = lettersOnly(ocrText);
        String[] words = canonicalName(profileName).split("\\s+");
        int total = 0, found = 0;
        for (String w : words) {
            if (w.length() < 2) continue;
            total++;
            if (containsFuzzy(hay, w) || (w.equals("MOHAMMAD") && hasMohammadVariant(hay))) found++;
        }
        return total == 0 ? 0f : (float) found / total;
    }

    private static boolean hasMohammadVariant(String hay) {
        return hay.contains("MD") || hay.contains("MOHD") || hay.contains("MUHAMMAD")
                || hay.contains("MOHAMMED") || hay.contains("MOHAMAD") || hay.contains("MOHAMMAD");
    }

    private static String canonicalName(String n) {
        String s = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", " ").trim();
        return s.replaceAll("\\s+", " ");
    }

    private static String lettersOnly(String t) {
        return t.toUpperCase(Locale.ROOT)
                .replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B')
                .replaceAll("[^A-Z]", "");
    }

    /** sliding window: does `hay` hold a substring within edit distance (len<=4:0, len<=7:1, else 2) of `word`? */
    private static boolean containsFuzzy(String hay, String word) {
        int maxErr = word.length() <= 4 ? 0 : (word.length() <= 7 ? 1 : 2);
        for (int len = word.length() - maxErr; len <= word.length() + maxErr; len++) {
            if (len <= 0) continue;
            for (int i = 0; i + len <= hay.length(); i++) {
                if (editDistance(hay.substring(i, i + len), word) <= maxErr) return true;
            }
        }
        return false;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ----------------------------------------------------------------- DOB
    private static final Pattern DOB_TEXT = Pattern.compile("([0-9OIl]{1,2})\\s*[-/. ]?\\s*([A-Za-z0]{3,9})\\s*[-/. ]?\\s*([12][0-9OIl]{3})");
    private static final Pattern DOB_NUM = Pattern.compile("([0-9OIl]{1,2})\\s*[-/.]\\s*([0-9OIl]{1,2})\\s*[-/.]\\s*([12][0-9OIl]{3})");

    /** returns "dd/MM/yyyy" or null. Handles "10 Oct 1996", "10-10-1996", "1O 0ct 1996" ... */
    public static String extractDob(String text) {
        if (text == null) return null;
        Matcher m = DOB_TEXT.matcher(text);
        while (m.find()) {
            int mon = monthIndex(m.group(2));
            if (mon < 0) continue;
            String r = build(m.group(1), String.valueOf(mon + 1), m.group(3));
            if (r != null) return r;
        }
        m = DOB_NUM.matcher(text);
        while (m.find()) {
            String r = build(m.group(1), digits(m.group(2)), m.group(3));
            if (r != null) return r;
        }
        return null;
    }

    private static String build(String d, String mo, String y) {
        try {
            int day = Integer.parseInt(digits(d));
            int month = Integer.parseInt(digits(mo));
            int year = Integer.parseInt(digits(y));
            if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1900 || year > 2100) return null;
            return String.format(Locale.ROOT, "%02d/%02d/%04d", day, month, year);
        } catch (Exception e) {
            return null;
        }
    }

    private static String digits(String s) {
        return s.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1');
    }

    private static int monthIndex(String w) {
        String u = w.toUpperCase(Locale.ROOT).replace('0', 'O').replace('1', 'I');
        if (u.length() > 3) u = u.substring(0, 3);
        int best = -1, bestD = 2;
        for (int i = 0; i < 12; i++) {
            int d = editDistance(u, MON[i]);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** both dates as dd/MM/yyyy (leading zeros ignored). */
    public static boolean dobMatches(String ocrDob, String profileDob) {
        if (ocrDob == null || profileDob == null) return false;
        return norm(ocrDob).equals(norm(profileDob));
    }

    private static String norm(String d) {
        String[] p = d.trim().split("/");
        if (p.length != 3) return d;
        try {
            return Integer.parseInt(p[0]) + "/" + Integer.parseInt(p[1]) + "/" + Integer.parseInt(p[2]);
        } catch (Exception e) {
            return d;
        }
    }

    // ------------------------------------------------------------ NID number
    /** First 10/13/17 digit number (spaces allowed between groups). Returns digits only, or null. */
    public static String extractNidNumber(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?<![0-9])([0-9OIl][0-9OIl ]{8,20}[0-9OIl])(?![0-9])").matcher(text);
        while (m.find()) {
            String d = digits(m.group(1)).replace(" ", "");
            if (d.matches("[0-9]+") && (d.length() == 10 || d.length() == 13 || d.length() == 17)) return d;
        }
        return null;
    }
}
