package com.example.reviewer.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffParserTest {

    @Test
    void emptyOrNullDiffYieldsNothing() {
        assertTrue(DiffParser.parse(null).isEmpty());
        assertTrue(DiffParser.parse("").isEmpty());
        assertTrue(DiffParser.parse("   ").isEmpty());
    }

    @Test
    void tracksAddedLinesInNewFileCoordinates() {
        // Hunk starts at new line 10. Context (10), added (11,12), context (13).
        String diff = """
                diff --git a/src/Foo.java b/src/Foo.java
                index 1111111..2222222 100644
                --- a/src/Foo.java
                +++ b/src/Foo.java
                @@ -10,2 +10,4 @@ class Foo {
                 unchanged line at 10
                +added line at 11
                +added line at 12
                 unchanged line at 13
                """;
        Map<String, FileDiff> result = DiffParser.parse(diff);
        FileDiff fd = result.get("src/Foo.java");
        assertEquals(List.of(11, 12), List.copyOf(fd.changedLines()));
    }

    @Test
    void removedLinesDoNotAdvanceNewCounter() {
        // new side: 5 (context), 6 (added). The removed line sits only in the old file.
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -5,3 +5,2 @@
                 context at 5
                -removed (old only)
                +added at 6
                """;
        FileDiff fd = DiffParser.parse(diff).get("A.java");
        assertEquals(List.of(6), List.copyOf(fd.changedLines()));
    }

    @Test
    void handlesMultipleHunksAndMultipleFiles() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,1 +1,2 @@
                 a
                +added at 2
                @@ -10,1 +11,2 @@
                 ctx at 11
                +added at 12
                diff --git a/B.java b/B.java
                --- a/B.java
                +++ b/B.java
                @@ -1 +1 @@
                +only line
                """;
        Map<String, FileDiff> result = DiffParser.parse(diff);
        assertEquals(List.of(2, 12), List.copyOf(result.get("A.java").changedLines()));
        assertEquals(List.of(1), List.copyOf(result.get("B.java").changedLines()));
    }

    @Test
    void newFileFromDevNullIsCaptured() {
        String diff = """
                diff --git a/New.java b/New.java
                new file mode 100644
                index 0000000..3333333
                --- /dev/null
                +++ b/New.java
                @@ -0,0 +1,2 @@
                +line one
                +line two
                """;
        FileDiff fd = DiffParser.parse(diff).get("New.java");
        assertEquals(List.of(1, 2), List.copyOf(fd.changedLines()));
    }

    @Test
    void deletedFileIsOmitted() {
        String diff = """
                diff --git a/Gone.java b/Gone.java
                deleted file mode 100644
                --- a/Gone.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -line one
                -line two
                """;
        assertTrue(DiffParser.parse(diff).isEmpty());
    }

    @Test
    void ignoresNoNewlineMarker() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1 +1 @@
                +last line
                \\ No newline at end of file
                """;
        FileDiff fd = DiffParser.parse(diff).get("A.java");
        assertEquals(List.of(1), List.copyOf(fd.changedLines()));
    }

    @Test
    void describeRangesCollapsesRuns() {
        FileDiff fd = new FileDiff("X.java", new java.util.TreeSet<>(List.of(12, 13, 14, 40, 55, 56)));
        assertEquals("12-14, 40, 55-56", fd.describeRanges());
    }

    @Test
    void touchesRespectsSlackWindow() {
        FileDiff fd = new FileDiff("X.java", new java.util.TreeSet<>(List.of(20)));
        assertTrue(fd.touches(20, 2));
        assertTrue(fd.touches(22, 2));
        assertTrue(fd.touches(18, 2));
        assertFalse(fd.touches(23, 2));
        assertFalse(fd.touches(17, 2));
    }
}
