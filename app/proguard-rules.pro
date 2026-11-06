# R8 is enabled for release (minify + shrink resources).
# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Tab model is persisted manually via JSON (no reflection), nothing else to keep.
