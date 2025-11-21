package com.fragmenterworks.ffxivextract.helpers;

import com.fragmenterworks.ffxivextract.Constants;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class EXDSchemaLoader {

    public static Map<Integer, String> loadColumnNames(String sheetName) {
        Map<Integer, String> columnNames = new HashMap<>();
        
        // Try local file first
        File file = new File(Constants.EXD_SCHEMA_PATH + sheetName + ".yml");

        InputStream inputStream = null;

        if (file.exists()) {
            try {
                inputStream = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                Utils.getGlobalLogger().error("File not found despite check: " + file.getAbsolutePath(), e);
            }
        } else {
            // Try to download stream
            inputStream = getUrlStream(sheetName);
        }

        if (inputStream != null) {
            try (InputStream is = inputStream) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(is);
                
                if (data != null && data.containsKey("fields")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
                    parseFields(fields, columnNames, new AtomicInteger(0), "");
                }

            } catch (Exception e) {
                Utils.getGlobalLogger().error("Failed to load schema for " + sheetName, e);
            }
        }

        return columnNames;
    }

    private static final Map<String, String> sheetNameMap = new HashMap<>();

    public static void parseRootExl(byte[] data) {
        if (data == null) return;
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data)))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                
                String[] split = line.split(",");
                if (split.length > 0) {
                    String sheetName = split[0];
                    sheetNameMap.put(sheetName.toLowerCase(), sheetName);
                }
            }
            Utils.getGlobalLogger().info("Loaded {} sheet names from root.exl", sheetNameMap.size());
        } catch (IOException e) {
            Utils.getGlobalLogger().error("Failed to parse root.exl", e);
        }
    }

    private static InputStream getUrlStream(String sheetName) {
        // Check map first
        String resolvedName = sheetNameMap.getOrDefault(sheetName.toLowerCase(), sheetName);
        
        InputStream stream = getUrlStreamInternal(resolvedName);
        
        // Fallback to capitalization check if map didn't help or stream failed
        if (stream == null && resolvedName.equals(sheetName) && sheetName.length() > 0) {
             String capitalized = sheetName.substring(0, 1).toUpperCase() + sheetName.substring(1);
             if (!capitalized.equals(sheetName)) {
                 stream = getUrlStreamInternal(capitalized);
             }
        }
        
        return stream;
    }

    private static InputStream getUrlStreamInternal(String sheetName) {
        try {
            URL url = URI.create(Constants.EXD_SCHEMA_URL + sheetName + ".yml").toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Utils.getGlobalLogger().info("Downloaded schema (memory): " + sheetName);
                return connection.getInputStream();
            }
        } catch (IOException e) {
            // Ignore download errors
        }
        return null;
    }

    private static void parseFields(List<Map<String, Object>> fields, Map<Integer, String> columnNames, AtomicInteger index, String prefix) {
        if (fields == null) return;

        for (Map<String, Object> field : fields) {
            String name = (String) field.getOrDefault("name", "");
            String type = (String) field.getOrDefault("type", "scalar");
            int count = field.containsKey("count") ? (Integer) field.get("count") : 1;
            
            String fullName;
            if (prefix.isEmpty()) {
                fullName = name;
            } else {
                fullName = name.isEmpty() ? prefix : prefix + "." + name;
            }

            if ("array".equals(type)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> subFields = (List<Map<String, Object>>) field.get("fields");
                for (int i = 0; i < count; i++) {
                    String arrayPrefix = fullName + "[" + i + "]";
                    if (subFields != null) {
                        parseFields(subFields, columnNames, index, arrayPrefix);
                    } else {
                        // Array of scalars
                        columnNames.put(index.getAndIncrement(), arrayPrefix);
                    }
                }
            } else {
                columnNames.put(index.getAndIncrement(), fullName);
            }
        }
    }
}
