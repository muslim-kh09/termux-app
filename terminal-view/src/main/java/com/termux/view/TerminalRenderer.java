package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            
            // Build / retrieve cached visual layout
            BidiLayout layout = BidiLayout.build(lineObject, columns, cursorCol, row == cursorRow && cursorVisible, selx1, selx2);
            BidiLayout.LogicalCell[] visualCells = layout.visualCells;

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int runStartBufferOffset = 0;
            boolean lastRunFontWidthMismatch = false;

            char[] drawBuffer = new char[columns * 4];
            int drawBufferUsed = 0;

            for (int vCol = 0; vCol < columns; ) {
                BidiLayout.LogicalCell cell = visualCells[vCol];
                if (cell.displayWidth == 0 && cell.codePoint == 0) {
                    vCol++;
                    continue;
                }

                final boolean insideCursor = cell.insideCursor;
                final boolean insideSelection = cell.insideSelection;
                final long style = cell.style;
                final int codePoint = cell.codePoint;
                final int codePointWcWidth = cell.displayWidth;

                float measuredCodePointWidth = 0f;
                char[] tempMeasure = new char[4];
                int tempLen = 0;
                if (codePoint != 0) {
                    tempLen = Character.toChars(codePoint, tempMeasure, 0);
                    if (cell.combiningChars != null) {
                        for (int i = 0; i < cell.combiningCount; i++) {
                            tempLen += Character.toChars(cell.combiningChars[i], tempMeasure, tempLen);
                        }
                    }
                    measuredCodePointWidth = mTextPaint.measureText(tempMeasure, 0, tempLen);
                }

                final boolean fontWidthMismatch = codePointWcWidth > 0 && Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (vCol == 0) {
                        // Skip first column
                    } else {
                        final int columnWidthSinceLastRun = vCol - lastRunStartColumn;
                        final int charsSinceLastRun = drawBufferUsed - runStartBufferOffset;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }

                        float measuredWidthForRun = 0f;
                        if (lastRunFontWidthMismatch) {
                            for (int c = lastRunStartColumn; c < vCol; c++) {
                                BidiLayout.LogicalCell rc = visualCells[c];
                                if (rc.displayWidth == 0 && rc.codePoint == 0) continue;
                                if (rc.codePoint != 0) {
                                    char[] t = new char[4];
                                    int tl = Character.toChars(rc.codePoint, t, 0);
                                    if (rc.combiningChars != null) {
                                        for (int i = 0; i < rc.combiningCount; i++) {
                                            tl += Character.toChars(rc.combiningChars[i], t, tl);
                                        }
                                    }
                                    measuredWidthForRun += mTextPaint.measureText(t, 0, tl);
                                } else {
                                    measuredWidthForRun += mFontWidth;
                                }
                            }
                        } else {
                            measuredWidthForRun = columnWidthSinceLastRun * mFontWidth;
                        }

                        drawTextRun(canvas, drawBuffer, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            runStartBufferOffset, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = vCol;
                    runStartBufferOffset = drawBufferUsed;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }

                if (codePoint != 0) {
                    drawBufferUsed += Character.toChars(codePoint, drawBuffer, drawBufferUsed);
                    if (cell.combiningChars != null) {
                        for (int i = 0; i < cell.combiningCount; i++) {
                            drawBufferUsed += Character.toChars(cell.combiningChars[i], drawBuffer, drawBufferUsed);
                        }
                    }
                } else if (codePointWcWidth > 0) {
                    drawBuffer[drawBufferUsed++] = ' ';
                }
                vCol += codePointWcWidth;
            }

            if (columns > lastRunStartColumn) {
                final int columnWidthSinceLastRun = columns - lastRunStartColumn;
                final int charsSinceLastRun = drawBufferUsed - runStartBufferOffset;
                int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                boolean invertCursorTextColor = false;
                if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                    invertCursorTextColor = true;
                }

                float measuredWidthForRun = 0f;
                if (lastRunFontWidthMismatch) {
                    for (int c = lastRunStartColumn; c < columns; c++) {
                        BidiLayout.LogicalCell rc = visualCells[c];
                        if (rc.displayWidth == 0 && rc.codePoint == 0) continue;
                        if (rc.codePoint != 0) {
                            char[] t = new char[4];
                            int tl = Character.toChars(rc.codePoint, t, 0);
                            if (rc.combiningChars != null) {
                                for (int i = 0; i < rc.combiningCount; i++) {
                                    tl += Character.toChars(rc.combiningChars[i], t, tl);
                                }
                            }
                            measuredWidthForRun += mTextPaint.measureText(t, 0, tl);
                        } else {
                            measuredWidthForRun += mFontWidth;
                        }
                    }
                } else {
                    measuredWidthForRun = columnWidthSinceLastRun * mFontWidth;
                }

                drawTextRun(canvas, drawBuffer, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                    runStartBufferOffset, charsSinceLastRun, measuredWidthForRun,
                    cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
            }
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }

    public int translateVisualToLogicalColumn(TerminalEmulator mEmulator, int visualCol, int row) {
        if (visualCol < 0) return 0;
        if (visualCol >= mEmulator.mColumns) return mEmulator.mColumns - 1;

        TerminalBuffer screen = mEmulator.getScreen();
        int internalRow = screen.externalToInternalRow(row);
        if (internalRow < 0 || internalRow >= screen.getActiveRows()) {
            return visualCol;
        }
        TerminalRow rowObject = screen.allocateFullLineIfNecessary(internalRow);
        if (rowObject == null) {
            return visualCol;
        }
        BidiLayout layout = BidiLayout.build(rowObject, mEmulator.mColumns, -1, false, -1, -1);
        return layout.visualToLogical[visualCol];
    }

    public int translateLogicalToVisualColumn(TerminalEmulator mEmulator, int logicalCol, int row) {
        if (logicalCol < 0) return 0;
        if (logicalCol >= mEmulator.mColumns) return mEmulator.mColumns - 1;

        TerminalBuffer screen = mEmulator.getScreen();
        int internalRow = screen.externalToInternalRow(row);
        if (internalRow < 0 || internalRow >= screen.getActiveRows()) {
            return logicalCol;
        }
        TerminalRow rowObject = screen.allocateFullLineIfNecessary(internalRow);
        if (rowObject == null) {
            return logicalCol;
        }
        BidiLayout layout = BidiLayout.build(rowObject, mEmulator.mColumns, -1, false, -1, -1);
        return layout.logicalToVisual[logicalCol];
    }
}
