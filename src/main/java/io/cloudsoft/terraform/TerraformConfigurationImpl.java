package io.cloudsoft.terraform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.common.collect.Maps.EntryTransformer;
import com.google.gson.internal.LinkedTreeMap;
import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.CommandPollConfig;
import org.apache.brooklyn.feed.ssh.SshFeed;
import org.apache.brooklyn.feed.ssh.SshPollValue;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.collect.Maps.transformEntries;


public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);
    private static final String TF_OUTPUT_SENSOR_PREFIX = "tf.output";
    private static final Duration FEED_UPDATE_PERIOD = Duration.seconds(30);

    private SshFeed sshFeed;
    private Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
    private AtomicBoolean configurationChangeInProgress = new AtomicBoolean(false);

    @Override
    public void init() {
        super.init();
        // Exactly one of the two must have a value
        if (Strings.isNonBlank(getConfig(CONFIGURATION_URL)) ^ Strings.isNonBlank(getConfig(CONFIGURATION_CONTENTS))) {
            if (getAttribute(CONFIGURATION_IS_APPLIED) == null) {
                sensors().set(CONFIGURATION_IS_APPLIED, false);
            }
        } else {
            throw new IllegalArgumentException("Exactly one of " +
                    CONFIGURATION_URL.getName() + " or " + CONFIGURATION_CONTENTS.getName() + " must be provided");
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
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

        Maybe<SshMachineLocation> machine = Locations.findUniqueSshMachineLocation(getLocations());
        if (machine.isPresent()) {
            Map<String, String> env = shellEnv();
            addFeed(sshFeed = SshFeed.builder()
                    .entity(this)
                    .period(FEED_UPDATE_PERIOD)
                    .machine(machine.get())
                    .poll(CommandPollConfig.forMultiple()
                            .env(env)
                            .command(getDriver().planCommand())
                            .onSuccess(new PlanSuccessFunction())
                            .onFailure(new PlanFailureFunction()))
                    .poll(new CommandPollConfig<>(OUTPUT)
                            .env(env)
                            .command(getDriver().outputCommand())
                            .onSuccess(new OutputSuccessFunction())
                            .onFailure(new OutputFailureFunction()))
                    .build());
        }
    }

    @Override
    protected void disconnectSensors() {
        disconnectServiceUpIsRunning();
        if (sshFeed != null) sshFeed.stop();
        super.disconnectSensors();
    }

    /**
     *  No need to update state when no changes were detected.
     *  Since `terraform plan` is the only command reacting to changes, it makes sense entities to change according to its results.
     */
    private void updateDeploymentState() {
        final String result = getDriver().runShowTask();
        Map<String, Object> state = StateParser.parseResources(result);
        sensors().set(TerraformConfiguration.STATE, state);
        Map<String, Object> resources = new HashMap<>(state);
        getChildren().forEach(c -> {
            if (resources.containsKey(c.getConfig(ManagedResource.ADDRESS))) { //child in resource set, update sensors
                ((ManagedResource) c).refreshSensors((Map<String, Object>) resources.get(c.getConfig(ManagedResource.ADDRESS)));
                resources.remove(c.getConfig(ManagedResource.ADDRESS));
            } else {
                getChildren().remove(c); // else  child not in resource set (deleted by terraform -> remove child)
            }
        });
        if (!resources.isEmpty()) { // new resource, new child must be created
            resources.forEach((resourceName, resourceContents) -> {
                Map<String, Object> contentsMap = (Map<String, Object>) resourceContents;
                addChild(
                        EntitySpec.create(ManagedResource.class)
                                .configure(ManagedResource.STATE_CONTENTS, contentsMap)
                                .configure(ManagedResource.TYPE, contentsMap.get("resource.type").toString())
                                .configure(ManagedResource.PROVIDER, contentsMap.get("resource.provider").toString())
                                .configure(ManagedResource.ADDRESS, contentsMap.get("resource.address").toString())
                                .configure(ManagedResource.NAME, contentsMap.get("resource.name").toString())
                );
            });
        }
    }

    private final class PlanSuccessFunction implements Function<SshPollValue, Void> {
        @Override
        public Void apply(SshPollValue input) {
            String output = input.getStdout();
            Map<String, Object> tfPlanStatus = StateParser.parsePlanLogEntries(output);
            if(!tfPlanStatus.get("tf.plan").equals( TerraformConfiguration.TerraformStatus.SYNC)) {
                sensors().set(CONFIGURATION_IS_APPLIED, false);
                ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Resources no longer match initial plan.");
                // user is asked to execute 'terraform apply'
                TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "compliance.drift"), tfPlanStatus);
            } else {
                ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", Entities.REMOVE);
            }
            TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "tf.plan"), tfPlanStatus.get("tf.plan"));
            TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "tf.plan.message"), tfPlanStatus.get("tf.plan.message"));
            updateDeploymentState();
            lastCommandOutputs.put(PLAN.getName(), tfPlanStatus);
            return null;
        }
    }

    private final class PlanFailureFunction implements Function<SshPollValue, Void> {
        @Override
        public Void apply(SshPollValue input) {
            // TODO figure how with what to replace this.
//            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(PLAN.getName())) {
//                (String) lastCommandOutputs.get(PLAN.getName());
//            } else {
//               input.getStderr();
//            }
            return null;
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
    private final class OutputSuccessFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            String output = input.getStdout();
            if (output != null) {
                try {
                    Map<String, Map<String, Object>> result = new ObjectMapper().readValue(output, LinkedTreeMap.class);
                    for (String name : result.keySet()) {
                        final String sensorName = String.format("%s.%s", TF_OUTPUT_SENSOR_PREFIX, name);
                        final AttributeSensor sensor = Sensors.newSensor(Object.class, sensorName);
                        final Object currentValue = sensors().get(sensor);
                        final Object newValue = result.get(name).get("value");
                        if (!Objects.equal(currentValue, newValue)) {
                            sensors().set(sensor, newValue);
                        }
                    }
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Output does not have the expected format!");
                }
            }
            if (Strings.isBlank(output)) {
                return "No output is applied.";
            }
            lastCommandOutputs.put(OUTPUT.getName(), output);
            return output;
        }
    }

    private final class OutputFailureFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(OUTPUT.getName())) {
                return (String) lastCommandOutputs.get(OUTPUT.getName());
            } else {
                return input.getStderr();
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
        final boolean configurationApplied = isConfigurationApplied();
        if(!configurationApplied) {
            int timeout = 60;
            while(timeout > 0) {
                if (configurationChangeInProgress.compareAndSet(false, true)) {
                    try {
                        getDriver().runApplyTask();
                        return;
                    } finally {
                        configurationChangeInProgress.set(false);
                    }
                } else {
                    Time.sleep(Duration.FIVE_SECONDS);
                    timeout -= 5;
                }
            }
            throw new IllegalStateException("Cannot apply configuration: operation timed out.");
        } else {
            throw new IllegalStateException("Cannot apply terraform plan: the configuration has already been applied.");
        }
    }

    @Override
    @Effector(description = "Destroy the Terraform configuration")
    public void destroy() {
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = configurationChangeInProgress.compareAndSet(false, true);
        if (configurationApplied && mayProceed) {
            try {
                getDriver().runDestroyTask();
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new IllegalStateException("Cannot destroy configuration: the configuration has not been applied.");
        } else {
            throw new IllegalStateException("Cannot destroy configuration: another operation is in progress.");
        }
    }

    @Override
    public void destroyTarget(ManagedResource child) {
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = !configurationChangeInProgress.compareAndSet(false, true);
        if (configurationApplied && mayProceed) {
            try {
                child.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING);
                final String destroyTargetCommand = getDriver().destroyCommand().concat(" -target=")
                    .concat(child.getConfig(ManagedResource.TYPE)).concat(".").concat(child.getConfig(ManagedResource.NAME));
                int result = getDriver().runDestroyTargetTask(destroyTargetCommand);
                if (result == 0 ) {
                    child.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
                    getParent().removeChild(child);
                } else {
                    child.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
                }
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new IllegalStateException("Cannot destroy target: the configuration has not been applied.");
        } else {
            throw new IllegalStateException("Cannot destroy target: another operation is in progress.");
        }
    }

    @Override
    public synchronized boolean isConfigurationApplied() {
        return getAttribute(CONFIGURATION_IS_APPLIED);
    }

    private Map<String, String> shellEnv() {
        return transformEntries(config().get(SHELL_ENVIRONMENT), new EnvironmentTransformer());
    }

    private static class EnvironmentTransformer implements EntryTransformer<String, Object, String> {
        @Override
        public String transformEntry(String key, Object value) {
            return TypeCoercions.coerce(value, String.class);
        }
    }
}
