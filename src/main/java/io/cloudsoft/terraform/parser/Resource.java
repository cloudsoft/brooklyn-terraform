package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class Resource {

    private String address;
    private String mode;
    private String type;
    private String name;
    private String provider_name;
    private int schema_version;
    private Map<String,Object> values = new HashMap<String,Object>();
    private Map<String,Object> sensitiveValues = new HashMap<String,Object>();
    ObjectMapper objectMapper = new ObjectMapper();

    public Resource(){
    }

    public void parseInput(String resourceJson) {
        try {
            JsonNode resourceNode = objectMapper.readTree(resourceJson);
            parseInput(resourceNode);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void parseInput(JsonNode resourceNode) {
        try {
            address = resourceNode.get("address").asText();
            mode = resourceNode.get("mode").asText();
            type = resourceNode.get("type").asText();
            name = resourceNode.get("name").asText();
            provider_name = resourceNode.get("provider_name").asText();
            schema_version = resourceNode.get("schema_version").asInt();
            values = objectMapper.convertValue(resourceNode.get("values"), new TypeReference<Map<String,Object>>(){});
            sensitiveValues = objectMapper.convertValue(resourceNode.get("sensitive_values"), new TypeReference<Map<String,Object>>(){});
        } catch (Exception e) {
            // propagate
            e.printStackTrace();
        }
    }

    //getters
    public String getAddress(){
        return address;
    }

    public String getMode(){
        return mode;
    }

    public String getType(){
        return type;
    }

    public String getName(){
        return name;
    }

    public String getProviderName(){
        return provider_name;
    }

    public int getSchemaVersion(){
        return schema_version;
    }

    public Map<String,Object> getValues(){
        return values;
    }

    public Map<String,Object> getSensitiveValues(){
        return sensitiveValues;
    }

    // setters
    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }



}
