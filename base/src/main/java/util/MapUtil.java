package util;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.function.Function;

public final class MapUtil {

  private MapUtil() {}

  @NonNull
  public static <K, V> V getOrDefault(@NonNull Map<K, V> map, @NonNull K key, @NonNull V defaultValue) {
    //noinspection ConstantConditions
    return map.getOrDefault(key, defaultValue);
  }

  @NonNull
  public static <K, V, M> M mapOrDefault(@NonNull Map<K, V> map, @NonNull K key, @NonNull Function<V, M> mapper, @NonNull M defaultValue) {
    V v = map.get(key);
    return v == null ? defaultValue : mapper.apply(v);
  }
}
