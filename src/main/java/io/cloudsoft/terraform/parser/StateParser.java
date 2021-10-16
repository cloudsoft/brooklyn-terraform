package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.util.*;

/**
 * Naive version. To be improved further.
 */
public class StateParser {
    public static final ImmutableList blankItems = ImmutableList.of("[]", "", "null", "\"\"", "{}", "[{}]");

    public static Map<String, Object> parseResources(final String state){
        Map<String, Object> result  = new HashMap<>();
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode root = objectMapper.readTree(state);

            if(root.isEmpty() || !root.isContainerNode() || root.get("terraform_version") == null) {
                throw new  IllegalArgumentException ("This is not a valid TF state!");
            }

            if(!root.has("values")) {
                throw new  IllegalArgumentException ("A valid deployment state should have values!");
            }
            if(!root.get("values").has("root_module")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a root_module!");
            }
            if(!root.get("values").get("root_module").has("resources")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a resources!");
            }

            JsonNode resourceNode = root.get("values").get("root_module").get("resources");

            resourceNode.forEach(resource ->  {
                Map<String, Object>  resourceBody = new LinkedHashMap<>();

                if (resource.has("mode") && "managed".equals(resource.get("mode").asText())) {
                    result.put(resource.get("address").asText(), resourceBody);

                    resourceBody.put("resource.address", resource.get("address").asText());
                    resourceBody.put("resource.mode", resource.get("mode").asText());
                    resourceBody.put("resource.type", resource.get("type").asText());
                    resourceBody.put("resource.name", resource.get("name").asText());
                    resourceBody.put("resource.provider", resource.get("provider_name").asText());
                    if(resource.has("values")) {
                        Iterator<Map.Entry<String, JsonNode>>  it = resource.get("values").fields();
                        while(it.hasNext()) {
                            Map.Entry<String,JsonNode> value =  it.next();
                            if(value.getValue() != null && !blankItems.contains(value.getValue().toString())) {
                                resourceBody.put("value." + value.getKey(), value.getValue());
                                if (value.getKey().equalsIgnoreCase("instance_state")) {
                                    resourceBody.put("resource.status", value.getValue().asText());
                                }
                            }
                        }
                    }

                    if(resource.has("sensitive_values")) {
                        Iterator<Map.Entry<String, JsonNode>>  it = resource.get("sensitive_values").fields();
                        while(it.hasNext()) {
                            Map.Entry<String,JsonNode> value =  it.next();
                            if(value.getValue() != null && !blankItems.contains(value.getValue().toString())) {
                                resourceBody.put("sensitive.value." + value.getKey(), value.getValue() + "\n");
                            }
                        }
                    }
                }

            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse Terraform state!", e);
        }
        return result;
    }
}
