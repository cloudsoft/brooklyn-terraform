package io.cloudsoft.terraform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.gson.internal.LinkedTreeMap;
import io.cloudsoft.terraform.entity.DataResource;
import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.entity.TerraformResource;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.group.BasicGroup;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriverLifecycleEffectorTasks;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static io.cloudsoft.terraform.TerraformCommons.SSH_MODE;
import static io.cloudsoft.terraform.TerraformDriver.*;
import static io.cloudsoft.terraform.entity.StartableManagedResource.RESOURCE_STATUS;
import static io.cloudsoft.terraform.parser.EntityParser.processResources;

public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);
    private static final String TF_OUTPUT_SENSOR_PREFIX = "tf.output";

    private Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
    private AtomicBoolean configurationChangeInProgress = new AtomicBoolean(false);

    private Boolean applyDriftComplianceCheckToResources = false;

    @Override
    public void init() {
        super.init();
    }

    // TODO check this.
    @Override
    protected SoftwareProcessDriverLifecycleEffectorTasks getLifecycleEffectorTasks() {
        String executionMode = getConfig(TerraformCommons.TF_EXECUTION_MODE);
        if(Objects.equals(SSH_MODE, executionMode)) {
            return getConfig(LIFECYCLE_EFFECTOR_TASKS);
        } else {
            return new SoftwareProcessDriverLifecycleEffectorTasks(){
                @Override
                protected Map<String, Object> obtainProvisioningFlags(MachineProvisioningLocation<?> location) {
                    throw new  NotImplementedException("Should not be called!");
                }

                @Override
                protected Task<MachineLocation> provisionAsync(MachineProvisioningLocation<?> location) {
                    throw new  NotImplementedException("Should not be called!");
                }

                @Override
                protected void startInLocations(Collection<? extends Location> locations, ConfigBag parameters) {
                    entity().getDriver().start(); // TODO look at logic around starting children
                }
                // TODO stop might work, but if not check and implement
            };
        }
    }

    @Override
    public void rebind() {
        lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
        configurationChangeInProgress = new AtomicBoolean(false);
        super.rebind();
    }

    @Override
    protected void preStop() {
        super.preStop();
        getChildren().forEach(c -> c.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING));
    }

    @Override
    protected void postStop() {
        getChildren().forEach(c -> c.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED));
        getChildren().forEach(child -> {
            if (child instanceof BasicGroup){
                child.getChildren().stream().filter(gc -> gc instanceof TerraformResource)
                                .forEach(gc -> {
                                    removeChild(gc);
                                    Entities.unmanage(gc);
                                });
                removeChild(child);
            }
            if (child instanceof TerraformResource){
                removeChild(child);
                Entities.unmanage(child);
            }
        });
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

        Maybe<SshMachineLocation> machine = Locations.findUniqueSshMachineLocation(getLocations());
        if (machine.isPresent()) {
            addFeed(FunctionFeed.builder()
                    .entity(this)
                    .period(getConfig(TerraformCommons.POLLING_PERIOD))
                    .poll(FunctionPollConfig.forSensor(PLAN).supplier(new PlanProvider(getDriver()))
                            .onResult(new PlanSuccessFunction())
                            .onFailure(new PlanFailureFunction()))
                    .poll(FunctionPollConfig.forSensor(OUTPUT).supplier(new OutputProvider(getDriver()))
                            .onResult(new OutputSuccessFunction())
                            .onFailure(new OutputFailureFunction()))
                    .build());
        }
    }

    @Override
    protected void disconnectSensors() {
        disconnectServiceUpIsRunning();
        feeds().forEach(feed -> feed.stop());
        super.disconnectSensors();
    }

    /**
     *  This method is called only when TF and AMP are in sync
     *  No need to update state when no changes were detected.
     *  Since `terraform plan` is the only command reacting to changes, it makes sense entities to change according to its results.
     */
    private void updateDeploymentState() {
        final String result = getDriver().runShowTask();
        Map<String, Object> state = StateParser.parseResources(result);
        sensors().set(TerraformConfiguration.STATE, state);
        Map<String, Object> resources = new HashMap<>(state);
        updateResources(resources, this, ManagedResource.class);
        updateDataResources(resources, DataResource.class);
        if (!resources.isEmpty()) { // new resource, new child must be created
            processResources(resources,this);
        }
    }

    private static Predicate<? super Entity> runningOrSync = c -> !c.sensors().getAll().containsKey(RESOURCE_STATUS) || (!c.sensors().get(RESOURCE_STATUS).equals("running") &&
                    c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC));

    private void updateResources(Map<String, Object> resources, Entity parent, Class<? extends TerraformResource> clazz) {
        List<Entity> childrenToRemove = new ArrayList<>();
        parent.getChildren().stream().filter(c -> clazz.isAssignableFrom(c.getClass())).forEach(c -> {
            if (runningOrSync.test(c)){
                c.sensors().set(RESOURCE_STATUS, "running");
            }
            if (resources.containsKey(c.getConfig(TerraformResource.ADDRESS))) { //child in resource set, update sensors
                ((TerraformResource) c).refreshSensors((Map<String, Object>) resources.get(c.getConfig(TerraformResource.ADDRESS)));
                resources.remove(c.getConfig(TerraformResource.ADDRESS));
            } else {
                childrenToRemove.add(c);
            }
        });
        childrenToRemove.forEach(parent::removeChild); //  child not in resource set (deleted by terraform -> remove child)
    }

    /**
     * Updates Data resources
     */
    private void updateDataResources(Map<String, Object> resources, Class<? extends TerraformResource> clazz) {
        getChildren().stream().filter(c-> c instanceof BasicGroup)
                .findAny().ifPresent(c -> updateResources(resources, c, clazz));
    }

    public static class PlanProvider implements Supplier<Map<String,Object>> {
        TerraformDriver driver;
        public PlanProvider(TerraformDriver driver) {
            this.driver = driver;
        }

        @Override
        public Map<String, Object> get() {
            return driver.runJsonPlanTask();
        }
    }

    private final class PlanSuccessFunction implements Function<Map<String, Object>, Map<String, Object>>  {
        @Nullable
        @Override
        public Map<String, Object> apply(@Nullable Map<String, Object> tfPlanStatus) {
            try {
                boolean driftChanged = false;
                if (sensors().getAll().containsKey(PLAN) && sensors().get(PLAN).containsKey(RESOURCE_CHANGES) &&
                        !sensors().get(PLAN).get(RESOURCE_CHANGES).equals(tfPlanStatus.get(RESOURCE_CHANGES))) {
                    // we had drift previously, and now either we have different drift or we don't have drift
                    driftChanged = true;
                }

                final TerraformStatus currentPlanStatus = (TerraformStatus) tfPlanStatus.get(PLAN_STATUS);
                final boolean ignoreDrift = !getConfig(TerraformConfiguration.TERRAFORM_DRIFT_CHECK);

                if (ignoreDrift || currentPlanStatus == TerraformStatus.SYNC) {
                    // plan status is SYNC so no errors, no ASYNC resources OR drift is ignored
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", Entities.REMOVE);
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ERROR", Entities.REMOVE);
                    TerraformConfigurationImpl.this.sensors().remove(Sensors.newSensor(Object.class, "compliance.drift"));
                    TerraformConfigurationImpl.this.sensors().remove(Sensors.newSensor(Object.class, "tf.plan.changes"));
                    updateDeploymentState();

                } else if (TerraformConfiguration.TerraformStatus.ERROR.equals(tfPlanStatus.get(PLAN_STATUS))) {
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ERROR",
                            tfPlanStatus.get(PLAN_MESSAGE) + ":" + tfPlanStatus.get("tf.errors"));
                    updateResourceStates(tfPlanStatus);

                } else if (!tfPlanStatus.get(PLAN_STATUS).equals(TerraformConfiguration.TerraformStatus.SYNC)) {
                    TerraformConfigurationImpl.this.sensors().set(DRIFT_STATUS, (TerraformStatus) tfPlanStatus.get(PLAN_STATUS));
                    if (tfPlanStatus.containsKey(RESOURCE_CHANGES)) {
                        ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Resources no longer match initial plan. Invoke 'apply' to synchronize configuration and infrastructure.");
                        updateDeploymentState(); // we are updating the resources anyway, because we still need to inspect our infrastructure
                        updateResourceStates(tfPlanStatus);
                    } else {
                        ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Outputs no longer match initial plan.This is not critical as the infrastructure is not affected. However you might want to invoke 'apply'.");
                    }
                    TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "compliance.drift"), tfPlanStatus);
                    TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "tf.plan.changes"), getDriver().runPlanTask());
                }

                if (driftChanged || !sensors().getAll().containsKey(DRIFT_STATUS) || !sensors().get(DRIFT_STATUS).equals(tfPlanStatus.get(PLAN_STATUS))) {
                    TerraformConfigurationImpl.this.sensors().set(DRIFT_STATUS, (TerraformStatus) tfPlanStatus.get(PLAN_STATUS));
                }
                lastCommandOutputs.put(PLAN.getName(), tfPlanStatus);
                return tfPlanStatus;
            } catch (Exception e) {
                LOG.error("Unable to process terraform plan", e);
                throw Exceptions.propagate(e);
            }
        }

        private void updateResourceStates(Map<String, Object> tfPlanStatus) {
            Object hasChanges = tfPlanStatus.get(RESOURCE_CHANGES);
            LOG.debug("Terraform plan updating: " + tfPlanStatus + ", changes: "+hasChanges);
            if (hasChanges!=null) {
                ((List<Map<String, Object>>) hasChanges).forEach(changeMap -> {
                    String resourceAddr = changeMap.get("resource.addr").toString();
                    TerraformConfigurationImpl.this.getChildren().stream()
                            .filter(c -> c instanceof ManagedResource)
                            .filter(c -> resourceAddr.equals(c.config().get(TerraformResource.ADDRESS)))
                            .forEach(this::checkAndUpdateResource);
                });
            }
        }

        private void checkAndUpdateResource(Entity c) {
            if (!c.sensors().get(RESOURCE_STATUS).equals("changed") && !c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC)) {
                c.sensors().set(RESOURCE_STATUS, "changed");
            }
            ((ManagedResource) c).updateResourceState(); // TODO this method gets called twice when updating resources and updating them accoring to the plan, maybe fix at some point!!
        }
    }

    private final class PlanFailureFunction implements Function<Map<String, Object>, Map<String, Object>> {
        @Nullable
        @Override
        public Map<String, Object> apply(@Nullable Map<String, Object> input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(PLAN.getName())) {
                return (Map<String, Object>) lastCommandOutputs.get(PLAN.getName());
            } else {
                return input;
            }
        }
    }

    public static class OutputProvider implements Supplier<String> {
        TerraformDriver driver;
        public OutputProvider(TerraformDriver driver) {
            this.driver = driver;
        }

        @Override
        public String get() {
            return driver.runOutputTask();
        }
    }

    private final class OutputSuccessFunction implements Function<String, String> {
        @Override
        public String apply(String output) {
            if (Strings.isBlank(output)) {
                return "No output is applied.";
            }
            try {
                Map<String, Map<String, Object>> result = new ObjectMapper().readValue(output, LinkedTreeMap.class);
                // remove sensors that were removed in the configuration
                List<AttributeSensor<?>> toRemove = new ArrayList<>();
                sensors().getAll().forEach((sK, sV) -> {
                    final String sensorName = sK.getName();
                    if(sensorName.startsWith(TF_OUTPUT_SENSOR_PREFIX+".") && !result.containsKey(sensorName.replace(TF_OUTPUT_SENSOR_PREFIX +".", ""))) {
                        toRemove.add(sK);
                    }
                });
                toRemove.forEach(os -> sensors().remove(os));

                for (String name : result.keySet()) {
                    final String sensorName = String.format("%s.%s", TF_OUTPUT_SENSOR_PREFIX, name);
                    final AttributeSensor sensor = Sensors.newSensor(Object.class, sensorName);
                    final Object currentValue = sensors().get(sensor);
                    final Object newValue = result.get(name).get("value");
                    if (!Objects.equals(currentValue, newValue)) {
                        sensors().set(sensor, newValue);
                    }
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Output does not have the expected format!");
            }
            lastCommandOutputs.put(OUTPUT.getName(), output);
            return output;
        }
    }

    private final class OutputFailureFunction implements Function<String, String> {
        @Override
        public String apply(String input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(OUTPUT.getName())) {
                return (String) lastCommandOutputs.get(OUTPUT.getName());
            } else {
                return input;
            }
        }
    }

    @Override
    public Class<?> getDriverInterface() {
        return TerraformDriver.class;
    }

    @Override
    public TerraformDriver getDriver() {
        String executionMode = getConfig(TerraformCommons.TF_EXECUTION_MODE);
        if(Objects.equals(SSH_MODE, executionMode)) {
            return (TerraformDriver) super.getDriver();
        } else {
            return new TerraformContainerDriver(this);
        }
    }

    @Override
    @Effector(description = "Apply the Terraform configuration to the infrastructure. Changes made outside terraform are reset.")
    public void apply() {
        CountdownTimer timer = Duration.ONE_MINUTE.countdownTimer();
        while(true) {
            if (configurationChangeInProgress.compareAndSet(false, true)) {
                try {
                    Objects.requireNonNull(getDriver()).runApplyTask();
                    return;
                } finally {
                    configurationChangeInProgress.set(false);
                }
            } else {
                if(timer.isExpired()) {
                    throw new IllegalStateException("Cannot apply configuration: operation timed out.");
                }
                Time.sleep(Duration.FIVE_SECONDS);
            }
        }
    }

    @Override
    @Effector(description = "Destroy the Terraform configuration")
    public void destroy() {
        final boolean mayProceed = configurationChangeInProgress.compareAndSet(false, true);
        if (mayProceed) {
            try {
                preStop();
                super.stop();
                postStop();
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else {
            throw new IllegalStateException("Cannot destroy configuration: another operation is in progress.");
        }
    }

    @Override
    @Effector(description = "Performs Terraform apply again with the configuration provided via the provided URL. If an URL is not provided the original URL provided when this blueprint was deployed will be used." +
            "This is useful when the URL points to a GitHub or Artifactory release.")
    public void reinstallConfig(@EffectorParam(name = "configUrl", description = "URL pointing to the terraform configuration") @Nullable String configUrl) {
        if(StringUtils.isNotBlank(configUrl)) {
            config().set(TerraformCommons.CONFIGURATION_URL, configUrl);
        }
        CountdownTimer timer = Duration.ONE_MINUTE.countdownTimer();
        while(true) {
            if (configurationChangeInProgress.compareAndSet(false, true)) {
                try {
                    sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STARTING);
                    ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);
                    getDriver().customize();
                    getDriver().launch();
                    if(getChildren() == null || getChildren().isEmpty()) { // after a destroy
                        getDriver().postLaunch();
                        connectSensors();
                    }
                    return;
                } finally {
                    configurationChangeInProgress.set(false);
                    sensors().set(Startable.SERVICE_UP, Boolean.TRUE);
                    sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
                    sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
                    ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
                }
            } else {
                if(timer.isExpired()) {
                    throw new IllegalStateException("Cannot re-apply configuration: operation timed out.");
                }
                Time.sleep(Duration.FIVE_SECONDS);
            }
        }
    }

    @Override
    public Boolean isApplyDriftComplianceToResources(){
        return applyDriftComplianceCheckToResources;
    }

    @Override
    public void setApplyDriftComplianceToResources(Boolean doApply){
        applyDriftComplianceCheckToResources = doApply;
    }
}
