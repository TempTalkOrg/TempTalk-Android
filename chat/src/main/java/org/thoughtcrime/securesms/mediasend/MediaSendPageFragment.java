package org.thoughtcrime.securesms.mediasend;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A page that sits in the {@link}.
 */
public interface MediaSendPageFragment {

  @NonNull Uri getUri();

  void setUri(@NonNull Uri uri);

  @Nullable Object saveState();

  void restoreState(@NonNull Object state);

  void notifyHidden();
}
