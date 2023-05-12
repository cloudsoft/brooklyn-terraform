package io.cloudsoft.terraform.parser;

import io.cloudsoft.terraform.TerraformConfiguration;
import io.cloudsoft.terraform.compliance.DriftCheck;
import io.cloudsoft.terraform.entity.DataResource;
import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.entity.StartableManagedResource;
import io.cloudsoft.terraform.entity.TerraformResource;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityInitializer;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.entity.group.BasicGroup;
import org.apache.brooklyn.util.text.Strings;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.cloudsoft.terraform.entity.TerraformResource.*;

public  final class EntityParser {

    private static Predicate<Map<String,Object>> isRunnable = resource -> resource.get("resource.type").toString().endsWith("_instance") ||
            resource.get("resource.type").toString().endsWith("_virtual_machine") ||
            resource.get("resource.type").toString().endsWith("_cluster");

    private static String getIdPrefixFor(Entity entity) {
        if (entity == null)
            return "tf.";
        String prefix = entity.config().get(BrooklynCampConstants.PLAN_ID);
        return Strings.isNonBlank(prefix)? prefix+"." : entity.getId()+".";
    }

    public static void processResources(Map<String, Map<String,Object>> resources, Entity entity) {
        List<Map<String, Object>> dataResources = getDataResources(resources);
        List<Map<String, Object>> managedResources = getManagedResources(resources);
        int managedResourceNumber = managedResources.size();

        if(!dataResources.isEmpty()) {
            Optional<BasicGroup> groupOpt = getDataResourcesGroup(entity);
            final BasicGroup dataGroup;
            if (!groupOpt.isPresent()) {
                dataGroup = entity.addChild(EntitySpec.create(BasicGroup.class).configure(AbstractEntity.DEFAULT_DISPLAY_NAME, "Data Resources"));
                dataGroup.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.CREATED);
            } else {
                dataGroup = groupOpt.get();
            }
            dataResources.forEach(resource -> dataGroup.addChild(basicSpec(DataResource.class, resource, getIdPrefixFor(entity))));
        }
        if(!managedResources.isEmpty()) {
            managedResources.forEach(resource -> {
                resource.put("drift-compliance", ((TerraformConfiguration) entity).isApplyDriftComplianceToResources());
                resource.put("total-resource-number", managedResourceNumber);
                if (isRunnable.test(resource)){
                    entity.addChild(basicSpec(StartableManagedResource.class, resource, getIdPrefixFor(entity)));
                } else
                    entity.addChild(basicSpec(ManagedResource.class, resource, getIdPrefixFor(entity)));
                }
            );
        }
    }

    public static Optional<BasicGroup> getDataResourcesGroup(Entity entity) {
        return (Optional) entity.getChildren().stream().filter(c -> c instanceof BasicGroup && "Data Resources".equals(c.config().get(AbstractEntity.DEFAULT_DISPLAY_NAME))).findAny();
    }

    static Function<Object, String> extractMode =  obj -> (String)((Map<String, Object>)obj).getOrDefault("resource.mode", "other");

    public static List<Map<String, Object>> getDataResources (Map<String, Map<String,Object>> resources) {
        return resources.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(resObject -> "data".equals(extractMode.apply(resObject)))
                .collect(Collectors.toList());
    }

    public static List<Map<String, Object>> getManagedResources (Map<String, Map<String,Object>> resources) {
        return resources.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(resObject -> !"data".equals(extractMode.apply(resObject)))
                .collect(Collectors.toList());
    }

    public static EntitySpec<? extends Entity> basicSpec(Class<? extends TerraformResource> clazz, Map<String, Object> contentsMap, final String prefix) {
        EntitySpec<? extends Entity> spec = EntitySpec.create(clazz)
                .configure(STATE_CONTENTS, contentsMap)
                .configure(TYPE, contentsMap.get("resource.type").toString())
                .configure(PROVIDER, contentsMap.get("resource.provider").toString())
                .configure(ADDRESS, contentsMap.get("resource.address").toString())
                .configure(NAME, contentsMap.get("resource.name").toString())
                .configure(BrooklynCampConstants.PLAN_ID, prefix.concat(contentsMap.get("resource.address").toString()));
        EntityInitializer labels = (!Objects.isNull(contentsMap.get("drift-compliance")) && !Objects.isNull(contentsMap.get("total-resource-number"))) ?
                new DriftCheck((Boolean) contentsMap.get("drift-compliance"), (int) contentsMap.get("total-resource-number")) :
                new DriftCheck();
        spec.addInitializer(labels);
        return spec;
    }
}
