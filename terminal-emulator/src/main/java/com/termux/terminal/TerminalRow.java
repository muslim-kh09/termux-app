package com.termux.terminal;

import java.util.Arrays;

/**
 * A row in a terminal, composed of a fixed number of cells.
 * <p>
 * The text in the row is stored in a char[] array, {@link #mText}, for quick access during rendering.
 */
public final class TerminalRow {

    private static final float SPARE_CAPACITY_FACTOR = 1.5f;

    private static final long UNDERLINE_SLOT_BITS = (long) TextStyle.UNDERLINE_SLOT_MASK << TextStyle.UNDERLINE_SLOT_SHIFT;

    /**
     * Max combining characters that can exist in a column, that are separate from the base character
     * itself. Any additional combining characters will be ignored and not added to the column.
     *
     * There does not seem to be limit in unicode standard for max number of combination characters
     * that can be combined but such characters are primarily under 10.
     *
     * "Section 3.6 Combination" of unicode standard contains combining characters info.
     * - https://www.unicode.org/versions/Unicode15.0.0/ch03.pdf
     * - https://en.wikipedia.org/wiki/Combining_character#Unicode_ranges
     * - https://stackoverflow.com/questions/71237212/what-is-the-maximum-number-of-unicode-combined-characters-that-may-be-needed-to
     *
     * UAX15-D3 Stream-Safe Text Format limits to max 30 combining characters.
     * > The value of 30 is chosen to be significantly beyond what is required for any linguistic or technical usage.
     * > While it would have been feasible to chose a smaller number, this value provides a very wide margin,
     * > yet is well within the buffer size limits of practical implementations.
     * - https://unicode.org/reports/tr15/#Stream_Safe_Text_Format
     * - https://stackoverflow.com/a/11983435/14686958
     *
     * We choose the value 15 because it should be enough for terminal based applications and keep
     * the memory usage low for a terminal row, won't affect performance or cause terminal to
     * lag or hang, and will keep malicious applications from causing harm. The value can be
     * increased if ever needed for legitimate applications.
     */
    private static final int MAX_COMBINING_CHARACTERS_PER_COLUMN = 15;

    /** The number of columns in this terminal row. */
    private final int mColumns;
    /** The text filling this terminal row. */
    public char[] mText;
    /** The number of java chars used in {@link #mText}. */
    private int mSpaceUsed;
    /** If this row has been line wrapped due to text output at the end of line. */
    boolean mLineWrap;
    /** The style bits of each cell in the row. See {@link TextStyle}. */
    final long[] mStyle;
    /** If this row might contain chars with width != 1, used for deactivating fast path */
    boolean mHasNonOneWidthOrSurrogateChars;
    /** Latches true if an RTL script or BiDi control codepoint was written to this row. */
    public boolean mMayContainRtlOrBidi;
    /** Cached visual layout object. Type is Object to decouple terminal emulator from view module. */
    public Object mCachedBidiLayout;
    public int[] mLogicalToVisual;
    public int[] mVisualToLogical;
    /** If this row has a {@link TerminalBitmap}. Used for performance only. */
    public boolean mHasTerminalBitmap;
    /** The colors of the underline slots of this row as ARGB, null until a cell gets an underline color. Slot 0 is unused. */
    private int[] mUnderlineSlotColors;

    /** Returns true if {@code codePoint} belongs to an RTL script or BiDi formatting block. */
    public static boolean isRtlOrBidiCodePoint(int codePoint) {
        if (codePoint < 0x0590) return false;
        if (codePoint <= 0x08FF) return true; // Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic, Arabic Ext
        if (codePoint >= 0x200E && codePoint <= 0x200F) return true; // LRM, RLM
        if (codePoint >= 0x202A && codePoint <= 0x202E) return true; // LRE..RLO
        if (codePoint >= 0x2066 && codePoint <= 0x2069) return true; // LRI..PDI
        if (codePoint >= 0xFB1D && codePoint <= 0xFDFF) return true; // Hebrew & Arabic Presentation Forms-A
        if (codePoint >= 0xFE70 && codePoint <= 0xFEFF) return true; // Arabic Presentation Forms-B & BOM
        if (codePoint >= 0x10800 && codePoint <= 0x10FFF) return true; // Historic RTL scripts
        return codePoint >= 0x1E800 && codePoint <= 0x1EEFF; // Adlam, Mende Kikakui, Arabic Math
    }

