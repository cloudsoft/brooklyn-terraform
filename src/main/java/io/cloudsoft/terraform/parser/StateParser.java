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
                    resourceMap.put("resource.mode", resource.get("mode").asText()); // TODO decided what other modes we shupport and if it is worth to show these resources - "data" from vsphere for example
                    resourceMap.put("resource.type", resource.get("type").asText());
                    resourceMap.put("resource.name", resource.get("name").asText());
                    resourceMap.put("resource.provider", resource.get("provider").asText());

                    if(!resource.has("instances")) {
                        throw new  IllegalArgumentException ("A valid resource state must have instances!");
                    }

                    resource.get("instances").forEach(instance -> {
                        resourceMap.put("instance.status", instance.get("status") != null ? instance.get("status").asText() : "ok");
                        resourceMap.put("instance.private", instance.get("private") != null ? instance.get("private").asText() : "");
                        resourceMap.put("instance.dependencies", instance.get("dependencies") !=  null? instance.get("dependencies").toString() : "none");
                        resourceMap.put("instance.sensitive.attributes", instance.get("sensitive_attributes") !=  null? instance.get("sensitive_attributes").toString() : "none");
                        if(instance.has("attributes")) {
                            Iterator<Map.Entry<String, JsonNode>>  it = instance.get("attributes").fields();
                            while(it.hasNext()) {
                                Map.Entry<String,JsonNode> attribute =  it.next();
                                if(attribute.getValue() != null && !blankItems.contains(attribute.getValue().toString())) {
                                    if(attribute.getKey().equalsIgnoreCase("instance_state")) {
                                        resourceMap.put("instance.status", attribute.getValue().asText()); // overrides the previously set with "ok"
                                    }
                                    resourceMap.put("attribute." + attribute.getKey(), attribute.getValue());
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


    public static Map<String, Object> parseResource(Map<String, Object> resourceMap){
        Map<String, Object>  resourceResult = new LinkedHashMap<>();
        List<Map<String, Object>> instances = (List<Map<String, Object>>) resourceMap.get("instances");
        instances.forEach(instance -> {

           instance.forEach((k,v) ->  {
               if("sensitive_attributes".equals(k)) {
                   resourceResult.put("instance.sensitive.attributes", blankItems.contains(v.toString())? "none": v.toString());
               } else if("attributes".equals(k)) {
                   Map<String, Object> attributes = (Map<String, Object>) v;
                   attributes.forEach((ak, av) -> {
                       if(av != null && !blankItems.contains(av.toString())) {
                           if(ak.equalsIgnoreCase("instance_state")) {
                               resourceResult.put("instance.status", av);
                           }
                           resourceResult.put("attribute." + ak, av);
                       }
                   });
               } else {
                   resourceResult.put("instance." + k, v.toString());
               }
           });
        });

        return resourceResult;
    }
}
