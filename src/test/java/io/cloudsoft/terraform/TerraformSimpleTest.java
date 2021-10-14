package io.cloudsoft.terraform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.cloudsoft.terraform.entity.ManagedResource;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.entity.database.mysql.MySqlNode;
import org.apache.brooklyn.util.text.Strings;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
public class TerraformSimpleTest {


    @Test
    public void xorTest() throws IOException {
        // nulls
        assertFalse(Strings.isNonBlank("1") ^ Strings.isNonBlank("1"));
        assertTrue(Strings.isNonBlank("1") ^ Strings.isNonBlank(null));
        assertTrue(Strings.isNonBlank(null) ^ Strings.isNonBlank("1"));
        assertFalse(Strings.isNonBlank(null) ^ Strings.isNonBlank(null));

        // empty strings
        assertFalse(Strings.isNonBlank("1") ^ Strings.isNonBlank("1"));
        assertTrue(Strings.isNonBlank("1") ^ Strings.isNonBlank(""));
        assertTrue(Strings.isNonBlank("") ^ Strings.isNonBlank("1"));
        assertFalse(Strings.isNonBlank("") ^ Strings.isNonBlank(""));
    }

    @Test
    public void readManagedResources(){
        ImmutableList blankItems = ImmutableList.of("[]", "", "null", "\"\"");
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode  node = objectMapper.readTree(new File("/Users/iulianacosmina/work-amp/brooklyn-terraform/src/test/resources/state/more-state.json"));
            //JsonNode  node = objectMapper.readTree(new File("/Users/iulianacosmina/work-amp/brooklyn-terraform/src/test/resources/state/updated-state.json"));
            //JsonNode  node = objectMapper.readTree(new File("/Users/iulianacosmina/work-amp/brooklyn-terraform/src/test/resources/state/vs-state.json"));
            if(node.isEmpty() || !node.isContainerNode() || node.get("terraform_version") == null) {
                throw new  IllegalArgumentException ("This is not a valid TF state!");
            }
            Map<String, Object> resources =  new HashMap<>();

            if(!node.has("resources")) {
                throw new  IllegalArgumentException ("A valid deployment state should have resources!");
            }

            node.get("resources").forEach(resource ->  {
                Map<String, Object>  resourceMap = new LinkedHashMap<>();

                if (resource.has("mode") && "managed".equals(resource.get("mode").asText())) {
                    resources.put(resource.get("name").asText(), resourceMap);
                    resourceMap.put("resource.mode", resource.get("mode").asText());
                    resourceMap.put("resource.type", resource.get("type").asText());
                    resourceMap.put("resource.name", resource.get("name").asText());
                    resourceMap.put("resource.provider", resource.get("provider").asText());

                    if(!resource.has("instances")) {
                        throw new  IllegalArgumentException ("A valid resource state should have instances!");
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
                }
            });

            resources.forEach((k,v) -> System.out.println(k + " : " + v + "\n"));
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

}


       /*                   EntitySpec<ManagedResource> reSpec =  EntitySpec.create(ManagedResource.class);
                        reSpec.configure(ManagedResource.MODE, resource.get("mode").asText());
                        reSpec.configure(ManagedResource.TYPE, resource.get("type").asText());
                        reSpec.configure(ManagedResource.NAME, resource.get("name").asText());
                        reSpec.configure(ManagedResource.PROVIDER, resource.get("provider").asText());*/