    /** Invalidates cached visual BiDi layout and column maps for this row. */
    public void invalidateBidiLayout() {
        mCachedBidiLayout = null;
        mLogicalToVisual = null;
        mVisualToLogical = null;
    }

    /** Construct a blank row (containing only whitespace, ' ') with a specified style. */
    public TerminalRow(int columns, long style) {
        mColumns = columns;
        mText = new char[(int) (SPARE_CAPACITY_FACTOR * columns)];
        mStyle = new long[columns];
        clear(style);
    }

    /**
     * Copies cells, also inside one row. A copy inside one row reads the old text and the old styles from a snapshot,
     * because the cells that were already written must not change what is read later.
     * NOTE: The sourceX2 is exclusive.
     */
    public void copyInterval(TerminalRow line, int sourceX1, int sourceX2, int destinationX) {
        mHasNonOneWidthOrSurrogateChars |= line.mHasNonOneWidthOrSurrogateChars;
        final int x1 = line.findStartOfColumn(sourceX1);
        final int x2 = line.findStartOfColumn(sourceX2);
        boolean startingFromSecondHalfOfWideChar = (sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1));
        final char[] sourceChars = (this == line) ? Arrays.copyOf(line.mText, line.mText.length) : line.mText;
        final long[] sourceStyles = (this == line) ? Arrays.copyOf(line.mStyle, line.mStyle.length) : line.mStyle;
        final int[] sourceUnderlineColors = (this == line && mUnderlineSlotColors != null)
            ? Arrays.copyOf(mUnderlineSlotColors, mUnderlineSlotColors.length) : line.mUnderlineSlotColors;
        int latestNonCombiningWidth = 0;
        for (int i = x1; i < x2; i++) {
            char sourceChar = sourceChars[i];
            int codePoint = Character.isHighSurrogate(sourceChar) ? Character.toCodePoint(sourceChar, sourceChars[++i]) : sourceChar;
            if (startingFromSecondHalfOfWideChar) {
                // Just treat copying second half of wide char as copying whitespace.
                codePoint = ' ';
                startingFromSecondHalfOfWideChar = false;
            }
            int w = WcWidth.width(codePoint);
            if (w > 0) {
                destinationX += latestNonCombiningWidth;
                sourceX1 += latestNonCombiningWidth;
                latestNonCombiningWidth = w;
            }
            final long sourceStyle = sourceStyles[sourceX1];
            setChar(destinationX, codePoint, sourceStyle, underlineColorOf(sourceUnderlineColors, sourceStyle));
        }
    }

    public int getSpaceUsed() {
        return mSpaceUsed;
    }

    /** Note that the column may end of second half of wide character. */
    public int findStartOfColumn(int column) {
        if (column == mColumns) return getSpaceUsed();

        int currentColumn = 0;
        int currentCharIndex = 0;
        while (true) { // 0<2 1 < 2
            int newCharIndex = currentCharIndex;
            char c = mText[newCharIndex++]; // cci=1, cci=2
            boolean isHigh = Character.isHighSurrogate(c);
            int codePoint = isHigh ? Character.toCodePoint(c, mText[newCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint); // 1, 2
            if (wcwidth > 0) {
                currentColumn += wcwidth;
                if (currentColumn == column) {
                    while (newCharIndex < mSpaceUsed) {
                        // Skip combining chars.
                        if (Character.isHighSurrogate(mText[newCharIndex])) {
                            if (WcWidth.width(Character.toCodePoint(mText[newCharIndex], mText[newCharIndex + 1])) <= 0) {
                                newCharIndex += 2;
                            } else {
                                break;
                            }
                        } else if (WcWidth.width(mText[newCharIndex]) <= 0) {
                            newCharIndex++;
                        } else {
                            break;
                        }
                    }
                    return newCharIndex;
                } else if (currentColumn > column) {
                    // Wide column going past end.
                    return currentCharIndex;
                }
            }
            currentCharIndex = newCharIndex;
        }
    }

    public boolean wideDisplayCharacterStartingAt(int column) {
        for (int currentCharIndex = 0, currentColumn = 0; currentCharIndex < mSpaceUsed; ) {
            char c = mText[currentCharIndex++];
            int codePoint = Character.isHighSurrogate(c) ? Character.toCodePoint(c, mText[currentCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                if (currentColumn == column && wcwidth == 2) return true;
                currentColumn += wcwidth;
                if (currentColumn > column) return false;
            }
        }
        return false;
    }

    public void clear(long style) {
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style & ~UNDERLINE_SLOT_BITS);
        mUnderlineSlotColors = null;
        mSpaceUsed = mColumns;
        mHasNonOneWidthOrSurrogateChars = false;
        mMayContainRtlOrBidi = false;
        invalidateBidiLayout();
        mHasTerminalBitmap = false;
        mLineWrap = false;
    }

    // https://github.com/steven676/Android-Terminal-Emulator/commit/9a47042620bec87617f0b4f5d50568535668fe26
    public void setChar(int columnToSet, int codePoint, long style) {
        if (columnToSet  < 0 || columnToSet >= mStyle.length)
            throw new IllegalArgumentException("TerminalRow.setChar(): columnToSet=" + columnToSet + ", codePoint=" + codePoint + ", style=" + style);

        invalidateBidiLayout();
        mStyle[columnToSet] = style;

        if (!mHasTerminalBitmap && TextStyle.isTerminalBitmap(style)) {
            mHasTerminalBitmap = true;
        }
        if (!mMayContainRtlOrBidi && isRtlOrBidiCodePoint(codePoint)) {
            mMayContainRtlOrBidi = true;
        }

        final int newCodePointDisplayWidth = WcWidth.width(codePoint);

        // Fast path when we don't have any chars with width != 1
        if (!mHasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true;
            } else {
                mText[columnToSet] = (char) codePoint;
                return;
            }
        }

        final boolean newIsCombining = newCodePointDisplayWidth <= 0;

        boolean wasExtraColForWideChar = (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1);

        if (newIsCombining) {
            // When standing at second half of wide character and inserting combining:
            if (wasExtraColForWideChar) columnToSet--;
        } else {
            // Check if we are overwriting the second half of a wide character starting at the previous column:
            if (wasExtraColForWideChar) setChar(columnToSet - 1, ' ', style);
            // Check if we are overwriting the first half of a wide character starting at the next column:
            boolean overwritingWideCharInNextColumn = newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1);
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, ' ', style);
        }

        char[] text = mText;
        final int oldStartOfColumnIndex = findStartOfColumn(columnToSet);
        final int oldCodePointDisplayWidth = WcWidth.width(text, oldStartOfColumnIndex);

        // Get the number of elements in the mText array this column uses now
        int oldCharactersUsedForColumn;
        if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            int oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth);
            oldCharactersUsedForColumn = oldEndOfColumnIndex - oldStartOfColumnIndex;
        } else {
            // Last character.
            oldCharactersUsedForColumn = mSpaceUsed - oldStartOfColumnIndex;
        }

        // If MAX_COMBINING_CHARACTERS_PER_COLUMN already exist in column, then ignore adding additional combining characters.
        if (newIsCombining) {
            int combiningCharsCount = WcWidth.zeroWidthCharsCount(mText, oldStartOfColumnIndex, oldStartOfColumnIndex + oldCharactersUsedForColumn);
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN)
                return;
        }

        // Find how many chars this column will need
        int newCharactersUsedForColumn = Character.charCount(codePoint);
        if (newIsCombining) {
            // Combining characters are added to the contents of the column instead of overwriting them, so that they
            // modify the existing contents.
            // FIXME: Unassigned characters also get width=0.
            newCharactersUsedForColumn += oldCharactersUsedForColumn;
        }

        int oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn;
        int newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn;

        final int javaCharDifference = newCharactersUsedForColumn - oldCharactersUsedForColumn;
        if (javaCharDifference > 0) {
            // Shift the rest of the line right.
            int oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex;
            if (mSpaceUsed + javaCharDifference > text.length) {
                // We need to grow the array
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, oldNextColumnIndex);
                System.arraycopy(text, oldNextColumnIndex, newText, newNextColumnIndex, oldCharactersAfterColumn);
                mText = text = newText;
            } else {
                System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, oldCharactersAfterColumn);
            }
        } else if (javaCharDifference < 0) {
            // Shift the rest of the line left.
            System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - oldNextColumnIndex);
        }
        mSpaceUsed += javaCharDifference;

        // Store char. A combining character is stored at the end of the existing contents so that it modifies them:
        //noinspection ResultOfMethodCallIgnored - since we already now how many java chars is used.
        Character.toChars(codePoint, text, oldStartOfColumnIndex + (newIsCombining ? oldCharactersUsedForColumn : 0));

        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {
            // Replace second half of wide char with a space. Which mean that we actually add a ' ' java character.
            if (mSpaceUsed + 1 > text.length) {
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, newNextColumnIndex);
                System.arraycopy(text, newNextColumnIndex, newText, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
                mText = text = newText;
            } else {
                System.arraycopy(text, newNextColumnIndex, text, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
            }
            text[newNextColumnIndex] = ' ';

            ++mSpaceUsed;
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (columnToSet == mColumns - 1) {
                throw new IllegalArgumentException("Cannot put wide character in last column");
            } else if (columnToSet == mColumns - 2) {
                // Truncate the line to the second part of this wide char:
                mSpaceUsed = newNextColumnIndex;
            } else {
                // Overwrite the contents of the next column, which mean we actually remove java characters. Due to the
                // check at the beginning of this method we know that we are not overwriting a wide char.
                int newNextNextColumnIndex = newNextColumnIndex + (Character.isHighSurrogate(mText[newNextColumnIndex]) ? 2 : 1);
                int nextLen = newNextNextColumnIndex - newNextColumnIndex;

                // Shift the array leftwards.
                System.arraycopy(text, newNextNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - newNextNextColumnIndex);
                mSpaceUsed -= nextLen;
            }
        }
    }

    /** The underline color of a cell with this style as ARGB, or 0 if the underline has the foreground color. */
    public int getUnderlineColor(long style) {
        return underlineColorOf(mUnderlineSlotColors, style);
    }

    private static int underlineColorOf(int[] slotColors, long style) {
        final int slot = TextStyle.decodeUnderlineSlot(style);
        return (slot == 0 || slotColors == null) ? 0 : slotColors[slot];
    }

    /**
     * Same as {@link #setChar(int, int, long)}, but the underline color is given as ARGB instead of as a slot in the
     * style, 0 means the foreground color. The slot bits of {@code style} are replaced.
     */
    public void setChar(int columnToSet, int codePoint, long style, int underlineColor) {
        style &= ~UNDERLINE_SLOT_BITS;
        if (underlineColor != 0) style |= (long) slotForUnderlineColor(underlineColor) << TextStyle.UNDERLINE_SLOT_SHIFT;
        setChar(columnToSet, codePoint, style);
    }

    /** Finds the slot of a color, or takes a free one. */
    private int slotForUnderlineColor(int color) {
        if (mUnderlineSlotColors == null) mUnderlineSlotColors = new int[TextStyle.UNDERLINE_SLOT_MASK + 1];
        final int[] colors = mUnderlineSlotColors;
        for (int slot = 1; slot < colors.length; slot++) {
            if (colors[slot] == color) return slot;
        }
        for (int slot = 1; slot < colors.length; slot++) {
            if (colors[slot] == 0) {
                colors[slot] = color;
                return slot;
            }
        }
        // All slots hold a color, so release the ones that no cell uses any more.
        final boolean[] used = new boolean[colors.length];
        for (long cellStyle : mStyle) used[TextStyle.decodeUnderlineSlot(cellStyle)] = true;
        int freed = 0;
        for (int slot = 1; slot < colors.length; slot++) {
            if (!used[slot]) {
                colors[slot] = 0;
                if (freed == 0) freed = slot;
            }
        }
        if (freed != 0) {
            colors[freed] = color;
            return freed;
        }
        // Every slot is used by a cell, so the cells that take the new color get the closest one of the 15 colors.
        int nearest = 1;
        long nearestDistance = Long.MAX_VALUE;
        for (int slot = 1; slot < colors.length; slot++) {
            long distance = 0;
            for (int shift = 0; shift < 24; shift += 8) {
                long d = ((colors[slot] >>> shift) & 0xff) - ((color >>> shift) & 0xff);
                distance += d * d;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = slot;
            }
        }
        return nearest;
    }

    boolean isBlank() {
        for (int charIndex = 0, charLen = getSpaceUsed(); charIndex < charLen; charIndex++)
            if (mText[charIndex] != ' ') return false;
        return true;
    }

    public final long getStyle(int column) {
        return mStyle[column];
    }

}
