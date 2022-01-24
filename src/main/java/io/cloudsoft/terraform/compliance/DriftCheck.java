package io.cloudsoft.terraform.compliance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import io.cloudsoft.amp.dashboard.beans.ComplianceCheck;
import io.cloudsoft.terraform.TerraformConfiguration;
import io.cloudsoft.terraform.entity.ManagedResource;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.EntityInitializers;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.text.Identifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DriftCheck extends EntityInitializers.InitializerPatternWithConfigKeys{

    public static final AttributeSensor<Set<ComplianceCheck>> DRIFT_COMPLIANCE = Sensors.newSensor(ComplianceCheck.SET_TYPE_TOKEN, "dashboard.compliance.terraform.drift");
    public static final ConfigKey<Boolean> ENABLE_DRIFT_COMPLIANCE_CHECK_FOR_MANAGED_RESOURCES = ConfigKeys.newBooleanConfigKey("terraform.resources-drift.enabled", "drift status for managed resources", false);

    private static final Logger LOG = LoggerFactory.getLogger(DriftCheck.class);

    private Boolean doApplyToResources = false;

    private int managedResourcesNumber = 0;

    public DriftCheck() { }

    public DriftCheck(Boolean applyToResources, int resourcesNumber){
        doApplyToResources = applyToResources;
        managedResourcesNumber = resourcesNumber;
    }

    public DriftCheck(ConfigBag params) {
        super(params);
    }

    @Override
    public void apply(EntityLocal entity) {
        if (entity instanceof TerraformConfiguration){
            ((EntityInternal) entity).getMutableEntityType().addSensor(DRIFT_COMPLIANCE);
            ((TerraformConfiguration) entity).setApplyDriftComplianceToResources(initParam(ENABLE_DRIFT_COMPLIANCE_CHECK_FOR_MANAGED_RESOURCES));

            entity.subscriptions().subscribe(entity, TerraformConfiguration.PLAN, new DriftStatusCheck(entity));
            LOG.debug("Terraform Drift Compliance check created for configuration entity: " + entity.getDisplayName());
        } else if((entity instanceof ManagedResource) && doApplyToResources) {
            ((EntityInternal) entity).getMutableEntityType().addSensor(DRIFT_COMPLIANCE);

            entity.subscriptions().subscribe(entity, ManagedResource.RESOURCE_STATUS, new DriftStatusCheck(entity));
            LOG.debug("Terraform Drift Compliance check created for resource entity: " + entity.getDisplayName());
        }
    }


    public class DriftStatusCheck implements SensorEventListener<String> {

        private Entity entity;

        public DriftStatusCheck(@Nonnull EntityLocal entity) {
            this.entity = entity;
        }

        @Override
        public void onEvent(SensorEvent<String> event) {

            Boolean isDrift = false;
            String eventData = event.getValue();
            Map<String,String> tfPlanDataMap = new HashMap<>();

            if (Objects.isNull(eventData)) {
                // error in retriving data so assume drift is true
                isDrift = true;
            } else if (entity instanceof TerraformConfiguration){
                tfPlanDataMap = buildPlanMapFromString(eventData);
                if (Objects.isNull(tfPlanDataMap.get("plan.status")) ||
                        !(tfPlanDataMap.get("plan.status").equals("SYNC"))){
                    isDrift = true;
                }
            } else if (entity instanceof ManagedResource){
                if (!(eventData.equals("running") || eventData.equals("ok"))){
                    isDrift = true;
                }
            }

            ComplianceCheck result = new ComplianceCheck();
            result.id = Identifiers.makeRandomId(6);
            result.mode = "amp";
            result.priority = (entity instanceof TerraformConfiguration) ? 1d : (1d/((managedResourcesNumber != 0) ? managedResourcesNumber : entity.getParent().getChildren().size()));
            result.created = Instant.now();
            result.pass = !isDrift;
            result.summary = (entity instanceof TerraformConfiguration) ? getSummaryForConfiguration(entity, tfPlanDataMap) :
                    ((entity instanceof ManagedResource) ? getSummaryForResource(eventData) : "");
            result.notes = (entity instanceof TerraformConfiguration) ? getNotesForConfiguration(entity, tfPlanDataMap) : "";

            Set<ComplianceCheck> status = Sets.newHashSet();

            status.add(result);
            LOG.debug(String.format("Terraform Drift compliance check for: %s with result: %b",entity.getDisplayName(), result.pass));

            entity.sensors().set(DRIFT_COMPLIANCE, status);
        }

        private String getSummaryForConfiguration(Entity entity, Map<String,String> driftInfo){
            String summary = "";
            if (!Objects.isNull(driftInfo.get("plan.status"))){
                if (driftInfo.get("plan.status").equals("SYNC")) {
                    summary += "The infrastructure matches the configuration. No changes required.";
                } else {
                    summary += "The infrastructure does not match the configuration. Current status: " + driftInfo.get("plan.status") + ". ";
                    if (!Objects.isNull(driftInfo.get("plan.message")) && Objects.isNull(driftInfo.get("errors"))){
                        String message = driftInfo.get("plan.message").split(".Plan: ")[1];
                        summary += " - Required changes: " + message;
                    }
                }
            } else {
                summary += "There has been an error retrieving the current status of the configuration.";
            }
            return summary;
        }

        private String getNotesForConfiguration(Entity entity, Map<String,String> driftInfo){
            String notes = "Total number of managed resources: " + entity.getChildren().size() + ".";
            if (!(Objects.isNull(driftInfo.get("plan.status")) || (driftInfo.get("plan.status").equals("SYNC")))){
                if (!Objects.isNull(driftInfo.get("resource.changes"))){
                    String resourceChanges = driftInfo.get("resource.changes");
                    Map<String,String> resourceActions = buildResourceActionsFromString(resourceChanges);
                    notes += "Number of resources with problems: " + resourceActions.size() + " - Details: ";
                    for (String resourceName : resourceActions.keySet()){
                        notes += "(Resource: " + resourceName + ", action: " + resourceActions.get(resourceName) + "), ";
                    }
                    notes = notes.substring(0,notes.length()-1);
                }
            }
            if (!Objects.isNull(driftInfo.get("errors"))){
                notes += "Errors: " + driftInfo.get("errors");
            }
            return notes;
        }

        private String getSummaryForResource(String resourceStatus){
            if (resourceStatus.equals("running") || resourceStatus.equals("ok")){
                return "The resource is in a healthy state.";
            }
            return "The resource is not in a healthy state. The current state is: " + resourceStatus;
        }

        private Map<String,String> buildPlanMapFromString(String tfPlan){
            tfPlan = tfPlan.substring(4,tfPlan.length()-1);


            /*
            //Wanted to use the cool syntax below but it failed to work

            Map<String, String> tfPlanMap = Arrays.stream(tfPlan.split(", "))
                    .filter(s -> s.contains("="))
                    .map(s -> s.split("=",2))
                    .collect(Collectors.toMap(
                            item -> item[0],
                            item -> item[1]
                    ));
            */


            Map<String, String> tfPlanMap = new HashMap<>();
            String[] tfPlanProps = tfPlan.split(", tf.");
            for (String prop : tfPlanProps){
                if (prop.contains("=")){
                    tfPlanMap.put(prop.split("=")[0],prop.split("=",2)[1]);
                }
            }

            return tfPlanMap;
        }

        private Map<String,String> buildResourceActionsFromString(String resourceActions){
            Map<String,String> resourceActionMap = new HashMap<>();
            resourceActions = resourceActions.substring(2,resourceActions.length()-2);
            String[] resources = resourceActions.split("}, ");
            for (String resource: resources){
                resourceActionMap.put(resource.split(", ")[0].split("=")[1], resource.split(", ")[1].split("=")[1]);
            }
            return resourceActionMap;
        }
    }
}


