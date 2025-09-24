package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.difft.android.chat.R;


public class EmojiImageView extends AppCompatImageView {

  private final boolean forceJumboEmoji;

  public EmojiImageView(Context context) {
    this(context, null);
  }

  public EmojiImageView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.EmojiImageView, 0, 0);
    forceJumboEmoji = a.getBoolean(R.styleable.EmojiImageView_forceJumbo, false);
    a.recycle();
  }

  public void setImageEmoji(CharSequence emoji) {
    if (isInEditMode()) {
      setImageResource(R.drawable.ic_emoji);
    } else {
      setImageResource(R.drawable.ic_emoji);
//      setImageDrawable(EmojiProvider.getEmojiDrawable(getContext(), emoji, forceJumboEmoji));
    }
  }
}
