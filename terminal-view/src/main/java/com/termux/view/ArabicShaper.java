package com.termux.view;

public final class ArabicShaper {
    private static final int JOINING_NONE = 0;
    private static final int JOINING_RIGHT = 1;
    private static final int JOINING_DUAL = 2;

    private static int getJoiningType(int cp) {
        if (cp == 0) return JOINING_NONE;

        // Treat LAM-ALIF presentation form ligatures as right-joining
        if (cp >= 0xFEF5 && cp <= 0xFEFC) return JOINING_RIGHT;

        if (cp >= 0x0600 && cp <= 0x06FF) {
            if (cp >= 0x064B && cp <= 0x065F) return JOINING_NONE; // Diacritics
            if (cp == 0x0670) return JOINING_NONE; // Super-script Alif

            // Right-joining characters
            if (cp == 0x0622 || cp == 0x0623 || cp == 0x0624 || cp == 0x0625 || cp == 0x0627) return JOINING_RIGHT;
            if (cp == 0x062F || cp == 0x0630) return JOINING_RIGHT; // Dal, Thal
            if (cp == 0x0631 || cp == 0x0632) return JOINING_RIGHT; // Reh, Zain
            if (cp == 0x0648) return JOINING_RIGHT; // Waw
            if (cp == 0x0649) return JOINING_RIGHT; // Alif Maksura
            if (cp == 0x0698) return JOINING_RIGHT; // Jeh (Persian)
            if (cp == 0x06D2) return JOINING_RIGHT; // Yeh Barree (Urdu)

            // Most other Arabic letters are dual-joining
            if (cp >= 0x0621 && cp <= 0x064A) {
                if (cp == 0x0621) return JOINING_NONE; // Hamza
                if (cp == 0x0629) return JOINING_RIGHT; // Teh Marbuta
                return JOINING_DUAL;
            }

            // Persian & Urdu dual-joining letters
            if (cp == 0x067E || cp == 0x0686 || cp == 0x06A9 || cp == 0x06AF || cp == 0x06CC) {
                return JOINING_DUAL;
            }
        }
        return JOINING_NONE;
    }

    private static boolean isCombining(int cp) {
        return (cp >= 0x064B && cp <= 0x065F) || cp == 0x0670;
    }

