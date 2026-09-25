package org.sgrewritten.stargate.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHelperTest {

    @TempDir
    Path dataFolder;

    @Test
    void createsInternalFileOnTheCurrentFileSystem() throws IOException {
        File file = FileHelper.createHiddenFileIfNotExists(dataFolder.toString(), ".internal", "state.txt");
        assertTrue(file.isFile());
        Files.writeString(file.toPath(), "existing state");

        File existing = FileHelper.createHiddenFileIfNotExists(dataFolder.toString(), ".internal", "state.txt");

        assertEquals(file, existing);
        assertEquals("existing state", Files.readString(existing.toPath()));
    }
}
