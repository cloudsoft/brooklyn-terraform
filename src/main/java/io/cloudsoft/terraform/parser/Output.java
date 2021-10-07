package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

public class Output {

    String name;
    String value;
    String description;
    Boolean sensitive;
    Set<String> dependsOn;



    public Output(String outputJson) {
        try{
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode outputNode = objectMapper.readTree(outputJson);
            name = outputNode.asText();
            JsonNode outputDataNode = outputNode.get(name);
            value = outputDataNode.get("value").asText();
            description = outputDataNode.get("description").asText();
            sensitive = outputDataNode.get("sensitive").asBoolean();
            JsonNode dependsOnNode = outputDataNode.get("sensitive");
            if (dependsOnNode.isArray()) {
                for (JsonNode dependency : dependsOnNode) {
                    dependsOn.add(dependency.asText());
                }
            }
        } catch (Exception e) {
            // handle exception
        }

    }

    // getters / setters
    public String getName(){
        return name;
    }

    public String getValue(){
        return value;
    }

    public String getDescription(){
        return description;
    }

    public Boolean isSensitive(){
        return sensitive;
    }

    public Set<String> getDependsOn(){
        return getDependsOn();
    }
}
