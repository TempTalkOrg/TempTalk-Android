package com.difft.android.base.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

import com.difft.android.base.utils.DimenUtils;

public class SpanBuilder {

    private static final CharSequence SPACE = " ";

    public static SpanBuilder create() {
        return new SpanBuilder();
    }

    public static SpanBuilder create(CharSequence chars) {
        return new SpanBuilder(chars);
    }

    private SpannableStringBuilder ssBuilder;
    private int begIndex;
    private int endIndex;

    private SpanBuilder() {
        ssBuilder = new SpannableStringBuilder();
    }

    private SpanBuilder(CharSequence chars) {
        ssBuilder = new SpannableStringBuilder(chars);
        all();
    }

    public Spannable build() {
        return ssBuilder;
    }

    public SpanBuilder padding(int size) {
        return padding(size, false);
    }

    public SpanBuilder padding(final int size, final boolean dip) {
        return append(SPACE).span(new ReplacementSpan() {
            @Override
            public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
                return dip ? DimenUtils.getDp(size) : size;
            }

            @Override
            public void draw(@NonNull Canvas canvas, CharSequence text,
                    int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
                // Empty.
            }
        });
    }

    public SpanBuilder appendSpanPlaceHolder() {
        return append(SPACE);
    }

    public SpanBuilder append(CharSequence tb) {
        tb = checkNullTb(tb);
        int length = ssBuilder.length();
        return replace(length, length, tb, 0, tb.length());
    }

    public SpanBuilder append(CharSequence tb, int start, int end) {
        tb = checkNullTb(tb);
        int length = ssBuilder.length();
        return replace(length, length, tb, start, end);
    }

    public SpanBuilder insert(int where, CharSequence tb) {
        tb = checkNullTb(tb);
        return replace(where, where, tb, 0, tb.length());
    }

    public SpanBuilder insert(int where, CharSequence tb, int start, int end) {
        tb = checkNullTb(tb);
        return replace(where, where, tb, start, end);
    }

    public SpanBuilder replace(int start, int end, CharSequence tb) {
        tb = checkNullTb(tb);
        return replace(start, end, tb, 0, tb.length());
    }

    public SpanBuilder replace(int start, int end, CharSequence tb, int tbstart, int tbend) {
        tb = checkNullTb(tb);
        int length = ssBuilder.length();
        int tbLength = tb.length();

        int tStart = transformIndex(length, start);
        int tEnd = transformIndex(length, end);
        int tTbStart = transformIndex(tbLength, tbstart);
        int tTbEnd = transformIndex(tbLength, tbend);

        ssBuilder.replace(tStart, tEnd, tb, tbstart, tbend);

        begIndex = tStart;
        endIndex = begIndex - tTbStart + tTbEnd;
        return this;
    }

    public SpanBuilder slice(int start, int end) {
        int length = ssBuilder.length();
        begIndex = transformIndex(length, start);
        endIndex = transformIndex(length, end);
        return this;
    }

    public SpanBuilder all() {
        return slice(0, Integer.MAX_VALUE);
    }

    private CharSequence checkNullTb(CharSequence tb) {
        return null == tb ? "" : tb;
    }

    private int transformIndex(int length, int index) {
        if (length < index) {
            return length;
        } else if (0 <= index) {
            return index;
        } else if (-length <= index) {
            return index + length;
        } else {
            return 0;
        }
    }

    public SpanBuilder span(Object span) {
        return span(span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public SpanBuilder span(Object span, int flags) {
        if (begIndex < endIndex) {
            ssBuilder.setSpan(span, begIndex, endIndex, flags);
        }
        return this;
    }

    public SpanBuilder typeface(String family, Typeface typeface) {
        return typeface(family, typeface, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public SpanBuilder typeface(String family, Typeface typeface, int flags) {
        if (begIndex < endIndex) {
            ssBuilder.setSpan(new CustomTypefaceSpan(family, typeface), begIndex, endIndex, flags);
        }
        return this;
    }

    public SpanBuilder textColor(int color) {
        return textColor(color, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public SpanBuilder textColor(int color, int flags) {
        if (begIndex < endIndex) {
            ssBuilder.setSpan(new ForegroundColorSpan(color), begIndex, endIndex, flags);
        }
        return this;
    }

    public SpanBuilder textSize(int size) {
        return textSize(size, false);
    }

    public SpanBuilder textSize(int size, boolean dip) {
        return textSize(size, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, dip);
    }

    public SpanBuilder textSize(int size, int flags) {
        return textSize(size, flags, false);
    }

    public SpanBuilder textSize(int size, int flags, boolean dip) {
        if (begIndex < endIndex) {
            ssBuilder.setSpan(new AbsoluteSizeSpan(size, dip), begIndex, endIndex, flags);
        }
        return this;
    }

    public SpanBuilder bgColor(int color) {
        return bgColor(color, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public SpanBuilder bgColor(int color, int flags) {
        if (begIndex < endIndex) {
            ssBuilder.setSpan(new BackgroundColorSpan(color), begIndex, endIndex, flags);
        }
        return this;
    }

    public SpanBuilder fakeBold() {
        return fakeBold(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public SpanBuilder fakeBold(int flags) {
        if (begIndex < endIndex) {
            ssBuilder.setSpan(new CharacterStyle() {
                @Override
                public void updateDrawState(TextPaint tp) {
                    tp.setFakeBoldText(true);
                }
            }, begIndex, endIndex, flags);
        }
        return this;
    }
}