    private static int[] getForms(int cp) {
        switch (cp) {
            case 0x0622: return new int[] { 0xFE81, 0, 0, 0xFE82 }; // ALIF_WITH_MADDA
            case 0x0623: return new int[] { 0xFE83, 0, 0, 0xFE84 }; // ALIF_WITH_HAMZA_ABOVE
            case 0x0624: return new int[] { 0xFE85, 0, 0, 0xFE86 }; // WAW_WITH_HAMZA_ABOVE
            case 0x0625: return new int[] { 0xFE87, 0, 0, 0xFE88 }; // ALIF_WITH_HAMZA_BELOW
            case 0x0626: return new int[] { 0xFE89, 0xFE8B, 0xFE8C, 0xFE8A }; // YEH_WITH_HAMZA_ABOVE
            case 0x0627: return new int[] { 0xFE8D, 0, 0, 0xFE8E }; // ALIF
            case 0x0628: return new int[] { 0xFE8F, 0xFE91, 0xFE92, 0xFE90 }; // BEH
            case 0x0629: return new int[] { 0xFE93, 0, 0, 0xFE94 }; // TEH_MARBUTA
            case 0x062A: return new int[] { 0xFE95, 0xFE97, 0xFE98, 0xFE96 }; // TEH
            case 0x062B: return new int[] { 0xFE99, 0xFE9B, 0xFE9C, 0xFE9A }; // THEH
            case 0x062C: return new int[] { 0xFE9D, 0xFE9F, 0xFEA0, 0xFE9E }; // JEEM
            case 0x062D: return new int[] { 0xFEA1, 0xFEA3, 0xFEA4, 0xFEA2 }; // HAH
            case 0x062E: return new int[] { 0xFEA5, 0xFEA7, 0xFEA8, 0xFEA6 }; // KHAH
            case 0x062F: return new int[] { 0xFEA9, 0, 0, 0xFEAA }; // DAL
            case 0x0630: return new int[] { 0xFEAB, 0, 0, 0xFEAC }; // THAL
            case 0x0631: return new int[] { 0xFEAD, 0, 0, 0xFEAE }; // REH
            case 0x0632: return new int[] { 0xFEAF, 0, 0, 0xFEB0 }; // ZAIN
            case 0x0633: return new int[] { 0xFEB1, 0xFEB3, 0xFEB4, 0xFEB2 }; // SEEN
            case 0x0634: return new int[] { 0xFEB5, 0xFEB7, 0xFEB8, 0xFEB6 }; // SHEEN
            case 0x0635: return new int[] { 0xFEB9, 0xFEBB, 0xFEBC, 0xFEBA }; // SAD
            case 0x0636: return new int[] { 0xFEBD, 0xFEBF, 0xFEC0, 0xFEBE }; // DAD
            case 0x0637: return new int[] { 0xFEC1, 0xFEC3, 0xFEC4, 0xFEC2 }; // TAH
            case 0x0638: return new int[] { 0xFEC5, 0xFEC7, 0xFEC8, 0xFEC6 }; // ZHAH
            case 0x0639: return new int[] { 0xFEC9, 0xFECB, 0xFECC, 0xFECA }; // AIN
            case 0x063A: return new int[] { 0xFECD, 0xFECF, 0xFED0, 0xFECE }; // GHAIN
            case 0x0641: return new int[] { 0xFED1, 0xFED3, 0xFED4, 0xFED2 }; // FEH
            case 0x0642: return new int[] { 0xFED5, 0xFED7, 0xFED8, 0xFED6 }; // QAF
            case 0x0643: return new int[] { 0xFED9, 0xFEDB, 0xFEDC, 0xFEDA }; // KAF
            case 0x0644: return new int[] { 0xFEDD, 0xFEDF, 0xFEE0, 0xFEDE }; // LAM
            case 0x0645: return new int[] { 0xFEE1, 0xFEE3, 0xFEE4, 0xFEE2 }; // MEEM
            case 0x0646: return new int[] { 0xFEE5, 0xFEE7, 0xFEE8, 0xFEE6 }; // NOON
            case 0x0647: return new int[] { 0xFEE9, 0xFEEB, 0xFEEC, 0xFEEA }; // HEH
            case 0x0648: return new int[] { 0xFEED, 0, 0, 0xFEEE }; // WAW
            case 0x0649: return new int[] { 0xFEEF, 0xFEF3, 0xFEF4, 0xFEF0 }; // ALIF_MAKSURA
            case 0x064A: return new int[] { 0xFEF1, 0xFEF3, 0xFEF4, 0xFEF2 }; // YEH

            // Persian
            case 0x067E: return new int[] { 0xFB56, 0xFB58, 0xFB59, 0xFB57 }; // PEH
            case 0x0686: return new int[] { 0xFB7A, 0xFB7C, 0xFB7D, 0xFB7B }; // TCHEH
            case 0x0698: return new int[] { 0xFB8A, 0, 0, 0xFB8B }; // JEH
            case 0x06A9: return new int[] { 0xFB8E, 0xFB90, 0xFB91, 0xFB8F }; // KEHEH
            case 0x06AF: return new int[] { 0xFB92, 0xFB94, 0xFB95, 0xFB93 }; // GAF
            case 0x06CC: return new int[] { 0xFEEF, 0xFEF3, 0xFEF4, 0xFEF0 }; // FARSI YEH
            case 0x06D2: return new int[] { 0xFBAE, 0, 0, 0xFBAF }; // YEH BARREE
            default: return null;
        }
    }

    private static int getShapedForm(int cp, boolean connectsRight, boolean connectsLeft) {
        int[] forms = getForms(cp);
        if (forms == null) return cp;

        int isolated = forms[0];
        int initial = forms[1];
        int medial = forms[2];
        int finalForm = forms[3];

        if (connectsRight && connectsLeft) {
            if (medial != 0) return medial;
            if (finalForm != 0) return finalForm;
        } else if (connectsRight) {
            if (finalForm != 0) return finalForm;
        } else if (connectsLeft) {
            if (initial != 0) return initial;
        }
        return isolated;
    }

