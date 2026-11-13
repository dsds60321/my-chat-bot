package com.mychatbot.knowledge;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The same conservative boundary is used for indexing and external AI payloads. */
public final class SensitiveData {
    private SensitiveData() {}
    private static final String LABEL = "(?:[a-z0-9_.-]{0,64}(?:password|passwd|pwd|secret|api[ _-]?key|(?:access[ _-]?|refresh[ _-]?|auth[ _-]?)?token)[a-z0-9_.-]{0,64}|비밀번호|암호|비밀키|인증토큰|접근토큰)";
    private static final String SERVICE_CARD_ID =
        "(?<![A-Za-z0-9_])SVCCRD_(?=[0-9a-fA-F]{0,24}[a-fA-F])[0-9a-fA-F]{25}(?![A-Za-z0-9_])";
    private static final String NUMBER_ASSIGNMENT = "\\s*[\\\"']?\\s*[:=：>]\\s*[\\\"']?";
    // Match a complete service-card ID before scanning for unlabeled RRN digit sequences inside it.
    private static final Pattern UNLABELED_RRN = Pattern.compile(
        SERVICE_CARD_ID + "|(?<!\\d)(\\d{6}[- ]?[1-4]\\d{6})(?!\\d)");
    private static final List<Pattern> RULES = List.of(
        Pattern.compile("(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
        Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"),
        Pattern.compile("(?i)\\b(?:gsk_|sk-proj-|sk-|AKIA)[A-Za-z0-9_-]{12,}"),
        Pattern.compile("(?im)[\\\"']?" + LABEL + "[\\\"']?\\s*[:=：]\\s*(?:\\\"[^\\\"\\r\\n]+\\\"|'[^'\\r\\n]+'|[^\\s,;}<>]+)"),
        Pattern.compile("(?is)<" + LABEL + "(?:\\s[^>]*)?>\\s*[^<\\s][^<]*</" + LABEL + "\\s*>"),
        Pattern.compile("(?i)(?:계좌(?:번호)?|account(?:_?number|_?no)|accnt_?no|acct_?no|주민등록번호|카드번호|card_?number|phone|tel)"
            + NUMBER_ASSIGNMENT + "[0-9][0-9 -]{6,}[0-9]"),
        Pattern.compile("(?i)주민등록번호" + NUMBER_ASSIGNMENT + SERVICE_CARD_ID),
        Pattern.compile("(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)"),
        Pattern.compile("(?i)\\b[A-Z0-9._%+-]{1,64}@[A-Z0-9.-]{1,253}\\.[A-Z]{2,}\\b")
    );
    // Use table column associations rather than treating every mention of a password as a credential.
    private static boolean sensitiveTable(String text) {
        java.util.Set<Integer> sensitiveColumns = new java.util.HashSet<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank() || (line.indexOf('|') < 0 && line.indexOf('\t') < 0)) {
                sensitiveColumns.clear();
                continue;
            }
            String[] columns = line.split("[|\\t]", -1);
            for (int column = 0; column < columns.length; column++) {
                String value = columns[column].strip();
                int equals = value.indexOf('=');
                if (equals >= 0) value = value.substring(equals + 1).strip();
                if (sensitiveHeader(value)) sensitiveColumns.add(column);
                else if (sensitiveColumns.contains(column) && !value.isBlank() && !value.matches("[-: ]+")) return true;
            }
        }
        return false;
    }

    /** False means reject the complete document or request before external transmission. */
    public static boolean guard(String text) {
        return text != null && !sensitiveTable(text) && RULES.stream().noneMatch(rule -> rule.matcher(text).find())
            && UNLABELED_RRN.matcher(text).results().noneMatch(match -> match.group(1) != null);
    }

    public static String mask(String text) {
        if (text == null) return "";
        if (sensitiveTable(text)) return "[REDACTED]";
        String result = text;
        for (Pattern rule : RULES) result = rule.matcher(result).replaceAll("[REDACTED]");
        return UNLABELED_RRN.matcher(result).replaceAll(match -> match.group(1) == null
            ? Matcher.quoteReplacement(match.group()) : "[REDACTED]");
    }

    static boolean sensitiveHeader(String value) {
        return value != null && value.strip().matches("(?i)(?:password|passwd|pwd|secret|api[_ -]?key|(?:access[_ -]?|refresh[_ -]?)?token|비밀번호|암호|비밀키|인증토큰|접근토큰|계좌번호|주민등록번호|카드번호|account[ _]?(?:no|number)|accnt_?no|acct_?no|card[ _]?number|phone|tel|email)");
    }
}
