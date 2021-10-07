package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

public class Output {

    private String name = "";
    private String value = "";
    private String description = "";
    private Boolean sensitive = false;
    private Set<String> dependsOn;

    public Output() {

    }

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

    // setters

    public void setName(String name) {
        this.name = name;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSensitive(Boolean sensitive) {
        this.sensitive = sensitive;
    }

    public void setDependsOn(Set<String> dependsOn) {
        this.dependsOn = dependsOn;
    }


    // getters
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
        return dependsOn;
    }
}
