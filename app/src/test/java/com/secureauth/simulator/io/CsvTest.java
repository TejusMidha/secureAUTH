package com.secureauth.simulator.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class CsvTest {

    private static List<String> row(String... fields) {
        return Arrays.asList(fields);
    }

    // ---- escaping ---------------------------------------------------------------------------

    @Test
    public void plainTextIsLeftAlone() {
        assertEquals("PASSWORD_OK", Csv.escape("PASSWORD_OK"));
        assertEquals("", Csv.escape(""));
        assertEquals("", Csv.escape(null));
        assertEquals("a b", Csv.escape("a b"));
    }

    @Test
    public void commaQuoteAndLineBreaksForceQuotes() {
        assertEquals("\"a,b\"", Csv.escape("a,b"));
        assertEquals("\"line1\nline2\"", Csv.escape("line1\nline2"));
        assertEquals("\"line1\rline2\"", Csv.escape("line1\rline2"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""));
    }

    @Test
    public void aRowIsJoinedWithCommasAndEndsWithCrlf() {
        assertEquals("1,a,\"b,c\"\r\n", Csv.formatRow(row("1", "a", "b,c")));
        assertEquals("x,,y\r\n", Csv.formatRow(row("x", null, "y")));
    }

    @Test
    public void formulaStartersGetAnApostropheButNumbersAndWordsDoNot() {
        assertEquals("'=SUM(A1)", Csv.neutralizeFormula("=SUM(A1)"));
        assertEquals("'+1", Csv.neutralizeFormula("+1"));
        assertEquals("'-2", Csv.neutralizeFormula("-2"));
        assertEquals("'@cmd", Csv.neutralizeFormula("@cmd"));
        assertEquals("OK", Csv.neutralizeFormula("OK"));
        assertEquals("", Csv.neutralizeFormula(""));
        assertNull(Csv.neutralizeFormula(null));
    }

    // ---- parsing ----------------------------------------------------------------------------

    @Test
    public void parsesSimpleRecords() {
        List<List<String>> records = Csv.parse("a,b,c\n1,2,3\n");
        assertEquals(2, records.size());
        assertEquals(row("a", "b", "c"), records.get(0));
        assertEquals(row("1", "2", "3"), records.get(1));
    }

    @Test
    public void understandsEveryKindOfLineEnding() {
        assertEquals(3, Csv.parse("a\r\nb\r\nc").size());
        assertEquals(3, Csv.parse("a\nb\nc\n").size());
        assertEquals(3, Csv.parse("a\rb\rc").size());
    }

    @Test
    public void quotedFieldsKeepCommasQuotesAndLineBreaks() {
        List<List<String>> records = Csv.parse("1,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\"\n");
        assertEquals(1, records.size());
        assertEquals(row("1", "a,b", "say \"hi\"", "two\nlines"), records.get(0));
    }

    @Test
    public void emptyFieldsAreKept() {
        assertEquals(row("a", "", "c", ""), Csv.parse("a,,c,").get(0));
        assertEquals(row("", "", ""), Csv.parse(",,").get(0));
    }

    @Test
    public void blankLinesAreSkippedAndTheLastLineNeedsNoNewline() {
        List<List<String>> records = Csv.parse("a,b\n\n\nc,d");
        assertEquals(2, records.size());
        assertEquals(row("c", "d"), records.get(1));
    }

    @Test
    public void aLeadingByteOrderMarkIsIgnored() {
        assertEquals(row("ts", "event"), Csv.parse("﻿ts,event\n").get(0));
    }

    @Test
    public void emptyInputGivesNoRecords() {
        assertTrue(Csv.parse("").isEmpty());
        assertTrue(Csv.parse(null).isEmpty());
        assertTrue(Csv.parse("\n\n").isEmpty());
    }

    @Test
    public void aQuoteThatIsNeverClosedIsAnError() {
        try {
            Csv.parse("a,\"never closed\nb,c\n");
            fail("expected an error");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    @Test
    public void aQuoteInTheMiddleOfAnUnquotedFieldIsJustText() {
        assertEquals(row("5\"", "x"), Csv.parse("5\",x").get(0));
    }

    // ---- round trip -------------------------------------------------------------------------

    @Test
    public void whateverIsWrittenCanBeReadBackExactly() {
        Random random = new Random(7);
        String alphabet = "ab ,\"\n\r;:'=-+@0";
        for (int round = 0; round < 300; round++) {
            List<List<String>> original = new ArrayList<>();
            int rows = 1 + random.nextInt(4);
            for (int r = 0; r < rows; r++) {
                List<String> fields = new ArrayList<>();
                int count = 2 + random.nextInt(4); // 2+ fields so a row can never look like a blank line
                for (int f = 0; f < count; f++) {
                    StringBuilder sb = new StringBuilder();
                    int length = random.nextInt(8);
                    for (int i = 0; i < length; i++) {
                        sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                    }
                    fields.add(sb.toString());
                }
                original.add(fields);
            }

            StringBuilder csv = new StringBuilder();
            for (List<String> fields : original) {
                csv.append(Csv.formatRow(fields));
            }
            assertEquals("round " + round, original, Csv.parse(csv.toString()));
        }
    }

    @Test
    public void singleColumnFilesWorkToo() {
        assertEquals(Collections.singletonList(row("x")), Csv.parse("x"));
    }
}
