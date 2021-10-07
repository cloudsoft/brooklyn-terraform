package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.databind.JsonNode;

public class InputVariable {

    String name;
    String defaultValue;
    String type;
    String description;
    JsonNode validation;
    Boolean sensitive;


}
