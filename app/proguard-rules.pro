# Readium, kotlinx.serialization, Room, OkHttp and WorkManager ship their own consumer rules.

# Persisted with kotlinx.serialization (session store, reader preferences): keep names stable
# so stored JSON from earlier builds still decodes.
-keep class de.freal.toleo.tolino.Session { *; }
-keep class de.freal.toleo.tolino.Partner { *; }
-keep class de.freal.toleo.reader.ReaderPrefs { *; }
-keep class de.freal.toleo.tolino.ShopAccessSettings { *; }
-keep class de.freal.toleo.tolino.ShopProxy { *; }
-keep class de.freal.toleo.tolino.ShopLogin { *; }
-keepclassmembers enum de.freal.toleo.** { *; }

# Instantiated by WorkManager via reflection.
-keep class de.freal.toleo.sync.SyncWorker { <init>(...); }

# Readium navigator fragments are created by FragmentFactory class name.
-keep class org.readium.r2.navigator.** extends androidx.fragment.app.Fragment { <init>(...); }

# Readium's optional modules (LCP, PDF adapters, audio) are not included.
-dontwarn org.readium.**
