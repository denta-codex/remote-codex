package dev.codexops.client;
import androidx.room.*;
@Database(entities={Record.class}, version=1, exportSchema=false)
public abstract class LocalDatabase extends RoomDatabase { public abstract Records records(); }
