package com.familybook.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NID / জন্ম নিবন্ধনের OCR লেখা থেকে ফর্ম পূরণের তথ্য বের করে (শুধু ইংরেজি লাইন পড়া হয়)।
 *  - বাংলাদেশি: "Government of the People's Republic of Bangladesh" লেখা পাওয়া গেলে
 *  - নাম: "Name" লেবেলের পরের ইংরেজি বড় হাতের লাইন
 *  - জেন্ডার: নামের শুরুতে Mohammad/Md/Mohd (বা বাংলা মোহাম্মদ/মোঃ) থাকলে Male, নাহলে খালি
 *  - জন্মতারিখ ও নম্বর (10/13/17 ডিজিট): NidTextParser দিয়ে
 */
final class NidAutoFill {
    private NidAutoFill() { }

    static final class Result {
        boolean bangladeshi;
        String name;      // Title Case, যেমন "Mohammad Masud Alam"; না পেলে null
        String dob;       // dd/MM/yyyy; না পেলে null
        String nid;       // শুধু ডিজিট; না পেলে null
        boolean male;     // নাম Mohammad দিয়ে শুরু হলে true
        boolean verified() { return bangladeshi && name != null && dob != null && nid != null; }
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "GOVERNMENT", "PEOPLE", "PEOPLES", "REPUBLIC", "BANGLADESH", "NATIONAL", "CARD", "DATE",
            "BIRTH", "NAME", "FATHER", "MOTHER", "NID", "NO", "REGISTRATION", "CERTIFICATE", "OF", "THE"));

    private static final Pattern NAME_INLINE = Pattern.compile("^\\s*name\\b[:\\s]*(.*)$", Pattern.CASE_INSENSITIVE);

    static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        r.bangladeshi = NidTextParser.nameScore(text, "PEOPLES REPUBLIC BANGLADESH") >= 0.67f;
        r.dob = NidTextParser.extractDob(text);
        r.nid = NidTextParser.extractNidNumber(text);
        r.name = extractName(text);
        r.male = isMale(r.name, text);
        return r;
    }

    // ---------------------------------------------------------------- নাম
    private static String extractName(String text) {
        String[] lines = text.split("\\r?\\n");
        // ১) "Name" লেবেলের পরের লাইন
        for (int i = 0; i < lines.length; i++) {
            Matcher m = NAME_INLINE.matcher(lines[i]);
            boolean label = m.matches() && lines[i].trim().length() <= 60;
            String letters = lines[i].toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
            boolean bareLabel = letters.length() <= 5 && NidTextParser.editDistance(letters, "NAME") <= 1;
            if (label) {
                String c = candidate(m.group(1));
                if (c != null) return c;
            }
            if (label || bareLabel) {
                for (int j = i + 1; j <= i + 3 && j < lines.length; j++) {
                    String c = candidate(lines[j]);
                    if (c != null) return c;
                }
            }
        }
        // ২) না পেলে: কমপক্ষে ২ শব্দের প্রথম বড় হাতের লাইন
        for (String l : lines) {
            String c = candidate(l);
            if (c != null && c.contains(" ")) return c;
        }
        return null;
    }

    /** লাইনটি ইংরেজি বড় হাতের নাম হলে Title Case-এ ফেরত, নাহলে null */
    private static String candidate(String line) {
        if (line == null) return null;
        String l = line.trim();
        if (l.length() < 5) return null;
        int letters = 0, upper = 0, nonSpace = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == ' ') continue;
            nonSpace++;
            if (c >= 'A' && c <= 'Z') { letters++; upper++; }
            else if (c >= 'a' && c <= 'z') letters++;
            else if (c == '.') { /* Md. */ }
        }
        if (nonSpace == 0 || letters < 0.85f * nonSpace) return null;      // বাংলা/সংখ্যা মেশানো লাইন বাদ
        if (upper < 0.9f * letters) return null;                           // নাম কার্ডে বড় হাতের
        String clean = l.replaceAll("[^A-Za-z. ]", " ").replaceAll("\\s+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : clean.split(" ")) {
            String key = w.replace(".", "").toUpperCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            if (STOP.contains(key)) return null;
            if (sb.length() > 0) sb.append(' ');
            sb.append(key.charAt(0)).append(key.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.length() >= 4 ? sb.toString() : null;
    }

    // ------------------------------------------------------------- জেন্ডার
    private static boolean isMale(String name, String fullText) {
        if (fullText != null && (fullText.contains("\u09AE\u09CB\u09B9\u09BE\u09AE\u09CD\u09AE\u09A6")      // মোহাম্মদ
                || fullText.contains("\u09AE\u09CB\u0983"))) return true;                                    // মোঃ
        if (name == null) return false;
        String first = name.split(" ")[0].toUpperCase(Locale.ROOT);
        if (first.equals("MD") || first.equals("MOHD")) return true;
        return first.length() >= 6 && NidTextParser.editDistance(first, "MOHAMMAD") <= 1
                || first.equals("MUHAMMAD") || first.equals("MOHAMMED") || first.equals("MOHAMAD");
    }
}
