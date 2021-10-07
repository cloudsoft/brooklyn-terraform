package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
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
            updateModel(objectMapper.readTree(configurationJson), objectMapper.readTree(outputJson));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public TerraformModel(JsonNode configurationNode, JsonNode outputNode) {
        updateModel(configurationNode,outputNode);
    }

    public TerraformModel() {
    }

    private void makeModelFromConfiguration() {
        try {
            //build up inputs from configuration - parse HCL
            if (configurationData != null) {
                resources.clear();
                // initially, take resources from configuration (will be overridden by state/show data)
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
                        Iterator<Map.Entry<String, JsonNode>> resourceAttributesNodeIterator = resourceAttributesNode.fields();
                        while (resourceAttributesNodeIterator.hasNext()) {
                            Map.Entry<String, JsonNode> resourceAttributeNodeData = resourceAttributesNodeIterator.next();
                            Resource resource = new Resource();
                            resource.setName(resourceName);
                            resource.setType(resourceType);
                            resource.setValues(resourceAttributeNodeData.getValue());

                            if (StringUtils.isNotEmpty(resourceName)){
                                resources.put(resourceName,resource);
                            }
                        }
                    }

                }

                inputVariables.clear();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void makeModelFromOutput (){
        try{
            if (outputData != null) {
                //build up resources from output data
                resources.clear();
                JsonNode resourcesNode = outputData.at("/values/root_module/resources");
                if (resourcesNode.isArray()) {
                    for (JsonNode resourceNode: resourcesNode){
                        Resource resource = new Resource();
                        resource.parseInput(resourceNode);

                        String resourceName = resource.getName();

                        if (StringUtils.isNotEmpty(resourceName)){
                            resources.put(resourceName,resource);
                        }
                    }
                }

                //build up outputs from output data
                outputs.clear();
                JsonNode outputsNode = outputData.at("/values/outputs");
                Iterator<Map.Entry<String, JsonNode>> outputIterator = outputsNode.fields();
                while (outputIterator.hasNext()) {
                    Map.Entry<String, JsonNode> outputVariable = outputIterator.next();
                    Output output = new Output();
                    output.setName(outputVariable.getKey());
                    Iterator<Map.Entry<String, JsonNode>> outputAttributeIterator = outputVariable.getValue().fields();
                    while (outputAttributeIterator.hasNext()) {
                        Map.Entry<String, JsonNode> outputAttribute = outputAttributeIterator.next();
                        String attributeName = outputAttribute.getKey();
                        if (attributeName.equals("value")) {
                            output.setValue(outputAttribute.getValue().asText());
                            continue;
                        }
                        if (attributeName.equals("description")) {
                            output.setDescription(outputAttribute.getValue().asText());
                            continue;
                        }
                        if (attributeName.equals("sensitive")) {
                            output.setSensitive(outputAttribute.getValue().asBoolean());
                            continue;
                        }
                        if (attributeName.equals("depends_on")) {
                            // TODO populate depends on
                            Set<String> dependsOn = new HashSet<String>();
                            output.setDependsOn(dependsOn);
                            continue;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void updateModel(JsonNode configurationNode, JsonNode outputNode) {
        // update the data nodes and the model
        if (configurationNode != null) {
            configurationData = configurationNode;
            makeModelFromConfiguration();
        }
        if (outputNode != null){
            outputData = outputNode;
            makeModelFromOutput();
        }
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