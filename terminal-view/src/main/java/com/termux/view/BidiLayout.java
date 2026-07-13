package com.termux.view;

import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;
import java.text.Bidi;

public final class BidiLayout {
    
    public static class LogicalCell {
        public int codePoint;
        public int displayWidth;
        public long style;
        public boolean insideCursor;
        public boolean insideSelection;
        public int originalColumn;
        public int[] combiningChars = null;
        public int combiningCount = 0;

        public void addCombining(int cp) {
            if (combiningChars == null) {
                combiningChars = new int[4];
            } else if (combiningCount == combiningChars.length) {
                int[] newArr = new int[combiningChars.length * 2];
                System.arraycopy(combiningChars, 0, newArr, 0, combiningChars.length);
                combiningChars = newArr;
            }
            combiningChars[combiningCount++] = cp;
        }
    }

    public final LogicalCell[] visualCells;
    public final int[] logicalToVisual;
    public final int[] visualToLogical;

    public BidiLayout(LogicalCell[] visualCells, int[] logicalToVisual, int[] visualToLogical) {
        this.visualCells = visualCells;
        this.logicalToVisual = logicalToVisual;
        this.visualToLogical = visualToLogical;
    }

    /**
     * Builds or retrieves a cached visual layout for a terminal row.
     * Maps logical terminal grid columns to visual characters and manages RTL/LTR reordering and shaping.
     * Uses a fast-path caching strategy to update cursor and selection states in-place,
     * avoiding Bidi calculations and array allocations on frame refreshes.
     */
    public static BidiLayout build(TerminalRow rowObject, int columns, int cursorCol, boolean cursorVisible, int selx1, int selx2) {
        // 1. Fast-path: Check cached layout (zero allocations, O(N) update of dynamic attributes)
        if (rowObject.mCachedBidiLayout instanceof BidiLayout) {
            BidiLayout cached = (BidiLayout) rowObject.mCachedBidiLayout;
            if (cached.visualCells.length == columns) {
                for (int i = 0; i < columns; i++) {
                    LogicalCell cell = cached.visualCells[i];
                    int lCol = cached.visualToLogical[i];
                    cell.insideCursor = (lCol == cursorCol && cursorVisible);
                    cell.insideSelection = (lCol >= selx1 && lCol <= selx2);
                }
                return cached;
            }
        }

        // 2. Cache miss: Build a new layout
        LogicalCell[] logicalCells = new LogicalCell[columns];
        for (int i = 0; i < columns; i++) {
            logicalCells[i] = new LogicalCell();
            logicalCells[i].codePoint = ' ';
            logicalCells[i].displayWidth = 1;
            logicalCells[i].style = rowObject.getStyle(i);
            logicalCells[i].insideCursor = (i == cursorCol && cursorVisible);
            logicalCells[i].insideSelection = (i >= selx1 && i <= selx2);
            logicalCells[i].originalColumn = i;
        }

        char[] line = rowObject.mText;
        int charsUsedInLine = rowObject.getSpaceUsed();
        int currentCharIndex = 0;

        for (int column = 0; column < columns; ) {
            if (currentCharIndex >= charsUsedInLine) {
                break;
            }
            char c = line[currentCharIndex];
            boolean isHigh = Character.isHighSurrogate(c);
            int charsCount = isHigh ? 2 : 1;

            int codePoint = isHigh ? Character.toCodePoint(c, line[currentCharIndex + 1]) : c;
            int w = WcWidth.width(codePoint);
            if (w <= 0) w = 1;

            if (column < columns) {
                LogicalCell cell = logicalCells[column];
                cell.codePoint = codePoint;
                cell.displayWidth = w;

                if (w == 2 && column + 1 < columns) {
                    logicalCells[column + 1].codePoint = 0;
                    logicalCells[column + 1].displayWidth = 0;
                    logicalCells[column + 1].insideCursor = (column + 1 == cursorCol && cursorVisible);
                }
            }

            currentCharIndex += charsCount;

            while (currentCharIndex < charsUsedInLine) {
                char nextChar = line[currentCharIndex];
                boolean nextIsHigh = Character.isHighSurrogate(nextChar);
                int nextCp = nextIsHigh ? Character.toCodePoint(nextChar, line[currentCharIndex + 1]) : nextChar;
                if (WcWidth.width(nextCp) <= 0) {
                    if (column < columns) {
                        logicalCells[column].addCombining(nextCp);
                    }
                    currentCharIndex += nextIsHigh ? 2 : 1;
                } else {
                    break;
                }
            }
            column += w;
        }

        // Shape Arabic/Persian
        ArabicShaper.shape(logicalCells);

        // Convert to Char array for Bidi
        char[] bidiChars = new char[columns];
        for (int i = 0; i < columns; i++) {
            int cp = logicalCells[i].codePoint;
            if (cp == 0) {
                bidiChars[i] = ' ';
            } else if (Character.isSupplementaryCodePoint(cp)) {
                bidiChars[i] = ' '; // standard LTR placeholder
            } else {
                bidiChars[i] = (char) cp;
            }
        }

        Bidi bidi = new Bidi(bidiChars, 0, null, 0, columns, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        int[] logicalToVisual = new int[columns];
        int[] visualToLogical = new int[columns];

        BidiLayout newLayout;
        if (bidi.isLeftToRight()) {
            for (int i = 0; i < columns; i++) {
                logicalToVisual[i] = i;
                visualToLogical[i] = i;
            }
            newLayout = new BidiLayout(logicalCells, logicalToVisual, visualToLogical);
        } else {
            byte[] levels = new byte[columns];
            Integer[] visualToLogicalObj = new Integer[columns];
            for (int i = 0; i < columns; i++) {
                levels[i] = (byte) bidi.getLevelAt(i);
                visualToLogicalObj[i] = i;
            }
            Bidi.reorderVisually(levels, 0, visualToLogicalObj, 0, columns);

            LogicalCell[] visualCells = new LogicalCell[columns];
            for (int i = 0; i < columns; i++) {
                visualToLogical[i] = visualToLogicalObj[i];
                logicalToVisual[visualToLogical[i]] = i;
                visualCells[i] = logicalCells[visualToLogical[i]];
            }
            newLayout = new BidiLayout(visualCells, logicalToVisual, visualToLogical);
        }

        // Cache the newly created layout
        rowObject.mCachedBidiLayout = newLayout;
        return newLayout;
    }
}
