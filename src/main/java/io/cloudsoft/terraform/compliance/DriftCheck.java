package io.cloudsoft.terraform.compliance;

import com.google.common.collect.Sets;
import io.cloudsoft.amp.dashboard.beans.ComplianceCheck;
import io.cloudsoft.terraform.TerraformConfiguration;
import io.cloudsoft.terraform.TerraformConfiguration.TerraformStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

            entity.subscriptions().subscribe(entity, TerraformConfiguration.DRIFT_STATUS, new TerraformEntityDriftCheck(entity));
            LOG.debug("Terraform Drift Compliance check created for configuration entity: " + entity.getDisplayName());
        } else if((entity instanceof ManagedResource) && doApplyToResources) {
            ((EntityInternal) entity).getMutableEntityType().addSensor(DRIFT_COMPLIANCE);

            entity.subscriptions().subscribe(entity, ManagedResource.RESOURCE_STATUS, new TerraformResourceDriftCheck(entity));
            LOG.debug("Terraform Drift Compliance check created for resource entity: " + entity.getDisplayName());
        }
    }

    public class TerraformEntityDriftCheck implements SensorEventListener<TerraformStatus> {

        private Entity entity;

        public TerraformEntityDriftCheck(@Nonnull EntityLocal entity) {
            this.entity = entity;
        }

        @Override
        public void onEvent(SensorEvent<TerraformStatus> event) {
            TerraformStatus state = event.getValue();
            Map<String,Object> tfPlan = entity.sensors().get(TerraformConfiguration.PLAN);

            ComplianceCheck result = new ComplianceCheck();
            result.id = Identifiers.makeRandomId(6);
            result.mode = "amp";
            result.priority = 1d;
            result.created = Instant.now();
            result.pass = state.equals(TerraformStatus.SYNC);
            result.summary = getSummaryForConfiguration(entity, tfPlan);
            result.notes = getNotesForConfiguration(entity, tfPlan);

            Set<ComplianceCheck> status = Sets.newHashSet();

            status.add(result);
            LOG.debug(String.format("Terraform Drift compliance check for: %s with result: %b",entity.getDisplayName(), result.pass));

            entity.sensors().set(DRIFT_COMPLIANCE, status);
        }

        private String getSummaryForConfiguration(Entity entity, Map<String,Object> driftInfo){
            String summary = "";
            if (driftInfo.containsKey("tf.plan.status")){
                if (driftInfo.get("tf.plan.status").equals(TerraformStatus.SYNC)) {
                    summary += "The infrastructure matches the configuration. No changes required.";
                } else {
                    summary += "The infrastructure does not match the configuration. Current status: " + driftInfo.get("tf.plan.status") + ". ";
                    if (driftInfo.containsKey("tf.plan.message") && !driftInfo.containsKey("errors")){
                        String message = ((String) driftInfo.get("tf.plan.message")).split(".Plan: ")[1];
                        summary += " - Required changes: " + message;
                    }
                }
            } else {
                summary += "There has been an error retrieving the current status of the configuration.";
            }
            return summary;
        }

        private String getNotesForConfiguration(Entity entity, Map<String,Object> driftInfo){
            String notes = "Total number of managed resources: " + entity.getChildren().size() + ". ";
            if (driftInfo.containsKey("tf.plan.status") &&
                    !(driftInfo.get("tf.plan.status").equals(TerraformConfiguration.TerraformStatus.SYNC)) &&
                    driftInfo.containsKey("tf.resource.changes")){
                List<Map<String, Object>> resourcesChanges = (List<Map<String, Object>>) driftInfo.get("tf.resource.changes");
                notes += "Number of resources with problems: " + resourcesChanges.size() + " - Details: ";
                for (Map<String,Object> resource : resourcesChanges){
                    notes += "(Resource: " + resource.get("resource.addr") + ", action: " + resource.get("resource.action") + "), ";
                }
                notes = notes.substring(0,notes.length()-2);
            }
            if (!Objects.isNull(driftInfo.get("errors"))){
                notes += " - Errors: " + driftInfo.get("errors");
            }
            return notes;
        }
    }

    public class TerraformResourceDriftCheck implements SensorEventListener<String> {

        private Entity entity;

        public TerraformResourceDriftCheck(@Nonnull EntityLocal entity) {
            this.entity = entity;
        }

        @Override
        public void onEvent(SensorEvent<String> event) {
            String eventData = event.getValue();

            ComplianceCheck result = new ComplianceCheck();
            result.id = Identifiers.makeRandomId(6);
            result.mode = "amp";
            result.priority = (managedResourcesNumber != 0) ? 1d/managedResourcesNumber : 1d/entity.getParent().getChildren().size();
            result.created = Instant.now();
            result.pass = eventData.equals("running") || eventData.equals("ok");
            result.summary = getSummaryForResource(eventData);
            result.notes = "";

            Set<ComplianceCheck> status = Sets.newHashSet();

            status.add(result);
            LOG.debug(String.format("Terraform Drift compliance check for: %s with result: %b",entity.getDisplayName(), result.pass));

            entity.sensors().set(DRIFT_COMPLIANCE, status);
        }

        private String getSummaryForResource(String resourceStatus){
            if (resourceStatus.equals("running") || resourceStatus.equals("ok")){
                return "The resource is in a healthy state.";
            }
            return "The resource is not in a healthy state. The current state is: " + resourceStatus;
        }
    }
}