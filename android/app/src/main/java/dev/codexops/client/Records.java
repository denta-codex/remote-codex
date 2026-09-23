package dev.codexops.client;
import androidx.room.*;
@Dao public interface Records {
 @Query("SELECT value FROM records WHERE id = :id") String get(String id);
 @Insert(onConflict=OnConflictStrategy.REPLACE) void put(Record record);
 @Query("DELETE FROM records WHERE id = :id") void remove(String id);
}
