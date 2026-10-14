package com.secureauth.simulator.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Writes through a real ContentResolver, the way the system file picker's Uri is used. */
@RunWith(AndroidJUnit4.class)
public class FileExporterDeviceTest {

    private Context context;
    private File target;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        target = new File(context.getCacheDir(), "export_test.txt");
        assertTrue(target.exists() || target.createNewFile());
    }

    @After
    public void tearDown() {
        target.delete();
    }

    private String contents() throws IOException {
        return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void writesTheTextToTheChosenFile() throws IOException {
        assertTrue(FileExporter.writeText(context.getContentResolver(), Uri.fromFile(target), "hello\nworld\n"));
        assertEquals("hello\nworld\n", contents());
    }

    @Test
    public void overwritingWithShorterTextLeavesNoOldTail() throws IOException {
        Uri uri = Uri.fromFile(target);
        assertTrue(FileExporter.writeText(context.getContentResolver(), uri, "a much longer first version"));
        assertTrue(FileExporter.writeText(context.getContentResolver(), uri, "short"));
        assertEquals("short", contents());
    }

    @Test
    public void keepsNonAsciiTextIntact() throws IOException {
        assertTrue(FileExporter.writeText(context.getContentResolver(), Uri.fromFile(target), "Ünïcödé ✓"));
        assertEquals("Ünïcödé ✓", contents());
    }

    @Test
    public void anUnwritableLocationReportsFailure() {
        Uri nowhere = Uri.parse("content://com.secureauth.simulator.does.not.exist/file.txt");
        assertFalse(FileExporter.writeText(context.getContentResolver(), nowhere, "x"));
    }
}
