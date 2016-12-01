package io.cloudsoft.terraform;

import java.io.IOException;
import java.util.Map;

import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

public interface TerraformDriver extends SoftwareProcessDriver {

    String makeTerraformCommand(String argument);

    Map<String, Object> getState() throws JsonParseException, JsonMappingException, IOException;
}
