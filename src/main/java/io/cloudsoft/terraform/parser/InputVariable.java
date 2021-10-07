package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.databind.JsonNode;

public class InputVariable {

    private String name = "";
    private String defaultValue = "";
    private String type = "";
    private String description = "";
    private JsonNode validation;
    private Boolean sensitive = false;

    public InputVariable () {

    }


    // getters

    public String getName() {
        return name;
    }

    public Boolean isSensitive() {
        return sensitive;
    }

    //setters

    public void setName (String name) {
        this.name = name;
    }

    public void setDefaultValue (String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public void setType (String type) {
        this.type = type;
    }

    public void setDescription (String description) {
        this.description = description;
    }

    public void setValidation(JsonNode validationNode) {
        this.validation = validationNode;
    }

    public void setSensitive(Boolean sensitive) {
        this.sensitive = sensitive;
    }
}
