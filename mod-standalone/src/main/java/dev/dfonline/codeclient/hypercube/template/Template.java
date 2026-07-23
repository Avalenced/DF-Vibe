package dev.dfonline.codeclient.hypercube.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dfonline.codeclient.CodeClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Template {
    public ArrayList<TemplateBlock> blocks;

    /**
     * Encode a pre-built block list ({@code [{"id":"block",...}, {"id":"bracket",...}]}) into the
     * base64(gzip(JSON)) "code" string DiamondFire uses - the inverse of {@link #parse64}. Used by the
     * physical-block salvage path ({@code SalvageLine}) to rebuild a line DF refused to hand over as a
     * template ("Exceeded the code data size limit"). Key ORDER doesn't have to match DF's - the codec
     * parses the JSON order-independently - only the values must be right.
     */
    public static String encode(JsonArray blocks) throws IOException {
        JsonObject root = new JsonObject();
        root.add("blocks", blocks);
        return Base64.getEncoder().encodeToString(gzip(root.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    /**
     * Parse base64+gzip data
     */
    public static Template parse64(String data) {
        try {
            return parse(Base64.getDecoder().decode(data));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse gzip data
     */
    public static Template parse(byte[] data) throws IOException {
        return parse(new String(decompress(data)));
    }

    /**
     * Uncompressed JSON
     */
    public static Template parse(String data) {
        return CodeClient.gson.fromJson(data, Template.class);
    }

    private static byte[] decompress(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
             GZIPInputStream gis = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }

            return bos.toByteArray();
        }
    }

    public int getLength() {
        int length = 0;
        for (var block : blocks) length += block.getLength();
        return length;
    }
}
