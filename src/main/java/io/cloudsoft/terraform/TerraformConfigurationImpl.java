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
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.cloudsoft.terraform.TerraformDriver.*;
import static io.cloudsoft.terraform.entity.StartableManagedResource.RESOURCE_STATUS;
import static io.cloudsoft.terraform.parser.EntityParser.processResources;

public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);
    private static final String TF_OUTPUT_SENSOR_PREFIX = "tf.output";

    private Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
    private AtomicBoolean configurationChangeInProgress = new AtomicBoolean(false);

    private Boolean applyDriftComplianceCheckToResources = false;
    private Boolean isPostApply = false;

    @Override
    public void init() {
        super.init();
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
                child.getChildren().forEach(grandChild -> {
                    if (grandChild instanceof TerraformResource){
                        removeChild(grandChild);
                        Entities.unmanage(grandChild);
                    }
                } );
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
                    .period(getConfig(TerraformConfiguration.POLLING_PERIOD))
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

    private void updateResources(Map<String, Object> resources, Entity parent, Class<? extends TerraformResource> clazz) {
        List<Entity> childrenToRemove = new ArrayList<>();
        parent.getChildren().stream().filter(c -> clazz.isAssignableFrom(c.getClass())).forEach(c -> {
            if (Objects.isNull(c.sensors().get(RESOURCE_STATUS)) ||
                    (!c.sensors().get(RESOURCE_STATUS).equals("running") &&
                    c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC))){
                c.sensors().set(RESOURCE_STATUS, "running");
            }
            if (resources.containsKey(c.getConfig(TerraformResource.ADDRESS))) { //child in resource set, update sensors
                ((TerraformResource) c).refreshSensors((Map<String, Object>) resources.get(c.getConfig(TerraformResource.ADDRESS)));
                resources.remove(c.getConfig(TerraformResource.ADDRESS));
            } else {
                childrenToRemove.add(c);
            }
        });
        childrenToRemove.forEach(c -> parent.removeChild(c)); //  child not in resource set (deleted by terraform -> remove child)
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
            Boolean driftChanged = false;
            if (!Objects.isNull(sensors().get(PLAN)) &&
                    sensors().get(PLAN).containsKey("tf.resource.changes") &&
                    !sensors().get(PLAN).get("tf.resource.changes").equals(tfPlanStatus.get("tf.resource.changes"))){
                driftChanged = true;
            }
            if(tfPlanStatus.get(PLAN_STATUS).equals( TerraformConfiguration.TerraformStatus.ERROR)) {
                ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ERROR",
                        tfPlanStatus.get(PLAN_MESSAGE) + ":" + tfPlanStatus.get("tf.errors"));
                updateResourceStates(tfPlanStatus);
            } else if(!tfPlanStatus.get(PLAN_STATUS).equals(TerraformConfiguration.TerraformStatus.SYNC)) {
                if (tfPlanStatus.containsKey(RESOURCE_CHANGES)) {
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Resources no longer match initial plan. Invoke 'apply' to synchronize configuration and infrastructure.");
                    updateDeploymentState(); // we are updating the resources anyway, because we still need to inspect our infrastructure
                    updateResourceStates(tfPlanStatus);
                } else {
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Outputs no longer match initial plan.This is not critical as the infrastructure is not affected. However you might want to invoke 'apply'.");
                }
                TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "compliance.drift"), tfPlanStatus);
                TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "tf.plan.changes"), getDriver().runPlanTask());
            } else {
                // plan status is SYNC so no errors, no ASYNC resources
                ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", Entities.REMOVE);
                ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ERROR", Entities.REMOVE);
                TerraformConfigurationImpl.this.sensors().remove(Sensors.newSensor(Object.class, "compliance.drift"));
                TerraformConfigurationImpl.this.sensors().remove(Sensors.newSensor(Object.class, "tf.plan.changes"));
                updateDeploymentState();
            }
            if (driftChanged || Objects.isNull(sensors().get(DRIFT_STATUS)) || !sensors().get(DRIFT_STATUS).equals(tfPlanStatus.get("tf.plan.status"))){
                sensors().set(DRIFT_STATUS, (TerraformStatus) tfPlanStatus.get("tf.plan.status"));
            }
            lastCommandOutputs.put(PLAN.getName(), tfPlanStatus);
            return tfPlanStatus;
        }

        private void updateResourceStates(Map<String, Object> tfPlanStatus) {
            if(tfPlanStatus.containsKey(RESOURCE_CHANGES)) {
                ((List<Map<String, Object>>) tfPlanStatus.get(RESOURCE_CHANGES)).forEach(changeMap -> {
                    String resourceAddr = changeMap.get("resource.addr").toString();
                    TerraformConfigurationImpl.this.getChildren().stream()
                            .filter(c -> c instanceof ManagedResource)
                            .filter(c -> resourceAddr.equals(c.config().get(TerraformResource.ADDRESS)))
                            .findAny().ifPresent(c -> {
                                if (!c.sensors().get(RESOURCE_STATUS).equals("changed") &&
                                    !c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC)) {
                                    c.sensors().set(RESOURCE_STATUS, "changed");
                                }
                                ((ManagedResource) c).updateResourceState();
                            });
                });
            }
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

    /**
     * Output looks like
     * <pre>
     * {
     *   "address": {
     *     "sensitive": false,
     *     "type": "string",
     *     "value": "172.31.2.35"
     *   },
     *   ...
     * }
     * </pre>
     */
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
        return (TerraformDriver) super.getDriver();
    }

    @Override
    @Effector(description = "Apply the Terraform configuration to the infrastructure. Changes made outside terraform are reset.")
    public void apply() {
        CountdownTimer timer = Duration.ONE_MINUTE.countdownTimer();
        while(true) {
            if (configurationChangeInProgress.compareAndSet(false, true)) {
                try {
                    getDriver().jsonPlanCommand(); // avoid stale plan terraform issue
                    getDriver().runApplyTask();
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
            config().set(CONFIGURATION_URL, configUrl);
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
