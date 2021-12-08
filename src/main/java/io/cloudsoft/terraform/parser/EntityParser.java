package io.cloudsoft.terraform.parser;

import io.cloudsoft.terraform.entity.DataResource;
import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.entity.StartableManagedResource;
import io.cloudsoft.terraform.entity.TerraformResource;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.entity.group.BasicGroup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.cloudsoft.terraform.entity.TerraformResource.*;

public  final class EntityParser {

    public static void processResources(Map<String, Object> resources, Entity entity) {
        List<Map<String, Object>> dataResources = getDataResources(resources);
        List<Map<String, Object>> managedResources = getManagedResources(resources);

        if(!dataResources.isEmpty()) {
            Optional<Entity> groupOpt =  entity.getChildren().stream().filter(c -> c instanceof BasicGroup).findAny();
            if (!groupOpt.isPresent()) {
                BasicGroup dataGroup = entity.addChild(EntitySpec.create(BasicGroup.class).configure(AbstractEntity.DEFAULT_DISPLAY_NAME, "Data Resources"));
                dataResources.forEach(resource -> dataGroup.addChild(basicSpec(DataResource.class, resource)));
                dataGroup.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.CREATED);
            }
        }
        if(!managedResources.isEmpty()) {
            managedResources.forEach(resource -> {
                if (resource.get("resource.type").toString().endsWith("_instance") || resource.get("resource.type").toString().endsWith("_virtual_machine")){
                    entity.addChild(basicSpec(StartableManagedResource.class, resource));
                } else
                    entity.addChild(basicSpec(ManagedResource.class, resource));
                }
            );
        }
    }

    static Function<Object, String> extractMode =  obj -> (String)((Map<String, Object>)obj).getOrDefault("resource.mode", "other");

    public static List<Map<String, Object>> getDataResources (Map<String, Object> resources) {
        return resources.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(resObject -> "data".equals(extractMode.apply(resObject)))
                .map(resObject -> (Map<String, Object>) resObject)
                .collect(Collectors.toList());
    }

    public static List<Map<String, Object>> getManagedResources (Map<String, Object> resources) {
        return resources.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(resObject -> !"data".equals(extractMode.apply(resObject)))
                .map(resObject -> (Map<String, Object>) resObject)
                .collect(Collectors.toList());
    }

    public static EntitySpec<? extends Entity> basicSpec(Class<? extends TerraformResource> clazz, Map<String, Object> contentsMap) {
        EntitySpec<? extends Entity> spec = EntitySpec.create(clazz)
                .configure(STATE_CONTENTS, contentsMap)
                .configure(TYPE, contentsMap.get("resource.type").toString())
                .configure(PROVIDER, contentsMap.get("resource.provider").toString())
                .configure(ADDRESS, contentsMap.get("resource.address").toString())
                .configure(NAME, contentsMap.get("resource.name").toString());
        return spec;
    }
}
