package org.difft.app.database.models;

import androidx.annotation.NonNull;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class QuoteModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBField
    @WCDBIndex
    public long id;

    @WCDBField
    public String author = "";

    @WCDBField
    public String text = "";

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QuoteModel that = (QuoteModel) o;
        return id == that.id && Objects.equals(author, that.author) && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, author, text);
    }

    @NonNull
    @Override
    public String toString() {
        return "QuoteModel{" +
                "databaseId=" + databaseId +
                ", id=" + id +
                ", author='" + author + '\'' +
                ", text='" + text + '\'' +
                '}';
    }
}