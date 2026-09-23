package dev.codexops.client;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="records")
public class Record {
 @PrimaryKey @NonNull public String id;
 @NonNull public String value;
 public Record(@NonNull String id, @NonNull String value) { this.id=id; this.value=value; }
}