    public static void shape(BidiLayout.LogicalCell[] cells) {
        int length = cells.length;

        // Pass 1: LAM-ALIF Ligatures
        for (int i = 0; i < length; i++) {
            BidiLayout.LogicalCell cell = cells[i];
            if (cell.codePoint == 0x0644) { // LAM
                int nextIdx = i + 1;
                while (nextIdx < length && cells[nextIdx].codePoint != 0 && 
                       getJoiningType(cells[nextIdx].codePoint) == JOINING_NONE && 
                       isCombining(cells[nextIdx].codePoint)) {
                    nextIdx++;
                }
                if (nextIdx < length) {
                    int nextCp = cells[nextIdx].codePoint;
                    int ligatureCp = 0;
                    if (nextCp == 0x0622) ligatureCp = 0xFEF5;
                    else if (nextCp == 0x0623) ligatureCp = 0xFEF7;
                    else if (nextCp == 0x0625) ligatureCp = 0xFEF9;
                    else if (nextCp == 0x0627) ligatureCp = 0xFEFB;

                    if (ligatureCp != 0) {
                        cell.codePoint = ligatureCp;
                        
                        // Clean Ligature Processing: Move combining characters from ALIF to LAM cell
                        if (cells[nextIdx].combiningChars != null) {
                            for (int k = 0; k < cells[nextIdx].combiningCount; k++) {
                                cell.addCombining(cells[nextIdx].combiningChars[k]);
                            }
                            cells[nextIdx].combiningChars = null;
                            cells[nextIdx].combiningCount = 0;
                        }
                        
                        cells[nextIdx].codePoint = 0; // Clear the Alif cell
                        cells[nextIdx].displayWidth = 1; // Preserve width 1 to maintain grid cell flow
                    }
                }
            }
        }

        // Pass 2: Contextual Shaping
        for (int i = 0; i < length; i++) {
            BidiLayout.LogicalCell cell = cells[i];
            int cp = cell.codePoint;
            if (cp == 0) continue;

            boolean isLigature = (cp >= 0xFEF5 && cp <= 0xFEFB);
            int joiningType = getJoiningType(cp);
            if (joiningType == JOINING_NONE && !isLigature) continue;

            int prevCp = 0;
            int prevIdx = i - 1;
            while (prevIdx >= 0) {
                if (cells[prevIdx].codePoint != 0 && !isCombining(cells[prevIdx].codePoint)) {
                    prevCp = cells[prevIdx].codePoint;
                    break;
                }
                prevIdx--;
            }

            int nextCp = 0;
            int nextIdx = i + 1;
            while (nextIdx < length) {
                if (cells[nextIdx].codePoint != 0 && !isCombining(cells[nextIdx].codePoint)) {
                    nextCp = cells[nextIdx].codePoint;
                    break;
                }
                nextIdx++;
            }

            // Connection Rules:
            // connectsRight: previous character must be DUAL joining, and current must be DUAL or RIGHT joining.
            boolean connectsRight = false;
            if (prevCp != 0) {
                int prevJoin = getJoiningType(prevCp);
                int currJoin = getJoiningType(cp);
                connectsRight = (prevJoin == JOINING_DUAL) && (currJoin == JOINING_DUAL || currJoin == JOINING_RIGHT);
            }

            // connectsLeft: current character must be DUAL joining, and next must be DUAL or RIGHT joining.
            boolean connectsLeft = false;
            if (nextCp != 0) {
                int currJoin = getJoiningType(cp);
                int nextJoin = getJoiningType(nextCp);
                connectsLeft = (currJoin == JOINING_DUAL) && (nextJoin == JOINING_DUAL || nextJoin == JOINING_RIGHT);
            }

            if (isLigature) {
                if (connectsRight) {
                    cell.codePoint = cp + 1; // Final form is isolated + 1
                }
            } else {
                cell.codePoint = getShapedForm(cp, connectsRight, connectsLeft);
            }
        }
    }
}
