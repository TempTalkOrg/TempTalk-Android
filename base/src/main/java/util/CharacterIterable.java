package util;

import androidx.annotation.NonNull;

import java.util.Iterator;

/**
 * Iterates over a string treating a surrogate pair and a grapheme cluster a single character.
 */
public final class CharacterIterable implements Iterable<String> {

  private final String string;

  public CharacterIterable(@NonNull String string) {
    this.string = string;
  }

  @Override
  public @NonNull Iterator<String> iterator() {
    return new CharacterIterator();
  }

  private class CharacterIterator implements Iterator<String> {
    private static final int UNINITIALIZED = -2;

    private final BreakIteratorCompat breakIterator;

    private int lastIndex = UNINITIALIZED;

    CharacterIterator() {
      this.breakIterator = new AndroidIcuBreakIterator(string);
    }

    @Override
    public boolean hasNext() {
      if (lastIndex == UNINITIALIZED) {
        lastIndex = breakIterator.first();
      }
      return !breakIterator.isDone(lastIndex);
    }

    @Override
    public String next() {
      int firstIndex = lastIndex;
      lastIndex = breakIterator.next();
      return string.substring(firstIndex, lastIndex);
    }
  }

  private interface BreakIteratorCompat {
    int first();

    int next();

    boolean isDone(int index);
  }

  /**
   * The BreakIteratorCompat implementation, delegating to `android.icu.text.BreakIterator`.
   * Handles grapheme clusters correctly. Sole implementation now (minSdk=26 always
   * satisfies its API-24 requirement); do not re-add an SDK_INT/@RequiresApi guard.
   */
  private static class AndroidIcuBreakIterator implements BreakIteratorCompat {
    private final android.icu.text.BreakIterator breakIterator = android.icu.text.BreakIterator.getCharacterInstance();

    public AndroidIcuBreakIterator(@NonNull String string) {
      breakIterator.setText(string);
    }

    @Override
    public int first() {
      return breakIterator.first();
    }

    @Override
    public int next() {
      return breakIterator.next();
    }

    @Override
    public boolean isDone(int index) {
      return index == android.icu.text.BreakIterator.DONE;
    }
  }
}
