package com.charge.ocpp.resolve.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class VersionHexDecoderUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String extractVersionHexToDec(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return MAPPER.writeValueAsString(rewrite(root));
        } catch (Exception e) {
            return json;
        }
    }

    public static Map<String,Object>extractVersionHexToDec(Map<String,Object>map) {
        return MAPPER.convertValue(rewrite(MAPPER.valueToTree(map)), new TypeReference<Map<String, Object>>(){});
    }


    private static JsonNode rewrite(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            ObjectNode newObj = MAPPER.createObjectNode();
            obj.fields().forEachRemaining(e -> {
                String key = e.getKey();
                JsonNode val = e.getValue();
                if (val.isTextual() && key.endsWith("Version")) {
                    String hex = val.asText();
                    switch (hex.length()) {
                        case 4:   // 2 字节
                            newObj.put(key, parse2(hex));
                            return;
                        case 10:  // 5 字节
                            newObj.put(key, parse5(hex));
                            return;
                        case 12:  // 6 字节
                            newObj.put(key, parse6(hex));
                            return;
                        default:
                            newObj.set(key, val);
                    }
                } else {
                    newObj.set(key, rewrite(val));
                }
            });
            return newObj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode newArr = MAPPER.createArrayNode();
            node.forEach(n -> newArr.add(rewrite(n)));
            return newArr;
        }
        return node;
    }

    /* 2字节：五位，不足前置补0，1.3.5位后插入“.” */
    private static String parse2(String hex) {
        int majorRaw = Integer.parseInt(hex, 16);
        return fmt5(majorRaw);
    }

    /* 5 字节：前1字节大版本，后4字节小版本 */
    private static String parse5(String hex) {
        int b1 = Integer.parseInt(hex.substring(0, 2), 16);
        String major = fmt3(b1);
        String datePart = fmtDate(hex.substring(2, 10));
        return major + "_" + datePart;
    }

    /* 6 字节：前 2 字节大版本，后 4 字节小版本 */
    private static String parse6(String hex) {
        int majorRaw = Integer.parseInt(hex.substring(0, 4), 16);
        String major = fmt5(majorRaw);
        String datePart = fmtDate(hex.substring(4, 12));
        return major + "_" + datePart;
    }

    /* 5 位补零 + 第 1、3 位后插点（2、6字节大版本用） */
    private static String fmt5(int val) {
        String five = String.format("%05d", val);
        return five.substring(0, 1) + "." +
                five.substring(1, 3) + "." +
                five.substring(3, 5);
    }

    /* 3位补零 + 第 1、3 位后插点（5字节大版本用） */
    private static String fmt3(int val) {
        String three = String.format("%03d", val);
        return three.substring(0, 1) + "." +
                three.substring(1, 2) + "." +
                three.substring(2, 3);
    }

    /* 日期部分：前3字节强制2位，最后1字节不用补0，用括号括起来 */
    private static String fmtDate(String eightHex) {
        String d1 = String.format("%02d", Integer.parseInt(eightHex.substring(0, 2), 16));
        String d2 = String.format("%02d", Integer.parseInt(eightHex.substring(2, 4), 16));
        String d3 = String.format("%02d", Integer.parseInt(eightHex.substring(4, 6), 16));
        int d4 = Integer.parseInt(eightHex.substring(6, 8), 16);
        return d1 + d2 + d3 + "(" + d4 + ")";
    }

}