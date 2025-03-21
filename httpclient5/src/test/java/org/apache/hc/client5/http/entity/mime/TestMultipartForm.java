/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.client5.http.entity.mime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestMultipartForm {

    private File tmpfile;

    @AfterEach
    void cleanup() {
        if (tmpfile != null) {
            tmpfile.delete();
        }
    }

    @Test
    void testMultipartFormStringParts() throws Exception {
        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new StringBody("this stuff", ContentType.DEFAULT_TEXT)).build();
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new StringBody("that stuff", ContentType.create(
                        ContentType.TEXT_PLAIN.getMimeType(), StandardCharsets.ISO_8859_1))).build();
        final FormBodyPart p3 = FormBodyPartBuilder.create(
                "field3",
                new StringBody("all kind of stuff", ContentType.DEFAULT_TEXT)).build();
        final HttpStrictMultipart multipart = new HttpStrictMultipart(null, "foo",
                Arrays.asList(p1, p2, p3));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field1\"\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "this stuff\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field2\"\r\n" +
            "Content-Type: text/plain; charset=ISO-8859-1\r\n" +
            "\r\n" +
            "that stuff\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field3\"\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "all kind of stuff\r\n" +
            "--foo--\r\n";
        final String s = out.toString("US-ASCII");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(s.length(), multipart.getTotalLength());
    }

    @Test
    void testMultipartFormCustomContentType() throws Exception {
        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new StringBody("this stuff", ContentType.DEFAULT_TEXT)).build();
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new StringBody("that stuff", ContentType.parse("stuff/plain; param=value"))).build();
        final HttpStrictMultipart multipart = new HttpStrictMultipart(null, "foo",
                Arrays.asList(p1, p2));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
                "--foo\r\n" +
                        "Content-Disposition: form-data; name=\"field1\"\r\n" +
                        "Content-Type: text/plain; charset=UTF-8\r\n" +
                        "\r\n" +
                        "this stuff\r\n" +
                        "--foo\r\n" +
                        "Content-Disposition: form-data; name=\"field2\"\r\n" +
                        "Content-Type: stuff/plain; param=value\r\n" +
                        "\r\n" +
                        "that stuff\r\n" +
                        "--foo--\r\n";
        final String s = out.toString("US-ASCII");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(s.length(), multipart.getTotalLength());
    }

    @Test
    void testMultipartFormBinaryParts() throws Exception {
        tmpfile = File.createTempFile("tmp", ".bin");
        try (Writer writer = new FileWriter(tmpfile)) {
            writer.append("some random whatever");
        }

        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new FileBody(tmpfile)).build();
        @SuppressWarnings("resource")
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new InputStreamBody(new FileInputStream(tmpfile), "file.tmp")).build();
        final HttpStrictMultipart multipart = new HttpStrictMultipart(null, "foo",
                Arrays.asList(p1, p2));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field1\"; " +
                "filename=\"" + tmpfile.getName() + "\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field2\"; " +
                "filename=\"file.tmp\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo--\r\n";
        final String s = out.toString("US-ASCII");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(-1, multipart.getTotalLength());
    }

    @Test
    void testMultipartFormStrict() throws Exception {
        tmpfile = File.createTempFile("tmp", ".bin");
        try (Writer writer = new FileWriter(tmpfile)) {
            writer.append("some random whatever");
        }

        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new FileBody(tmpfile)).build();
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new FileBody(tmpfile, ContentType.create("text/plain", "ANSI_X3.4-1968"), "test-file")).build();
        @SuppressWarnings("resource")
        final FormBodyPart p3 = FormBodyPartBuilder.create(
                "field3",
                new InputStreamBody(new FileInputStream(tmpfile), "file.tmp")).build();
        final HttpStrictMultipart multipart = new HttpStrictMultipart(null, "foo",
                Arrays.asList(p1, p2, p3));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field1\"; " +
                "filename=\"" + tmpfile.getName() + "\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field2\"; " +
                "filename=\"test-file\"\r\n" +
            "Content-Type: text/plain; charset=US-ASCII\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field3\"; " +
                "filename=\"file.tmp\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo--\r\n";
        final String s = out.toString("US-ASCII");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(-1, multipart.getTotalLength());
    }

    @Test
    void testMultipartFormRFC6532() throws Exception {
        tmpfile = File.createTempFile("tmp", ".bin");
        try (Writer writer = new FileWriter(tmpfile)) {
            writer.append("some random whatever");
        }

        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1\u0414",
                new FileBody(tmpfile)).build();
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new FileBody(tmpfile, ContentType.create("text/plain", "ANSI_X3.4-1968"), "test-file")).build();
        @SuppressWarnings("resource")
        final FormBodyPart p3 = FormBodyPartBuilder.create(
                "field3",
                new InputStreamBody(new FileInputStream(tmpfile), "file.tmp")).build();
        final HttpRFC6532Multipart multipart = new HttpRFC6532Multipart(null, "foo",
                Arrays.asList(p1, p2, p3));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field1\u0414\"; " +
                "filename=\"" + tmpfile.getName() + "\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field2\"; " +
                "filename=\"test-file\"\r\n" +
            "Content-Type: text/plain; charset=US-ASCII\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field3\"; " +
                "filename=\"file.tmp\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo--\r\n";
        final String s = out.toString("UTF-8");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(-1, multipart.getTotalLength());
    }

    private static final int SWISS_GERMAN_HELLO [] = {
        0x47, 0x72, 0xFC, 0x65, 0x7A, 0x69, 0x5F, 0x7A, 0xE4, 0x6D, 0xE4
    };

    private static final int RUSSIAN_HELLO [] = {
        0x412, 0x441, 0x435, 0x43C, 0x5F, 0x43F, 0x440, 0x438,
        0x432, 0x435, 0x442
    };

    private static String constructString(final int [] unicodeChars) {
        final StringBuilder buffer = new StringBuilder();
        if (unicodeChars != null) {
            for (final int unicodeChar : unicodeChars) {
                buffer.append((char)unicodeChar);
            }
        }
        return buffer.toString();
    }

    @Test
    void testMultipartFormBrowserCompatibleNonASCIIHeaders() throws Exception {
        final String s1 = constructString(SWISS_GERMAN_HELLO);
        final String s2 = constructString(RUSSIAN_HELLO);

        tmpfile = File.createTempFile("tmp", ".bin");
        try (Writer writer = new FileWriter(tmpfile)) {
            writer.append("some random whatever");
        }

        @SuppressWarnings("resource")
        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new InputStreamBody(new FileInputStream(tmpfile), s1 + ".tmp"), HttpMultipartMode.LEGACY).build();
        @SuppressWarnings("resource")
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new InputStreamBody(new FileInputStream(tmpfile), s2 + ".tmp"), HttpMultipartMode.LEGACY).build();
        final LegacyMultipart multipart = new LegacyMultipart(
                StandardCharsets.UTF_8, "foo",
                Arrays.asList(p1, p2));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field1\"; " +
                "filename=\"" + s1 + ".tmp\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field2\"; " +
                "filename=\"" + s2 + ".tmp\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "some random whatever\r\n" +
            "--foo--\r\n";
        final String s = out.toString("UTF-8");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(-1, multipart.getTotalLength());
    }

    @Test
    void testMultipartFormStringPartsMultiCharsets() throws Exception {
        final String s1 = constructString(SWISS_GERMAN_HELLO);
        final String s2 = constructString(RUSSIAN_HELLO);

        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new StringBody(s1, ContentType.create("text/plain", StandardCharsets.ISO_8859_1))).build();
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new StringBody(s2, ContentType.create("text/plain", Charset.forName("KOI8-R")))).build();
        final HttpStrictMultipart multipart = new HttpStrictMultipart(null, "foo",
                Arrays.asList(p1, p2));

        final ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        multipart.writeTo(out1);
        out1.close();

        final ByteArrayOutputStream out2 = new ByteArrayOutputStream();

        out2.write((
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field1\"\r\n" +
            "Content-Type: text/plain; charset=ISO-8859-1\r\n" +
            "\r\n").getBytes(StandardCharsets.US_ASCII));
        out2.write(s1.getBytes(StandardCharsets.ISO_8859_1));
        out2.write(("\r\n" +
            "--foo\r\n" +
            "Content-Disposition: form-data; name=\"field2\"\r\n" +
            "Content-Type: text/plain; charset=KOI8-R\r\n" +
            "\r\n").getBytes(StandardCharsets.US_ASCII));
        out2.write(s2.getBytes(Charset.forName("KOI8-R")));
        out2.write(("\r\n" +
            "--foo--\r\n").getBytes(StandardCharsets.US_ASCII));
        out2.close();

        final byte[] actual = out1.toByteArray();
        final byte[] expected = out2.toByteArray();

        Assertions.assertEquals(expected.length, actual.length);
        for (int i = 0; i < actual.length; i++) {
            Assertions.assertEquals(expected[i], actual[i]);
        }
        Assertions.assertEquals(expected.length, multipart.getTotalLength());
    }

    @Test
    void testMultipartFormBinaryPartsPreamblEpilogue() throws Exception {
        tmpfile = File.createTempFile("tmp", ".bin");
        try (Writer writer = new FileWriter(tmpfile)) {
            writer.append("some random whatever");
        }

        final FormBodyPart p1 = FormBodyPartBuilder.create(
                "field1",
                new FileBody(tmpfile)).build();
        @SuppressWarnings("resource")
        final FormBodyPart p2 = FormBodyPartBuilder.create(
                "field2",
                new InputStreamBody(new FileInputStream(tmpfile), "file.tmp")).build();
        final HttpStrictMultipart multipart = new HttpStrictMultipart(null, "foo",
                Arrays.asList(p1, p2), "This is the preamble", "This is the epilogue");

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);
        out.close();

        final String expected =
                "This is the preamble\r\n" +
                        "--foo\r\n" +
                        "Content-Disposition: form-data; name=\"field1\"; " +
                        "filename=\"" + tmpfile.getName() + "\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "some random whatever\r\n" +
                        "--foo\r\n" +
                        "Content-Disposition: form-data; name=\"field2\"; " +
                        "filename=\"file.tmp\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "some random whatever\r\n" +
                        "--foo--\r\n" +
                        "This is the epilogue\r\n";
        final String s = out.toString("US-ASCII");
        Assertions.assertEquals(expected, s);
        Assertions.assertEquals(-1, multipart.getTotalLength());
    }
}
