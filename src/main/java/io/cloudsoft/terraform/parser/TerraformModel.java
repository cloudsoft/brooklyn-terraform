package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.util.Strings;

import java.io.InputStream;
import java.util.*;

public class TerraformModel {

    private ObjectMapper objectMapper = new ObjectMapper();

    //resources
    Map<String, Resource> resources = new HashMap<String, Resource>();

    //input variables
    Map<String, InputVariable> inputVariables = new HashMap<String, InputVariable>();

    // output values
    Map<String, Output> outputs = new HashMap<String, Output>();

    private JsonNode configurationData;
    private JsonNode outputData;



    public TerraformModel(String configurationJson, String outputJson) {
        try {
            configurationData = objectMapper.readTree(configurationJson);
            outputData = objectMapper.readTree(outputJson);
        } catch (Exception e) {
            e.printStackTrace();
        }
        makeModel();
    }

    public TerraformModel(JsonNode configurationNode, JsonNode outputNode) {
        configurationData = configurationNode;
        outputData = outputNode;
        makeModel();
    }

    public TerraformModel() {
    }

    private void makeModel(){
        try {
            //build up inputs from configuration - parse HCL
            if (configurationData != null) {

                // initially, take resources from configuration (willl be overriden by state/show data)
                JsonNode resourcesNode = configurationData.at("/resource");
                Iterator<Map.Entry<String, JsonNode>> resourceFields = resourcesNode.fields();
                while (resourceFields.hasNext()) {
                    Map.Entry<String, JsonNode> resourceTypeGroup = resourceFields.next();
                    String resourceType = resourceTypeGroup.getKey();
                    JsonNode resourcesOfType = resourceTypeGroup.getValue();
                    Iterator<Map.Entry<String, JsonNode>> resourcesOfTypeIterator = resourcesOfType.fields();
                    while (resourcesOfTypeIterator.hasNext()) {
                        Map.Entry<String, JsonNode> resourceData = resourcesOfTypeIterator.next();
                        String resourceName = resourceData.getKey();
                        JsonNode resourceAttributesNode = resourceData.getValue();
                        Iterator<Map.Entry<String, JsonNode>> resourceAttributesNodeIterator = resourcesOfType.fields();
                        while (resourceAttributesNodeIterator.hasNext()) {
                            Map.Entry<String, JsonNode> resourceAttributeNodeData = resourceAttributesNodeIterator.next();
                            Resource resource = new Resource();
                            resource.setName(resourceName);
                            resource.setType(resourceType);
                            resource.setValues(resourceAttributeNodeData.getValue());
                            resourceName = resource.getName();

                            if (Strings.isNotNullAndNotEmpty(resourceName)){
                                resources.put(resourceName,resource);
                            }
                        }
                    }

                }


                // input vars
                JsonNode inputVariablesNode = configurationData.at("/variable");
                Iterator<Map.Entry<String, JsonNode>> inputVariablesFields = inputVariablesNode.fields();
                while (inputVariablesFields.hasNext()) {
                    Map.Entry<String, JsonNode> inputVariableNode = inputVariablesFields.next();

                    InputVariable variable = new InputVariable();
                    String variableName = inputVariableNode.getKey();
                    variable.setName(variableName);

                    Iterator<Map.Entry<String, JsonNode>> variableDataNode = inputVariableNode.getValue().fields();
                    while (variableDataNode.hasNext()){
                        Map.Entry<String, JsonNode> variableAttribute = variableDataNode.next();
                        String variableAttributeName = variableAttribute.getKey();
                        JsonNode variableAttributeValue = variableAttribute.getValue();
                        // handle acceptable values:
                        if (variableAttributeName.equals("default")) {
                            variable.setDefaultValue(variableAttributeValue.asText());
                            continue;
                        }
                        if (variableAttributeName.equals("description")) {
                            variable.setDescription(variableAttributeValue.asText());
                            continue;
                        }
                        if (variableAttributeName.equals("type")) {
                            variable.setType(variableAttributeValue.asText());
                            continue;
                        }
                        if (variableAttributeName.equals("validation")) {
                            variable.setValidation(variableAttributeValue);
                            continue;
                        }
                        if (variableAttributeName.equals("sensitive")) {
                            variable.setSensitive(variableAttributeValue.asBoolean());
                            continue;
                        }
                    }
                    inputVariables.put(variable.getName(), variable);


                }
            }


            if (outputData != null) {
                //build up resources from output data
                if (resources.size() > 0) {
                    // if we already have some resources parsed from the configuration bit,
                    // we just clear them as output/show is a better representation of actual real world
                    resources.clear();
                }
                JsonNode resourcesNode = outputData.at("/values/root_module/resources");
                if (resourcesNode.isArray()) {
                    for (JsonNode resourceNode: resourcesNode){
                        Resource resource = new Resource();
                        resource.parseInput(resourceNode);

                        String resourceName = resource.getName();

                        if (Strings.isNotNullAndNotEmpty(resourceName)){
                            resources.put(resourceName,resource);
                        }
                    }
                }
            }


            //build up outputs from output data



        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void updateModel(JsonNode configurationNode, JsonNode outputNode) {
        // update the data nodes
        if (configurationNode != null) {
            configurationData = configurationNode;
        }
        if (outputNode != null){
            outputData = outputNode;
        }

        // clear existing model
        clearModel();

        // make model
        makeModel();
    }

    public void clearModel() {
        resources.clear();
        inputVariables.clear();
        outputs.clear();
    }

    public void setConfigurationNode(JsonNode node) {
        configurationData = node;
    }

    public void setOutputNode(JsonNode node) {
        outputData = node;
    }



    // get, set, add, remove, clear for each

    //public Map<String,Object> get

}