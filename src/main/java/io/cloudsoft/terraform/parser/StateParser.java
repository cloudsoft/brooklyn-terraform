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
    public static final ImmutableList blankItems = ImmutableList.of("[]", "", "null", "\"\"");

    public static List<Map<String, Object>> parse (final String state){
        List<Map<String, Object>> result  = new ArrayList<>();
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode root = objectMapper.readTree(state);

            if(root.isEmpty() || !root.isContainerNode() || root.get("terraform_version") == null) {
                throw new  IllegalArgumentException ("This is not a valid TF state!");
            }
            if(!root.has("resources")) {
                throw new  IllegalArgumentException ("A valid deployment state must have resources!");
            }

            root.get("resources").forEach(resource ->  {
                Map<String, Object>  resourceMap = new LinkedHashMap<>();
                if (resource.has("mode") && "managed".equals(resource.get("mode").asText())) {
                    resourceMap.put("resource.mode", resource.get("mode").asText());
                    resourceMap.put("resource.type", resource.get("type").asText());
                    resourceMap.put("resource.name", resource.get("name").asText());
                    resourceMap.put("resource.provider", resource.get("provider").asText());

                    if(!resource.has("instances")) {
                        throw new  IllegalArgumentException ("A valid resource state must have instances!");
                    }

                    resource.get("instances").forEach(instance -> {
                        resourceMap.put("instance.status", instance.get("status") != null ? instance.get("status").asText() : "up");
                        resourceMap.put("instance.private", instance.get("private") != null ? instance.get("private").asText() : "");
                        resourceMap.put("instance.dependencies", instance.get("dependencies") !=  null? instance.get("dependencies").toString() : "none");
                        resourceMap.put("instance.sensitive.attributes", instance.get("sensitive_attributes") !=  null? instance.get("sensitive_attributes").toString() : "none");
                        if(instance.has("attributes")) {
                            Iterator<Map.Entry<String, JsonNode>>  it = instance.get("attributes").fields();
                            while(it.hasNext()) {
                                Map.Entry<String,JsonNode> attribute =  it.next();
                                if(attribute.getValue() != null && !blankItems.contains(attribute.getValue().toString())) {
                                    resourceMap.put("attribute." + attribute.getKey(), attribute.getValue() + "\n");
                                }
                            }
                        }
                    });
                    result.add(resourceMap);
                }
            });


        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse Terraform state!", e);
        }
        return result;
    }
}
