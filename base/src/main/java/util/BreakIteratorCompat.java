package util;

import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Iterator;

public abstract class BreakIteratorCompat implements Iterable<CharSequence> {
  public static final int          DONE = -1;
  private             CharSequence charSequence;

  public abstract int first();

  public abstract int next();

  public void setText(CharSequence charSequence) {
    this.charSequence = charSequence;
  }

  public static BreakIteratorCompat getInstance() {
    return new AndroidIcuBreakIterator();
  }

  public int countBreaks() {
    int breakCount = 0;

    first();

    while (next() != DONE) {
      breakCount++;
    }

    return breakCount;
  }

  @Override
  public @NonNull Iterator<CharSequence> iterator() {
    return new Iterator<CharSequence>() {

      int index1 = BreakIteratorCompat.this.first();
      int index2 = BreakIteratorCompat.this.next();

      @Override
      public boolean hasNext() {
        return index2 != DONE;
      }

      @Override
      public CharSequence next() {
        CharSequence c = index2 != DONE ? charSequence.subSequence(index1, index2) : "";

        index1 = index2;
        index2 = BreakIteratorCompat.this.next();

        return c;
      }
    };
  }

  /**
   * Take {@param atMost} graphemes from the start of string.
   */
  public final CharSequence take(int atMost) {
    if (atMost <= 0) return "";

    StringBuilder stringBuilder = new StringBuilder(charSequence.length());
    int           count         = 0;

    for (CharSequence grapheme : this) {
      stringBuilder.append(grapheme);

      count++;

      if (count >= atMost) break;
    }

    return stringBuilder.toString();
  }

  /**
   * The BreakIteratorCompat implementation, delegating to `android.icu.text.BreakIterator`.
   * Handles grapheme clusters correctly. Sole implementation now (minSdk=26 always
   * satisfies its API-24 requirement); do not re-add an SDK_INT/@RequiresApi guard.
   */
  private static class AndroidIcuBreakIterator extends BreakIteratorCompat {
    private final android.icu.text.BreakIterator breakIterator = android.icu.text.BreakIterator.getCharacterInstance();

    @Override
    public int first() {
      return breakIterator.first();
    }

    @Override
    public int next() {
      return breakIterator.next();
    }

    @Override
    public void setText(CharSequence charSequence) {
      super.setText(charSequence);
      if (Build.VERSION.SDK_INT >= 29) {
        breakIterator.setText(charSequence);
      } else {
        breakIterator.setText(charSequence.toString());
      }
    }
  }
}
